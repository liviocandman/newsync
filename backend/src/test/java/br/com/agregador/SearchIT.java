package br.com.agregador;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import br.com.agregador.articles.*;
import br.com.agregador.ingestion.*;
import br.com.agregador.sources.SourceRepository;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.collection.enabled=false")
class SearchIT {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry p) {
    DatabaseIT.database(p);
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  @Autowired SourceRepository sources;
  @Autowired ArticleRepository articles;
  @Autowired ArticleService states;
  @Autowired FeedImporter importer;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @MockitoBean PublicFeedClient transport;
  HttpClient client;

  @BeforeEach
  void clean() {
    jdbc.sql("TRUNCATE articles,sources CASCADE").update();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void close() {
    client.close();
  }

  UUID source(String name) {
    return sources.register("https://" + name + ".invalid/feed", name);
  }

  UUID ingest(UUID source, String guid, String title, String body) {
    String xml =
        "<rss version='2.0'><channel><title>Teste</title><link>https://example.invalid</link><description>Teste</description><item><guid>"
            + guid
            + "</guid><title>"
            + title
            + "</title>"
            + (body == null ? "" : "<description><![CDATA[<p>" + body + "</p>]]></description>")
            + "</item></channel></rss>";
    importer.importFeed(source, 0, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    return jdbc.sql("SELECT id FROM articles WHERE source_id=:source AND external_id=:guid")
        .param("source", source)
        .param("guid", guid)
        .query(UUID.class)
        .single();
  }

  HttpResponse<String> get(String params) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/search" + params))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode search(String q, String filters) throws Exception {
    var response = get("?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + filters);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return json.readTree(response.body());
  }

  List<UUID> ids(JsonNode page) {
    var result = new ArrayList<UUID>();
    for (var item : page.get("items")) result.add(UUID.fromString(item.get("id").asString()));
    return result;
  }

  List<UUID> find(String q) throws Exception {
    return ids(search(q, ""));
  }

  @Test
  void t12HttpValidatesEveryParameterWithoutCsrfNetworkOrQueryLeakage() throws Exception {
    UUID source = source("validation");
    UUID article = ingest(source, "one", "CPU", "Uber workloads");
    for (String invalid :
        List.of(
            "",
            "?q=",
            "?q=%20%09",
            "?q=CPU&q=CPU",
            "?q=CPU&sourceId=a",
            "?q=CPU&sourceId=1-1-1-1-1",
            "?q=CPU&sourceId=",
            "?q=CPU&from=2026-10-29",
            "?q=CPU&from=2026-10-29T12:00:00",
            "?q=CPU&to=bad",
            "?q=CPU&from=2026-10-30T00:00:00Z&to=2026-10-29T00:00:00Z",
            "?q=CPU&from=2026-10-29T00:00:00Z&to=2026-10-29T00:00:00Z",
            "?q=CPU&limit=0",
            "?q=CPU&limit=101",
            "?q=CPU&limit=-1",
            "?q=CPU&limit=1.5",
            "?q=CPU&limit=2147483648",
            "?q=CPU&limit=",
            "?q=CPU&offset=-1",
            "?q=CPU&offset=2147483648",
            "?q=CPU&offset=1.1",
            "?q=CPU&offset=",
            "?q=CPU&limit=1&limit=2",
            "?q=CPU&sourceId=" + source + "&sourceId=" + source,
            "?q=CPU&from=2026-10-29T00:00:00Z&from=2026-10-29T00:00:00Z")) {
      var response = get(invalid);
      assertThat(response.statusCode()).as(invalid + " " + response.body()).isEqualTo(400);
      var error = json.readTree(response.body());
      assertThat(error.get("code").asString()).isEqualTo("INVALID_REQUEST");
      assertThat(error.get("requestId").asString()).isNotBlank();
      assertThat(response.body()).doesNotContain("SELECT", "tsquery", "CPU");
    }
    for (String extra : List.of("cursor=x", "saved=true", "unknown=x")) {
      var response = get("?q=CPU&" + extra);
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(json.readTree(response.body()).get("code").asString())
          .isEqualTo("UNSUPPORTED_PARAMETERS");
    }
    assertThat(ids(search("\u00a0 CPU \u2003", ""))).containsExactly(article);
    assertThat(search("😀".repeat(200), "").get("hasMore").asBoolean()).isFalse();
    assertThat(
            get("?q=" + URLEncoder.encode("😀".repeat(201), StandardCharsets.UTF_8)).statusCode())
        .isEqualTo(400);
    for (String q : List.of("% _ !", "'; DROP TABLE articles; --", "CPU OR missing", "CPU:*")) {
      var response = search(q, "");
      if (!q.equals("CPU:*")) assertThat(ids(response)).isEmpty();
    }
    assertThat(find("\"CPU\" % _")).containsExactly(article);
    assertThat(ids(search("CPU", "&sourceId=" + UUID.randomUUID()))).isEmpty();
    assertThat(ids(search("CPU", "&offset=2147483647"))).isEmpty();
    assertThat(jdbc.sql("SELECT count(*) FROM articles").query(Long.class).single()).isEqualTo(1);
    verifyNoInteractions(transport);
  }

  @Test
  void t12RanksTitleFiltersSortAtAndPaginatesStaticTies() throws Exception {
    UUID source = source("ranking"), second = source("other");
    UUID body = ingest(source, "body", "Outro", "PostgreSQL timeout");
    UUID title = ingest(source, "title", "PostgreSQL timeout", null);
    ingest(second, "other", "PostgreSQL timeout", null);
    jdbc.sql("UPDATE articles SET sort_at='2026-10-27T12:00:00Z'").update();
    jdbc.sql("UPDATE sources SET status='archived' WHERE id=:id").param("id", source).update();
    String filter = "&sourceId=" + source;
    assertThat(ids(search("PostgreSQL timeout", filter))).containsExactly(title, body);
    assertThat(
            ids(
                search(
                    "PostgreSQL timeout",
                    filter + "&from=2026-10-27T09:00:00-03:00&to=2026-10-27T12:00:01Z")))
        .containsExactly(title, body);
    assertThat(ids(search("PostgreSQL", filter + "&to=2026-10-27T12:00:00Z"))).isEmpty();
    assertThat(ids(search("PostgreSQL", filter + "&from=2026-10-27T12:00:01Z"))).isEmpty();
    jdbc.sql("UPDATE articles SET published_at='2020-01-01T00:00:00Z'").update();
    assertThat(ids(search("PostgreSQL", filter + "&from=2026-10-27T12:00:00Z")))
        .containsExactly(title, body);
    jdbc.sql("UPDATE sources SET status='paused' WHERE id=:id").param("id", source).update();
    assertThat(ids(search("PostgreSQL", filter))).hasSize(2);

    var tied = new ArrayList<UUID>();
    for (int i = 0; i < 5; i++) tied.add(ingest(second, "tie-" + i, "Desempate", null));
    jdbc.sql("UPDATE articles SET sort_at='2026-10-28T12:00:00Z' WHERE title='Desempate'").update();
    tied.sort(Comparator.comparing(UUID::toString).reversed());
    var observed = new ArrayList<UUID>();
    for (int offset = 0; offset < 6; offset += 2) {
      var page = search("Desempate", "&limit=2&offset=" + offset);
      observed.addAll(ids(page));
      assertThat(page.get("hasMore").asBoolean()).isEqualTo(offset < 4);
    }
    assertThat(observed).containsExactlyElementsOf(tied);
    var beyond = search("Desempate", "&limit=2&offset=6");
    assertThat(ids(beyond)).isEmpty();
    assertThat(beyond.get("hasMore").asBoolean()).isFalse();
    jdbc.sql("UPDATE articles SET sort_at='2026-10-29T12:00:00Z' WHERE id=:id")
        .param("id", tied.getLast())
        .update();
    assertThat(find("Desempate").getFirst()).isEqualTo(tied.getLast());
    verifyNoInteractions(transport);
  }

  @Test
  void t12EditorialCommitPreservesBodyReplacesTermsAndRollsBackWithPersonalConcurrency()
      throws Exception {
    UUID source = source("editorial");
    UUID id = ingest(source, "same", "Antigo", "corpo preservado");
    states.setState(id, Map.of("saved", true));
    var identity =
        jdbc.sql("SELECT id,sort_at,first_seen_at,saved_at FROM articles").query().singleRow();
    ingest(source, "same", "Novo", null);
    assertThat(find("Antigo")).isEmpty();
    assertThat(find("corpo preservado")).containsExactly(id);
    assertThat(find("Novo")).containsExactly(id);
    var before =
        jdbc.sql("SELECT search_document::text FROM articles").query(String.class).single();
    states.setState(id, Map.of("read", true));
    assertThat(jdbc.sql("SELECT search_document::text FROM articles").query(String.class).single())
        .isEqualTo(before);

    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var editorial =
          executor.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          status -> {
                            ingest(source, "same", "Final", "substituto");
                            entered.countDown();
                            try {
                              if (!release.await(4, TimeUnit.SECONDS))
                                throw new IllegalStateException();
                            } catch (InterruptedException error) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(error);
                            }
                            return true;
                          }));
      try {
        assertThat(entered.await(4, TimeUnit.SECONDS)).isTrue();
        assertThat(find("Novo")).containsExactly(id);
        assertThat(find("Final")).isEmpty();
        var personal = executor.submit(() -> states.setState(id, Map.of("read", false)));
        release.countDown();
        editorial.get(5, TimeUnit.SECONDS);
        personal.get(5, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }
    assertThat(find("corpo preservado")).isEmpty();
    assertThat(find("Final substituto")).containsExactly(id);
    assertThat(articles.find(id).read()).isFalse();
    assertThat(articles.find(id).saved()).isTrue();
    assertThat(
            jdbc.sql("SELECT id,sort_at,first_seen_at,saved_at FROM articles").query().singleRow())
        .isEqualTo(identity);
    var committed =
        jdbc.sql("SELECT *,search_document::text AS vector FROM articles").query().singleRow();
    new TransactionTemplate(transactions)
        .execute(
            status -> {
              ingest(source, "same", "Rollback", "descartado");
              status.setRollbackOnly();
              return null;
            });
    assertThat(
            jdbc.sql("SELECT *,search_document::text AS vector FROM articles").query().singleRow())
        .isEqualTo(committed);
    assertThat(find("Rollback descartado")).isEmpty();
    assertThat(find("Final substituto")).containsExactly(id);
  }

