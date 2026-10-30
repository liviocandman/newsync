package br.com.agregador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agregador.ingestion.*;
import br.com.agregador.sources.SourceRepository;
import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.collection.enabled=false")
class DatabaseIT {
  // Sem disabledWithoutDocker: indisponibilidade precisa falhar o verify.
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:7e32e9833a6fb1c92c32552794cb6ed569d51b445a54907d35fc112ef39684db");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("app.base-url", () -> "http://127.0.0.1:5173");
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @org.springframework.boot.test.web.server.LocalServerPort int port;
  @Autowired org.springframework.context.ApplicationContext context;

  @Test
  void normalProfileHasNoInitializerAndAllowsLocalReader() throws Exception {
    assertThat(context.containsBean("demoInitializer")).isFalse();
    assertThat(jdbc.sql("SELECT count(*) FROM sources").query(Long.class).single()).isZero();
    try (var client = java.net.http.HttpClient.newHttpClient()) {
      var response =
          client.send(
              java.net.http.HttpRequest.newBuilder(
                      java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/articles"))
                  .GET()
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("\"items\":[]", "\"nextCursor\":null");
    }
  }

  @Autowired JdbcClient jdbc;
  @Autowired FeedImporter importer;
  @Autowired FeedParser parser;
  @Autowired CollectionWriter writer;
  @Autowired SourceRepository sources;
  @Autowired br.com.agregador.articles.ArticleRepository articles;

  @BeforeEach
  void clean() {
    jdbc.sql("TRUNCATE articles,sources CASCADE").update();
  }

  InputStream fixture(String name) {
    return getClass().getResourceAsStream("/fixtures/" + name + ".xml");
  }

  UUID seed() {
    UUID id = sources.register("https://example.invalid/test", "Fonte de teste");
    importer.importFeed(id, 0, fixture("base"));
    return id;
  }

  List<Map<String, Object>> identity() {
    return jdbc.sql(
            "SELECT id,first_seen_at,sort_at,read_at,saved_at FROM articles ORDER BY external_id")
        .query()
        .listOfRows();
  }

  @Test
  void reimportsAndMergesWithoutLosingIdentityStateOrMissingFields() {
    UUID source = seed();
    assertThat(sources.register("https://example.invalid/test", "Fonte de teste"))
        .isEqualTo(source);
    jdbc.sql(
            "UPDATE articles SET read_at='2026-10-26T14:00:00Z',saved_at='2026-10-26T15:00:00Z' WHERE external_id='b00:horta'")
        .update();
    var before = identity();
    importer.importFeed(source, 0, fixture("base"));
    assertThat(identity()).isEqualTo(before);
    importer.importFeed(source, 0, fixture("edited"));
    assertThat(identity()).isEqualTo(before);
    assertThat(jdbc.sql("SELECT count(*) FROM articles").query(Long.class).single()).isEqualTo(3);
    var horta =
        jdbc.sql(
                "SELECT title,author,content_html,content_text,content_origin,completeness FROM articles WHERE external_id='b00:horta'")
            .query()
            .singleRow();
    assertThat(horta.get("title")).isEqualTo("A horta ganhou folhas");
    assertThat(horta.get("author")).isNotNull();
    assertThat(horta.get("content_text").toString()).contains("primeiras folhas");
    assertThat(horta.get("content_html").toString()).contains("<p>As primeiras folhas");
    assertThat(horta)
        .containsEntry("content_origin", "feed")
        .containsEntry("completeness", "unknown");
    assertThat(
            jdbc.sql("SELECT content_text FROM articles WHERE external_id='b00:chuva'")
                .query(String.class)
                .single())
        .contains("gotas");
    var empty =
        jdbc.sql(
                "SELECT content_html,content_text,content_origin,completeness FROM articles WHERE external_id='b00:caderno'")
            .query()
            .singleRow();
    assertThat(empty)
        .containsEntry("content_html", null)
        .containsEntry("content_text", null)
        .containsEntry("content_origin", "feed")
        .containsEntry("completeness", "unknown");
    importer.importFeed(source, 0, fixture("atom"));
    assertThat(
            jdbc.sql("SELECT count(*) FROM articles WHERE external_id='urn:b00:atom:1'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(sources.list().getFirst().lastResult()).isEqualTo("success");
  }

  @Test
  void t36RollsBackArticlesAndSourceMetadataWhenCommitFails() {
    UUID source = seed();
    jdbc.sql(
            "UPDATE articles SET read_at='2026-10-26T14:00:00Z',saved_at='2026-10-26T15:00:00Z' WHERE external_id='b00:horta'")
        .update();
    var beforeArticles = jdbc.sql("SELECT * FROM articles ORDER BY id").query().listOfRows();
    var beforeSources = jdbc.sql("SELECT * FROM sources ORDER BY id").query().listOfRows();
    var changed = new ArrayList<>(parser.parse(fixture("edited")).items());
    changed.addAll(parser.parse(fixture("atom")).items());
    assertThat(AopUtils.isAopProxy(writer)).isTrue();
    jdbc.sql(
            """
            CREATE FUNCTION b00_fail_commit() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'test-only deferred confirmation failure'; RETURN NEW; END $$
            """)
        .update();
    jdbc.sql(
            """
            CREATE CONSTRAINT TRIGGER b00_fail_commit AFTER UPDATE ON sources
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION b00_fail_commit()
            """)
        .update();
    try {
      assertThatThrownBy(() -> writer.confirm(source, 0, new FeedParser.Feed(changed, false)))
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining("test-only deferred confirmation failure");
      // Leituras novas, fora da transação que tentou confirmar. Sem @Transactional no teste.
      assertThat(jdbc.sql("SELECT * FROM articles ORDER BY id").query().listOfRows())
          .isEqualTo(beforeArticles);
      assertThat(jdbc.sql("SELECT * FROM sources ORDER BY id").query().listOfRows())
          .isEqualTo(beforeSources);
      assertThat(
              jdbc.sql("SELECT count(*) FROM articles WHERE external_id='urn:b00:atom:1'")
                  .query(Long.class)
                  .single())
          .isZero();
    } finally {
      jdbc.sql("DROP TRIGGER b00_fail_commit ON sources").update();
      jdbc.sql("DROP FUNCTION b00_fail_commit()").update();
    }
  }

  @Test
  void migratesAnEmptyPostgres() {
    assertThat(
            jdbc.sql("select count(*) from flyway_schema_history where success")
                .query(Long.class)
                .single())
        .isEqualTo(3);
    assertThat(jdbc.sql("select count(*) from sources").query(Long.class).single()).isZero();
  }

  private void collect(UUID source, String items) {
    String xml =
        "<rss version='2.0'><channel><title>Teste</title><link>https://example.invalid/</link>"
            + "<description>Teste</description>"
            + items
            + "</channel></rss>";
    importer.importFeed(
        source,
        0,
        new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private String item(String guid, String title, String url) {
    return "<item>"
        + (guid == null ? "" : "<guid isPermaLink='false'>" + guid + "</guid>")
        + "<title>"
        + title
        + "</title>"
        + (url == null ? "" : "<link>" + url + "</link>")
        + "</item>";
  }

  private void markAll() {
    jdbc.sql("UPDATE articles SET read_at='2026-10-26T14:00:00Z',saved_at='2026-10-26T15:00:00Z'")
        .update();
  }

  @Test
  void t06PromotesUniqueUrlToGuidAndPreservesKnownGuidWhenItDisappears() {
    UUID source = sources.register("https://example.invalid/identity", "Identidade");
    collect(source, item(null, "Mesmo   título", "https://EXAMPLE.invalid:443/article#trecho"));
    markAll();
    var before = identity();
    collect(
        source, item("opaque?signature=A%2fb", "mesmo título", "https://example.invalid/article"));
    assertThat(identity()).isEqualTo(before);
    assertThat(jdbc.sql("SELECT dedup_key,external_id FROM articles").query().singleRow())
        .containsEntry("external_id", "opaque?signature=A%2fb");
    assertThat(jdbc.sql("SELECT dedup_key FROM articles").query(String.class).single())
        .startsWith("v1:id:");
    collect(source, item(null, "MESMO TÍTULO", "https://example.invalid/article"));
    assertThat(identity()).isEqualTo(before);
    assertThat(jdbc.sql("SELECT external_id FROM articles").query(String.class).single())
        .isEqualTo("opaque?signature=A%2fb");
  }

  @Test
  void t06DistinctGuidsAndAmbiguousUrlsNeverMergeExistingIdentitiesOrStates() {
    UUID source = sources.register("https://example.invalid/conflicts", "Conflitos");
    collect(source, item("a", "Mesmo título", "https://example.invalid/shared"));
    markAll();
    UUID original =
        jdbc.sql("SELECT id FROM articles WHERE external_id='a'").query(UUID.class).single();
    collect(source, item("b", "Mesmo título", "https://example.invalid/shared"));
    assertThat(jdbc.sql("SELECT count(*) FROM articles").query(Long.class).single()).isEqualTo(2);
    assertThat(sources.list().getFirst().lastResult()).isEqualTo("partial");
    markAll();
    var before = identity();
    collect(
        source,
        item("a", "Título editado", "https://example.invalid/shared")
            + item("b", "Mesmo título", "https://example.invalid/shared")
            + item(null, "Mesmo título", "https://example.invalid/shared"));
    assertThat(jdbc.sql("SELECT id FROM articles WHERE external_id='a'").query(UUID.class).single())
        .isEqualTo(original);
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM articles WHERE read_at IS NOT NULL AND saved_at IS NOT NULL")
                .query(Long.class)
                .single())
        .isEqualTo(2);
    assertThat(identity()).containsAll(before);
    assertThat(jdbc.sql("SELECT count(*) FROM articles").query(Long.class).single()).isEqualTo(3);
    var repeated = identity();
    collect(
        source,
        item("a", "Título editado", "https://example.invalid/shared")
            + item("b", "Mesmo título", "https://example.invalid/shared")
            + item(null, "Mesmo título", "https://example.invalid/shared"));
    assertThat(identity()).isEqualTo(repeated);
  }

  @Test
  void t06ReusedUrlsAndChangedFingerprintsDoNotOverwriteEarlierArticles() {
    UUID source = sources.register("https://example.invalid/reused", "Reutilização");
    collect(source, item(null, "Primeira matéria", "https://example.invalid/reused"));
    markAll();
    var first = identity();
    collect(source, item(null, "Outra matéria", "https://example.invalid/reused"));
    assertThat(identity()).containsAll(first).hasSize(2);
    assertThat(
            jdbc.sql("SELECT title FROM articles WHERE read_at IS NOT NULL")
                .query(String.class)
                .single())
        .isEqualTo("Primeira matéria");
    collect(source, item(null, "Fingerprint sem URL", null));
    markAll();
    var fingerprints = identity();
    collect(source, item(null, "Fingerprint sem URL", null));
    assertThat(identity()).isEqualTo(fingerprints);
    collect(source, item(null, "Fingerprint alterado", null));
    assertThat(identity()).containsAll(fingerprints).hasSize(4);
  }

  @Test
  void t06AmbiguousUrlsInTheSameResponseUseFingerprintsAndCannotBePromotedApproximately() {
    UUID source = sources.register("https://example.invalid/ambiguous", "Ambiguidade");
    String response =
        item(null, "Primeiro", "https://example.invalid/shared")
            + item(null, "Segundo", "https://example.invalid/shared");
    collect(source, response);
    markAll();
    var before = identity();
    assertThat(jdbc.sql("SELECT dedup_key FROM articles").query(String.class).list())
        .allSatisfy(key -> assertThat(key).startsWith("v1:fp:"));
    collect(source, response);
    assertThat(identity()).isEqualTo(before);
    collect(source, item("new-guid", "Primeiro", "https://example.invalid/shared"));
    assertThat(identity()).containsAll(before).hasSize(3);
  }

  @Test
  void t11ConcurrentEditorialAndPersonalChangesPreserveBoth() throws Exception {
    UUID source = seed();
    UUID article =
        jdbc.sql("SELECT id FROM articles WHERE external_id='b00:horta'")
            .query(UUID.class)
            .single();
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      for (int attempt = 0; attempt < 12; attempt++) {
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var editorial =
            executor.submit(
                () -> {
                  barrier.await();
                  importer.importFeed(source, 0, fixture("edited"));
                  return null;
                });
        var personal =
            executor.submit(
                () -> {
                  barrier.await();
                  articles.setState(article, Map.of("read", true, "saved", true));
                  return null;
                });
        editorial.get(10, java.util.concurrent.TimeUnit.SECONDS);
        personal.get(10, java.util.concurrent.TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
    var result =
        jdbc.sql(
                "SELECT title,content_html,content_text,read_at,saved_at FROM articles WHERE id=:id")
            .param("id", article)
            .query()
            .singleRow();
    assertThat(result).containsEntry("title", "A horta ganhou folhas");
    assertThat(result.get("content_text").toString()).contains("primeiras folhas");
    assertThat(result.get("read_at")).isNotNull();
    assertThat(result.get("saved_at")).isNotNull();
  }

  @Test
  void editorialDateCorrectionPreservesSortAndMissingFieldsPreserveContent() {
    UUID source = sources.register("https://example.invalid/editorial", "Editorial");
    collect(
        source,
        "<item><guid>x</guid><title>Original</title><pubDate>Mon, 26 Oct 2026 12:00:00 GMT</pubDate>"
            + "<author>Autora</author><description>&lt;p&gt;Corpo anterior&lt;/p&gt;</description></item>");
    markAll();
    var before = identity();
    collect(
        source,
        "<item><guid>x</guid><title>Corrigido</title><author> </author><description> </description>"
            + "<pubDate>Sun, 25 Oct 2026 12:00:00 GMT</pubDate></item>");
    assertThat(identity()).isEqualTo(before);
    var value =
        jdbc.sql("SELECT title,author,content_html,content_text,published_at FROM articles")
            .query()
            .singleRow();
    assertThat(value)
        .containsEntry("title", "Corrigido")
        .containsEntry("author", "Autora")
        .containsEntry("content_html", "<p>Corpo anterior</p>")
        .containsEntry("content_text", "Corpo anterior");
    assertThat(value.get("published_at").toString()).contains("2026-10-25");
  }

  @Test
  void migratesV001WithExistingArticleAndPersonalStates() {
    String schema = "migration_b03_" + UUID.randomUUID().toString().replace("-", "");
    String url =
        POSTGRES.getJdbcUrl()
            + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
            + "currentSchema="
            + schema;
    try {
      org.flywaydb.core.Flyway.configure()
          .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
          .schemas(schema)
          .defaultSchema(schema)
          .target("1")
          .load()
          .migrate();
      var migrated =
          JdbcClient.create(
              new org.springframework.jdbc.datasource.DriverManagerDataSource(
                  url, POSTGRES.getUsername(), POSTGRES.getPassword()));
      UUID source = UUID.randomUUID();
      UUID article = UUID.randomUUID();
      migrated
          .sql(
              "INSERT INTO sources(id,feed_url,normalized_feed_url,title,created_at,updated_at) VALUES (:id,'https://example.invalid/old','https://example.invalid/old','V001',now(),now())")
          .param("id", source)
          .update();
      migrated
          .sql(
              "INSERT INTO articles(id,source_id,dedup_key,external_id,title,sort_at,first_seen_at,last_seen_at,updated_at,read_at,saved_at) VALUES (:id,:source,'v1:id:old','old','V001',now(),now(),now(),now(),'2026-10-26T14:00:00Z','2026-10-26T15:00:00Z')")
          .param("id", article)
          .param("source", source)
          .update();
      var before = migrated.sql("SELECT * FROM articles").query().singleRow();
      org.flywaydb.core.Flyway.configure()
          .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
          .schemas(schema)
          .defaultSchema(schema)
          .load()
          .migrate();
      assertThat(migrated.sql("SELECT * FROM articles").query().singleRow())
          .containsAllEntriesOf(before);
      assertThat(
              migrated
                  .sql("SELECT next_poll_at,manual_requested_at,last_result FROM sources")
                  .query()
                  .singleRow())
          .containsEntry("manual_requested_at", null)
          .containsEntry("last_result", "never");
      migrated
          .sql("UPDATE articles SET external_id=NULL WHERE id=:id")
          .param("id", article)
          .update();
      assertThat(
              migrated
                  .sql(
                      "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL")
                  .query(Long.class)
                  .single())
          .isEqualTo(3);
    } finally {
      jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
    }
  }

  @Test
  void t06PublicationEvidenceAllowsPromotionButConflictingDatesBlockUrlReconciliation() {
    UUID source = sources.register("https://example.invalid/dates", "Datas");
    String original =
        "<item><title>Antes</title><link>https://example.invalid/date</link>"
            + "<pubDate>Mon, 26 Oct 2026 12:00:00 GMT</pubDate></item>";
    collect(source, original);
    markAll();
    var before = identity();
    collect(
        source,
        "<item><guid>with-date</guid><title>Título corrigido</title><link>https://example.invalid/date</link>"
            + "<pubDate>Mon, 26 Oct 2026 12:00:00 GMT</pubDate></item>");
    assertThat(identity()).isEqualTo(before);
    collect(
        source,
        "<item><title>Título corrigido</title><link>https://example.invalid/date</link>"
            + "<pubDate>Tue, 27 Oct 2026 12:00:00 GMT</pubDate></item>");
    assertThat(identity()).containsAll(before).hasSize(2);
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM articles WHERE read_at IS NOT NULL AND saved_at IS NOT NULL")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void t36ManualCommitFailurePreservesPendingRequestAndValidatorsWithAllArticles() {
    UUID source = seed();
    markAll();
    sources.request(source, java.time.OffsetDateTime.parse("2026-10-29T14:00:00Z"));
    jdbc.sql(
            "UPDATE sources SET etag='previous',last_modified='Mon, 26 Oct 2026 12:00:00 GMT' WHERE id=:id")
        .param("id", source)
        .update();
    var captured = sources.find(source);
    var beforeArticles = jdbc.sql("SELECT * FROM articles ORDER BY id").query().listOfRows();
    var beforeSource =
        jdbc.sql("SELECT * FROM sources WHERE id=:id").param("id", source).query().singleRow();
    jdbc.sql(
            "CREATE FUNCTION b03_fail_manual_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test-only manual commit failure'; RETURN NEW; END $$")
        .update();
    jdbc.sql(
            "CREATE CONSTRAINT TRIGGER b03_fail_manual_commit AFTER UPDATE ON sources DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION b03_fail_manual_commit()")
        .update();
    try {
      var changed = parser.parse(fixture("edited"));
      assertThatThrownBy(
              () ->
                  writer.confirm(
                      source,
                      captured.lifecycleVersion(),
                      changed,
                      captured.manualRequestedAt(),
                      resolved ->
                          sources.complete(
                              captured,
                              resolved.partial() ? "partial" : "success",
                              "replacement",
                              null,
                              null,
                              null,
                              null,
                              resolved.ignoredItems(),
                              java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC))))
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining("test-only manual commit failure");
      assertThat(jdbc.sql("SELECT * FROM articles ORDER BY id").query().listOfRows())
          .isEqualTo(beforeArticles);
      assertThat(
              jdbc.sql("SELECT * FROM sources WHERE id=:id")
                  .param("id", source)
                  .query()
                  .singleRow())
          .isEqualTo(beforeSource);
      assertThat(sources.find(source).manualRequestedAt()).isEqualTo(captured.manualRequestedAt());
    } finally {
      jdbc.sql("DROP TRIGGER b03_fail_manual_commit ON sources").update();
      jdbc.sql("DROP FUNCTION b03_fail_manual_commit()").update();
    }
  }

  @Test
  void t06InvalidItemsStillMakeAResponseUrlAmbiguous() {
    UUID source = sources.register("https://example.invalid/invalid-ambiguity", "Ambiguidade");
    collect(source, item(null, "Mesmo título", "https://example.invalid/shared"));
    markAll();
    var before = identity();
    collect(
        source,
        item("would-promote", "Mesmo título", "https://example.invalid/shared")
            + "<item><link>https://example.invalid/shared</link></item>");
    assertThat(identity()).containsAll(before).hasSize(2);
    assertThat(
            jdbc.sql("SELECT external_id FROM articles WHERE read_at IS NOT NULL")
                .query()
                .singleRow())
        .containsEntry("external_id", null);
  }
}
