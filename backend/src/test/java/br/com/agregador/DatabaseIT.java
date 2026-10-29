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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @org.springframework.boot.test.web.server.LocalServerPort int port;
  @Autowired org.springframework.context.ApplicationContext context;

  @Test
  void normalProfileHasNoInitializerOrAnonymousApi() throws Exception {
    assertThat(context.containsBean("demoInitializer")).isFalse();
    var client = java.net.http.HttpClient.newHttpClient();
    var response =
        client.send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/articles"))
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).contains("AUTH_REQUIRED");
    var cookies = new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL);
    var session = java.net.http.HttpClient.newBuilder().cookieHandler(cookies).build();
    String base = "http://127.0.0.1:" + port;
    var token =
        session.send(
            java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/v1/auth/csrf"))
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    var csrf = tools.jackson.databind.json.JsonMapper.builder().build().readTree(token.body());
    var write =
        session.send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(base + "/api/v1/articles/" + UUID.randomUUID() + "/state"))
                .header("Origin", "http://127.0.0.1:5173")
                .header("Content-Type", "application/json")
                .header(csrf.get("headerName").asString(), csrf.get("token").asString())
                .method(
                    "PATCH", java.net.http.HttpRequest.BodyPublishers.ofString("{\"read\":true}"))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(write.statusCode()).isEqualTo(401);
  }

  @Autowired JdbcClient jdbc;
  @Autowired FeedImporter importer;
  @Autowired FeedParser parser;
  @Autowired CollectionWriter writer;
  @Autowired SourceRepository sources;

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
        .isEqualTo(1);
    assertThat(jdbc.sql("select count(*) from sources").query(Long.class).single()).isZero();
  }
}