  @Test
  void t12TenPersistedSearchesAndSimpleLinguisticLimits() throws Exception {
    UUID technical = source("tecnica"), security = source("seguranca");
    String[][] corpus = {
      {"Uber CPU em cargas com estado", "stateful workloads"},
      {"Kubernetes control plane failures", "distributed control"},
      {"Git infrastructure", "scale repositories"},
      {"Banco de dados", "PostgreSQL timeout"},
      {"Java CSRF", "proteção local"},
      {"DNS rebinding", "resolução segura"},
      {"Segurança de aplicações", "revisão"},
      {"Kubernetes no período", "agenda"},
      {"Chamada sem corpo", null},
      {"Memória arquivada", "preservação"}
    };
    var expected = new ArrayList<UUID>();
    for (int i = 0; i < corpus.length; i++)
      expected.add(ingest(i >= 6 ? security : technical, "p" + i, corpus[i][0], corpus[i][1]));
    jdbc.sql("UPDATE articles SET sort_at='2026-10-27T12:00:00Z'").update();
    states.setState(expected.get(9), Map.of("saved", true));
    jdbc.sql("UPDATE sources SET status='archived' WHERE id=:id").param("id", security).update();
    String[] queries = {
      "Uber CPU",
      "Kubernetes control plane",
      "Git infrastructure",
      "PostgreSQL timeout",
      "Java CSRF",
      "DNS rebinding",
      "Segurança",
      "Kubernetes",
      "Chamada sem corpo",
      "Memória arquivada"
    };
    for (int i = 0; i < queries.length; i++) {
      String filters =
          i == 6
              ? "&sourceId=" + security
              : i == 7
                  ? "&sourceId=" + security + "&from=2026-10-27T00:00:00Z&to=2026-10-28T00:00:00Z"
                  : "";
      var result = search(queries[i], filters);
      assertThat(ids(result)).containsExactly(expected.get(i));
      System.out.println(
          "T12_SEARCH P"
              + (i + 1)
              + " query="
              + queries[i]
              + " filters="
              + filters
              + " expected="
              + expected.get(i)
              + " observed="
              + ids(result));
    }
    UUID language =
        ingest(technical, "linguistic", "Ação servidor", "English control plane pontuação");
    assertThat(find("AÇÃO")).containsExactly(language);
    assertThat(find("acao")).isEmpty();
    assertThat(find("servidor")).containsExactly(language);
    assertThat(find("servidores")).isEmpty();
    assertThat(find("\"English\", control! plane?")).containsExactly(language);
    assertThat(find("inglês")).isEmpty();
    assertThat(find("% _ !")).isEmpty();
    System.out.println(
        "T12_SIMPLE case=equal accent=different inflection=different punctuation=ignored english=literal noLexemes=empty");
    verifyNoInteractions(transport);
  }

