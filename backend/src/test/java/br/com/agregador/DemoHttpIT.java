package br.com.agregador;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agregador.ingestion.DemoInitializer;
import br.com.agregador.ingestion.FeedImporter;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class DemoHttpIT {
  @Test
  void demoNeverServesSourceManagementShell() throws Exception {
    try (var client = client()) {
      assertThat(get(client, "/sources").statusCode()).isEqualTo(403);
      assertThat(get(client, "/").statusCode()).isEqualTo(200);
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    DatabaseIT.database(properties);
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcClient jdbc;
  @Autowired FeedImporter importer;
  @Autowired DemoInitializer initializer;

  @BeforeEach
  void reset() throws Exception {
    jdbc.sql("TRUNCATE articles,sources CASCADE").update();
    initializer.run(null);
  }

  HttpClient client() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode node(HttpResponse<String> response) {
    return json.readTree(response.body());
  }

  HttpResponse<String> patch(
      HttpClient client, String id, String body, JsonNode csrf, String origin) throws Exception {
    var builder =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/articles/" + id + "/state"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Origin", origin);
    if (csrf != null)
      builder.header(csrf.get("headerName").asString(), csrf.get("token").asString());
    return client.send(
        builder.method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  String articleId() {
    return jdbc.sql("SELECT id::text FROM articles WHERE external_id='b00:horta'")
        .query(String.class)
        .single();
  }

  @Test
  void demoSearchesOnlyItsPersistedFixtureWithoutCsrf() throws Exception {
    try (var client = client()) {
      var response = get(client, "/api/v1/search?q=horta");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(node(response).get("items").get(0).get("id").asString()).isEqualTo(articleId());
      assertThat(node(response).get("hasMore").asBoolean()).isFalse();
      assertThat(get(client, "/api/v1/search?q=Uber").body()).contains("\"items\":[]");
    }
  }

  @Test
  void hostileHtmlIsCommittedSanitizedAndReturnedThroughHttp() throws Exception {
    assertThat(
            org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive())
        .isFalse();
    UUID source = jdbc.sql("SELECT id FROM sources").query(UUID.class).single();
    try (var input = getClass().getResourceAsStream("/fixtures/hostile.xml")) {
      importer.importFeed(source, 0, input);
    }
    String id;
    String html;
    String text;
    // Read again after the transactional proxy returns: API-only sanitization must fail.
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT id,content_html,content_text,content_origin,completeness FROM articles WHERE external_id='b00:hostile'")) {
      assertThat(connection.getAutoCommit()).isTrue();
      try (var row = statement.executeQuery()) {
        assertThat(row.next()).isTrue();
        id = row.getString("id");
        html = row.getString("content_html");
        text = row.getString("content_text");
        assertThat(row.getString("content_origin")).isEqualTo("feed");
        assertThat(row.getString("completeness")).isEqualTo("unknown");
        assertSanitizedHostileBody(html, text);
        assertThat(row.next()).isFalse();
      }
    }
    try (var client = client()) {
      var response = get(client, "/api/v1/articles/" + id);
      assertThat(response.statusCode()).isEqualTo(200);
      var detail = node(response);
      assertThat(detail.get("contentHtml").asString()).isEqualTo(html);
      assertThat(detail.get("contentText").asString()).isEqualTo(text);
      assertThat(detail.get("contentOrigin").asString()).isEqualTo("feed");
      assertThat(detail.get("completeness").asString()).isEqualTo("unknown");
      assertSanitizedHostileBody(
          detail.get("contentHtml").asString(), detail.get("contentText").asString());
    }
  }

  @Autowired javax.sql.DataSource dataSource;

  static void assertSanitizedHostileBody(String html, String text) {
    var body = org.jsoup.Jsoup.parseBodyFragment(html).body();
    assertThat(body.text())
        .isEqualTo(
            "Texto seguro. Link perigoso Link de dados Link FTP Link de email Link seguro Link HTTP");
    assertThat(text).isEqualTo(body.text());
    assertThat(body.select("a[href]").eachAttr("href"))
        .containsExactly("https://example.invalid/seguro", "http://example.invalid/seguro-http");
    for (var element : body.getAllElements()) {
      if (element == body) continue;
      assertThat(element.tagName()).isIn("p", "a");
      for (var attribute : element.attributes()) {
        assertThat(attribute.getKey()).isIn("href", "rel");
      }
      if (element.hasAttr("href")) {
        assertThat(URI.create(element.attr("href")).getScheme()).isIn("http", "https");
        assertThat(element.attr("rel")).isEqualTo("noopener noreferrer");
      }
    }
  }

  @Test
  void bootIsIdempotentAndReadyOnlyAfterImport() throws Exception {
    var before = jdbc.sql("SELECT id FROM articles ORDER BY id").query(UUID.class).list();
    initializer.run(null);
    assertThat(jdbc.sql("SELECT id FROM articles ORDER BY id").query(UUID.class).list())
        .isEqualTo(before);
    assertThat(jdbc.sql("SELECT count(*) FROM sources").query(Long.class).single()).isEqualTo(1);
    assertThat(get(client(), "/health/ready").statusCode()).isEqualTo(200);
    var source = node(get(client(), "/api/v1/sources")).get(0);
    assertThat(source.get("lastResult").asString()).isEqualTo("success");
    assertThat(source.get("lastSuccessAt").isNull()).isFalse();
  }

  @Test
  void detailContractForBodyAbsentBodyAndPreservedBody() throws Exception {
    var client = client();
    var list = node(get(client, "/api/v1/articles"));
    assertThat(list.get("items").size()).isEqualTo(3);
    assertThat(list.get("nextCursor").isNull()).isTrue();
    String id = articleId();
    var detail = node(get(client, "/api/v1/articles/" + id));
    assertThat(detail.get("read").asBoolean()).isFalse();
    assertThat(detail.get("contentOrigin").asString()).isEqualTo("feed");
    assertThat(detail.get("completeness").asString()).isEqualTo("unknown");
    assertThat(detail.get("contentText").asString()).contains("Três vasos");
    var sourceId = UUID.fromString(detail.get("sourceId").asString());
    importer.importFeed(sourceId, 0, getClass().getResourceAsStream("/fixtures/edited.xml"));
    var chuvaId =
        jdbc.sql("SELECT id::text FROM articles WHERE external_id='b00:chuva'")
            .query(String.class)
            .single();
    var preserved = node(get(client, "/api/v1/articles/" + chuvaId));
    assertThat(preserved.get("contentOrigin").asString()).isEqualTo("feed");
    assertThat(preserved.get("completeness").asString()).isEqualTo("unknown");
    assertThat(preserved.get("contentText").asString()).contains("gotas");
    String emptyId =
        jdbc.sql("SELECT id::text FROM articles WHERE external_id='b00:caderno'")
            .query(String.class)
            .single();
    var empty = node(get(client, "/api/v1/articles/" + emptyId));
    assertThat(empty.get("contentHtml").isNull()).isTrue();
    assertThat(empty.get("contentText").isNull()).isTrue();
    assertThat(empty.get("contentOrigin").asString()).isEqualTo("feed");
    assertThat(empty.get("completeness").asString()).isEqualTo("unknown");
    assertThat(node(get(client, "/api/v1/articles/" + id)).get("read").asBoolean()).isFalse();
  }

  @Test
  void validatesStateCsrfOriginIdsAndUnsupportedParameters() throws Exception {
    var client = client();
    var csrf = node(get(client, "/api/v1/auth/csrf"));
    String id = articleId();
    assertThat(patch(client, id, "{\"read\":true}", null, "http://127.0.0.1:5173").statusCode())
        .isEqualTo(403);
    assertThat(patch(client, id, "{\"read\":true}", csrf, "https://attacker.invalid").statusCode())
        .isEqualTo(403);
    for (String body :
        List.of(
            "{\"read\":null}",
            "{\"read\":\"true\"}",
            "{\"saved\":1}",
            "{}",
            "{\"other\":true}",
            "[]",
            "broken"))
      assertThat(patch(client, id, body, csrf, "http://127.0.0.1:5173").statusCode())
          .as(body)
          .isEqualTo(400);
    var missing =
        patch(
            client, UUID.randomUUID().toString(), "{\"read\":true}", csrf, "http://127.0.0.1:5173");
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(node(missing).get("code").asString()).isEqualTo("NOT_FOUND");
    assertThat(missing.body()).doesNotContain("SELECT", "Exception", "stackTrace");
    assertThat(get(client, "/api/v1/articles/not-uuid").statusCode()).isEqualTo(400);
    for (String query : List.of("?cursor=x", "?read=invalid", "?limit=0", "?sourceId=x")) {
      var response = get(client, "/api/v1/articles" + query);
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(node(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
    }
    var read = node(patch(client, id, "{\"read\":true}", csrf, "http://127.0.0.1:5173"));
    var repeat = node(patch(client, id, "{\"read\":true}", csrf, "http://127.0.0.1:5173"));
    assertThat(repeat.get("readAt")).isEqualTo(read.get("readAt"));
    var saved = node(patch(client, id, "{\"saved\":true}", csrf, "http://127.0.0.1:5173"));
    assertThat(saved.get("read").asBoolean()).isTrue();
    assertThat(
            node(patch(client, id, "{\"saved\":true}", csrf, "http://127.0.0.1:5173"))
                .get("savedAt"))
        .isEqualTo(saved.get("savedAt"));
    var unread = node(patch(client, id, "{\"read\":false}", csrf, "http://127.0.0.1:5173"));
    assertThat(unread.get("readAt").isNull()).isTrue();
    assertThat(unread.get("saved").asBoolean()).isTrue();
    var clear = node(patch(client, id, "{\"saved\":false}", csrf, "http://127.0.0.1:5173"));
    assertThat(clear.get("savedAt").isNull()).isTrue();
  }

  @Test
  void t11ConcurrentIndependentHttpPatchesPreserveBothFields() throws Exception {
    String id = articleId();
    var reader = client();
    var saver = client();
    var readCsrf = node(get(reader, "/api/v1/auth/csrf"));
    var savedCsrf = node(get(saver, "/api/v1/auth/csrf"));
    CountDownLatch prepared = new CountDownLatch(2), start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var read =
          executor.submit(
              () -> {
                prepared.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException();
                return patch(reader, id, "{\"read\":true}", readCsrf, "http://127.0.0.1:5173");
              });
      var saved =
          executor.submit(
              () -> {
                prepared.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException();
                return patch(saver, id, "{\"saved\":true}", savedCsrf, "http://127.0.0.1:5173");
              });
      assertThat(prepared.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(read.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      assertThat(saved.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    } finally {
      start.countDown();
    }
    var detail = node(get(client(), "/api/v1/articles/" + id));
    assertThat(detail.get("read").asBoolean()).isTrue();
    assertThat(detail.get("saved").asBoolean()).isTrue();
    assertThat(detail.get("readAt").isNull()).isFalse();
    assertThat(detail.get("savedAt").isNull()).isFalse();
  }

  @Test
  void demoRejectsRegistrationExternalRefreshAndSourceLifecycle() throws Exception {
    try (var client = client()) {
      var csrf = node(get(client, "/api/v1/auth/csrf"));
      String source = jdbc.sql("SELECT id::text FROM sources").query(String.class).single();
      var diagnostic = node(get(client, "/api/v1/sources/" + source));
      assertThat(diagnostic.get("collectionEnabled").asBoolean()).isFalse();
      assertThat(diagnostic.get("collecting").asBoolean()).isFalse();
      var before = jdbc.sql("SELECT * FROM sources").query().listOfRows();
      for (String[] operation :
          List.of(
              new String[] {"POST", "/sources", "{\"feedUrl\":\"https://example.org/feed\"}"},
              new String[] {"PATCH", "/sources/" + source, "{\"state\":\"paused\"}"},
              new String[] {"DELETE", "/sources/" + source, ""},
              new String[] {"POST", "/sources/" + source + "/refresh", ""})) {
        var response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1" + operation[1]))
                    .header("Origin", "http://127.0.0.1:5173")
                    .header("Content-Type", "application/json")
                    .header(csrf.get("headerName").asString(), csrf.get("token").asString())
                    .method(operation[0], HttpRequest.BodyPublishers.ofString(operation[2]))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(node(response).get("code").asString()).isEqualTo("FORBIDDEN");
      }
      assertThat(jdbc.sql("SELECT * FROM sources").query().listOfRows()).isEqualTo(before);
    }
  }
}