  @Test
  void t12V002BackfillPreservesOldDataAndBuildsGinOnControlledCorpus() {
    String schema = "search_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    String base = DatabaseIT.POSTGRES.getJdbcUrl();
    String url = base + (base.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    String user = DatabaseIT.POSTGRES.getUsername(), password = DatabaseIT.POSTGRES.getPassword();
    try {
      Flyway.configure()
          .dataSource(url, user, password)
          .schemas(schema)
          .defaultSchema(schema)
          .target("002")
          .load()
          .migrate();
      JdbcClient old =
          JdbcClient.create(
              new org.springframework.jdbc.datasource.DriverManagerDataSource(url, user, password));
      UUID source = UUID.randomUUID();
      old.sql(
              "INSERT INTO sources(id,feed_url,normalized_feed_url,title,status,created_at,updated_at) VALUES (:id,'https://old.invalid','https://old.invalid','Antiga','archived',now(),now())")
          .param("id", source)
          .update();
      old.sql(
              "INSERT INTO articles(id,source_id,dedup_key,title,content_html,content_text,sort_at,first_seen_at,last_seen_at,updated_at,read_at,saved_at) SELECT md5(n::text)::uuid,:source,'old-' || n,CASE WHEN n=1 THEN 'Rarissimo' ELSE 'Chamada técnica' END,CASE WHEN n=2 THEN NULL ELSE '<p>corpo anterior</p>' END,CASE WHEN n=2 THEN NULL ELSE 'corpo anterior' END,'2026-10-27T12:00:00Z',now(),now(),now(),now(),now() FROM generate_series(1,12000) n")
          .param("source", source)
          .update();
      var before =
          old.sql("SELECT id,read_at,saved_at,first_seen_at,sort_at FROM articles ORDER BY id")
              .query()
              .listOfRows();
      var history =
          old.sql(
                  "SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY version")
              .query()
              .listOfRows();
      long started = System.nanoTime();
      Flyway flyway =
          Flyway.configure()
              .dataSource(url, user, password)
              .schemas(schema)
              .defaultSchema(schema)
              .load();
      flyway.migrate();
      System.out.println(
          "T12_BACKFILL rows=12000 milliseconds=" + (System.nanoTime() - started) / 1_000_000);
      flyway.validate();
      assertThat(
              old.sql("SELECT id,read_at,saved_at,first_seen_at,sort_at FROM articles ORDER BY id")
                  .query()
                  .listOfRows())
          .isEqualTo(before);
      assertThat(
              old.sql(
                      "SELECT version,checksum FROM flyway_schema_history WHERE version IN ('001','002') ORDER BY version")
                  .query()
                  .listOfRows())
          .isEqualTo(history);
      assertThat(
              old.sql(
                      "SELECT count(*) FROM articles WHERE search_document @@ plainto_tsquery('simple','corpo anterior')")
                  .query(Long.class)
                  .single())
          .isEqualTo(11999);
      assertThat(
              old.sql(
                      "SELECT count(*) FROM articles WHERE search_document @@ plainto_tsquery('simple','Chamada técnica')")
                  .query(Long.class)
                  .single())
          .isEqualTo(11999);
      assertThat(
              old.sql(
                      "SELECT count(*) FROM articles WHERE search_document <> (setweight(to_tsvector('simple',title),'A') || setweight(to_tsvector('simple',coalesce(content_text,'')),'B'))")
                  .query(Long.class)
                  .single())
          .isZero();
      assertThat(
              old.sql(
                      "SELECT indexdef FROM pg_indexes WHERE schemaname=:schema AND indexname='articles_search_document'")
                  .param("schema", schema)
                  .query(String.class)
                  .single())
          .contains("USING gin (search_document)");
      old.sql("ANALYZE articles").update();
      var plan =
          old.sql(
                  "EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM articles CROSS JOIN plainto_tsquery('simple','Rarissimo') query WHERE numnode(query)>0 AND search_document @@ query ORDER BY ts_rank(search_document,query) DESC,sort_at DESC,id DESC LIMIT 31 OFFSET 0")
              .query(String.class)
              .list();
      assertThat(String.join("\n", plan)).contains("articles_search_document");
      System.out.println("T12_EXPLAIN\n" + String.join("\n", plan));
    } finally {
      jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
    }
  }
}
