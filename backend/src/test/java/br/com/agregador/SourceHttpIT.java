package br.com.agregador;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import br.com.agregador.ingestion.*;
import br.com.agregador.sources.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.collection.initial-delay-ms=3600000", "app.collection.delay-ms=3600000"})
class SourceHttpIT {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry p) {
    DatabaseIT.database(p);
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  ManualFeedDispatcher dispatcher;
  @Autowired CollectionActivity activity;
  @Autowired FeedParser parser;
  @Autowired CollectionWriter writer;
  @Autowired SourceRepository sources;
  @Autowired SourceService service;
  @MockitoBean PublicFeedClient transport;
  @MockitoBean Clock clock;
  final Instant time = Instant.parse("2026-10-29T12:00:00Z");
  HttpClient client;
  JsonNode csrf;

  @BeforeEach
  void resetAll() throws Exception {
    jdbc.sql("TRUNCATE articles,sources CASCADE").update();
    reset(transport, clock);
    when(clock.instant()).thenReturn(time);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    csrf = body(request("GET", "/auth/csrf", null, null, false));
    activity.clear();
    dispatcher =
        new ManualFeedDispatcher(
            sources, service, activity, transport, parser, writer, clock, true);
    dispatcher.ready();
  }

  @AfterEach
  void close() {
    client.close();
  }

  HttpResponse<String> request(
      String method, String path, String data, String origin, boolean token) throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
            .timeout(Duration.ofSeconds(20));
    if (origin != null) b.header("Origin", origin);
    if (token) b.header(csrf.get("headerName").asString(), csrf.get("token").asString());
    if (data != null) b.header("Content-Type", "application/json");
    return client.send(
        b.method(
                method,
                data == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(data))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> write(String method, String path, String data) throws Exception {
    return request(method, path, data, "http://127.0.0.1:5173", true);
  }

  JsonNode body(HttpResponse<String> r) {
    return json.readTree(r.body());
  }

  String create() throws Exception {
    var r =
        write(
            "POST",
            "/sources",
            "{\"feedUrl\":\"https://example.org/feed?key=kept\",\"title\":\"Minha fonte\"}");
    assertThat(r.statusCode()).isEqualTo(201);
    return body(r).get("id").asString();
  }

  static byte[] xml(String title) {
    return ("<rss version='2.0'><channel><title>F</title><link>https://example.org</link><description>F</description><item><guid>opaque?keep=1</guid><title>"
            + title
            + "</title><link>https://example.org/story</link><description>&lt;p&gt;Corpo&lt;/p&gt;</description></item></channel></rss>")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void registersPendingWithoutDownloadAndValidatesLifecycleAndDuplicates() throws Exception {
    String id = create();
    dispatcher.tick();
    verifyNoInteractions(transport);
    assertThat(
            write(
                    "POST",
                    "/sources",
                    "{\"feedUrl\":\"https://example.org/" + "x".repeat(9000) + "\"}")
                .statusCode())
        .isEqualTo(413);
    var source = body(request("GET", "/sources/" + id, null, null, false));
    assertThat(source.get("state").asString()).isEqualTo("active");
    assertThat(source.get("lastResult").asString()).isEqualTo("never");
    assertThat(source.get("manualRequestedAt").isNull()).isTrue();
    assertThat(
            write("POST", "/sources", "{\"feedUrl\":\"HTTPS://EXAMPLE.ORG:443/feed?key=kept\"}")
                .statusCode())
        .isEqualTo(409);
    assertThat(write("PATCH", "/sources/" + id, "{\"feedUrl\":\"https://other.org\"}").statusCode())
        .isEqualTo(400);
    assertThat(
            write("PATCH", "/sources/" + id, "{\"title\":\"Novo nome\",\"state\":\"paused\"}")
                .statusCode())
        .isEqualTo(200);
    assertThat(write("POST", "/sources/" + id + "/refresh", null).statusCode()).isEqualTo(409);
    assertThat(write("DELETE", "/sources/" + id, null).statusCode()).isEqualTo(204);
    assertThat(
            write("POST", "/sources", "{\"feedUrl\":\"https://example.org/feed?key=kept\"}")
                .statusCode())
        .isEqualTo(409);
    assertThat(write("PATCH", "/sources/" + id, "{\"state\":\"active\"}").statusCode())
        .isEqualTo(200);
    assertThat(write("GET", "/sources/" + UUID.randomUUID(), null).statusCode()).isEqualTo(404);
    assertThat(jdbc.sql("SELECT lifecycle_version FROM sources").query(Long.class).single())
        .isEqualTo(3);
  }

  @Test
  void csrfCodesOriginPrecedenceAndNoWritesOnEveryNegative() throws Exception {
    String id = create();
    String path = "/sources/" + id;
    var before = jdbc.sql("SELECT * FROM sources").query().listOfRows();
    for (String origin : Arrays.asList(null, "null", "https://attacker.invalid")) {
      var rejected = request("PATCH", path, "{\"title\":\"Bad\"}", origin, false);
      assertThat(rejected.statusCode()).isEqualTo(403);
      assertThat(body(rejected).get("code").asString()).isEqualTo("ORIGIN_REJECTED");
    }
    var missing = request("PATCH", path, "{\"title\":\"Bad\"}", "http://127.0.0.1:5173", false);
    assertThat(missing.statusCode()).isEqualTo(403);
    assertThat(body(missing).get("code").asString()).isEqualTo("CSRF_INVALID");
    csrf = json.readTree("{\"headerName\":\"X-CSRF-TOKEN\",\"token\":\"tampered\"}");
    var invalid = write("PATCH", path, "{\"title\":\"Bad\"}");
    assertThat(body(invalid).get("code").asString()).isEqualTo("CSRF_INVALID");
    assertThat(jdbc.sql("SELECT * FROM sources").query().listOfRows()).isEqualTo(before);
    csrf = body(request("GET", "/auth/csrf", null, null, false));
    var forbidden = write("POST", "/articles", "{}");
    assertThat(forbidden.statusCode()).isEqualTo(403);
    assertThat(body(forbidden).get("code").asString()).isEqualTo("FORBIDDEN");
    assertThat(
            request("GET", "/auth/csrf", null, null, false).headers().firstValue("Cache-Control"))
        .contains("no-store");
  }

  @Test
  void manualRequestIsDurableSequentialAndConditionalWithPersistedCooldown() throws Exception {
    String id = create();
    when(transport.fetch(anyString(), isNull(), isNull()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              assertThat(Thread.currentThread().getName()).doesNotContain("http-nio");
              return new PublicFeedClient.Response(200, xml("Primeiro"), "\"v1\"", null, null);
            });
    assertThat(write("POST", "/sources/" + id + "/refresh", null).statusCode()).isEqualTo(202);
    assertThat(write("POST", "/sources/" + id + "/refresh", null).statusCode()).isEqualTo(202);
    assertThat(sources.find(UUID.fromString(id)).manualRequestedAt()).isNotNull();
    verifyNoInteractions(transport);
    dispatcher.tick();
    assertThat(jdbc.sql("SELECT count(*) FROM articles").query(Long.class).single()).isEqualTo(1);
    var done = sources.find(UUID.fromString(id));
    assertThat(done.manualRequestedAt()).isNull();
    assertThat(done.etag()).isEqualTo("\"v1\"");
    assertThat(write("POST", "/sources/" + id + "/refresh", null).statusCode()).isEqualTo(429);
    when(clock.instant()).thenReturn(time.plusSeconds(61));
    when(transport.fetch(anyString(), eq("\"v1\""), isNull()))
        .thenReturn(
            new PublicFeedClient.Response(304, new byte[0], null, null, time.plusSeconds(3600)));
    write("POST", "/sources/" + id + "/refresh", null);
    dispatcher.tick();
    assertThat(sources.find(UUID.fromString(id)).lastResult()).isEqualTo("not_modified");
    var limited = write("POST", "/sources/" + id + "/refresh", null);
    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(limited.headers().firstValue("Retry-After")).contains("3539");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "paused,false",
    "paused,true",
    "archived,false",
    "archived,true"
  })
  void staleDownloadCannotChangeReactivatedSourceOrConsumeNewRequest(String state, boolean failed)
      throws Exception {
    String id = create();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(transport.fetch(anyString(), isNull(), isNull()))
        .thenAnswer(
            call -> {
              started.countDown();
              if (!release.await(10, TimeUnit.SECONDS)) throw new TimeoutException();
              if (failed)
                throw new FeedTransportException(
                    "HTTP_503", "Falha antiga", time.plusSeconds(3600));
              return new PublicFeedClient.Response(200, xml("Resposta antiga"), "old", null, null);
            });
    write("POST", "/sources/" + id + "/refresh", null);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var future = executor.submit(dispatcher::tick);
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(body(write("POST", "/sources/" + id + "/refresh", null)).get("status").asString())
          .isEqualTo("collecting");
      if (state.equals("archived")) write("DELETE", "/sources/" + id, null);
      else write("PATCH", "/sources/" + id, "{\"state\":\"paused\"}");
      write("PATCH", "/sources/" + id, "{\"state\":\"active\"}");
      when(clock.instant()).thenReturn(time.plusSeconds(61));
      var next = write("POST", "/sources/" + id + "/refresh", null);
      assertThat(body(next).get("status").asString()).isEqualTo("scheduled");
      release.countDown();
      future.get(15, TimeUnit.SECONDS);
      assertThat(jdbc.sql("SELECT count(*) FROM articles").query(Long.class).single()).isZero();
      assertThat(sources.find(UUID.fromString(id)).manualRequestedAt()).isNotNull();
      doReturn(new PublicFeedClient.Response(200, xml("Resposta nova"), "new", null, null))
          .when(transport)
          .fetch(anyString(), isNull(), isNull());
      dispatcher.tick();
      assertThat(jdbc.sql("SELECT title FROM articles").query(String.class).single())
          .isEqualTo("Resposta nova");
      assertThat(sources.find(UUID.fromString(id)).manualRequestedAt()).isNull();
    } finally {
      release.countDown();
    }
  }

  @Test
  void feedPaginationUsesTiesFiltersAndRejectsInvalidOrReusedCursors() throws Exception {
    String source = create();
    var sourceId = UUID.fromString(source);
    var articleIds = new ArrayList<UUID>();
    for (int i = 0; i < 7; i++) {
      UUID id = UUID.randomUUID();
      articleIds.add(id);
      jdbc.sql(
              "INSERT INTO articles(id,source_id,dedup_key,title,sort_at,first_seen_at,last_seen_at,updated_at,read_at,saved_at) VALUES (:id,:source,:key,:title,:now,:now,:now,:now,NULL,:saved)")
          .param("id", id)
          .param("source", sourceId)
          .param("key", "k" + i)
          .param("title", "Artigo " + i)
          .param("now", time.atOffset(ZoneOffset.UTC))
          .param(
              "saved",
              i < 5 ? time.atOffset(ZoneOffset.UTC) : null,
              java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
          .update();
    }
    UUID other = sources.register("https://other.example/feed", "Outra fonte");
    jdbc.sql(
            "INSERT INTO articles(id,source_id,dedup_key,title,sort_at,first_seen_at,last_seen_at,updated_at,saved_at) VALUES (:id,:source,'other','Outra',:now,:now,:now,:now,:now)")
        .param("id", UUID.randomUUID())
        .param("source", other)
        .param("now", time.atOffset(ZoneOffset.UTC))
        .update();
    var found = new LinkedHashSet<String>();
    String cursor = null;
    do {
      var page =
          body(
              request(
                  "GET",
                  "/articles?sourceId="
                      + source
                      + "&saved=true&read=false&limit=2"
                      + (cursor == null ? "" : "&cursor=" + cursor),
                  null,
                  null,
                  false));
      for (var item : page.get("items")) assertThat(found.add(item.get("id").asString())).isTrue();
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
      if (cursor != null)
        assertThat(
                request(
                        "GET",
                        "/articles?sourceId="
                            + source
                            + "&saved=false&read=false&limit=2&cursor="
                            + cursor,
                        null,
                        null,
                        false)
                    .statusCode())
            .isEqualTo(400);
    } while (cursor != null);
    assertThat(found).hasSize(5);
    assertThat(new ArrayList<>(found))
        .isEqualTo(
            articleIds.subList(0, 5).stream()
                .map(UUID::toString)
                .sorted(Comparator.reverseOrder())
                .toList());
    assertThat(
            body(request(
                    "GET",
                    "/articles?sourceId=" + other + "&saved=true&read=false&limit=100",
                    null,
                    null,
                    false))
                .get("items")
                .size())
        .isEqualTo(1);
    assertThat(
            body(request(
                    "GET",
                    "/articles?sourceId=" + source + "&saved=true&read=true",
                    null,
                    null,
                    false))
                .get("items")
                .size())
        .isZero();
    for (String query :
        List.of(
            "limit=0",
            "limit=101",
            "read=x",
            "saved=1",
            "sourceId=x",
            "cursor=broken",
            "limit=2&limit=3",
            "other=1"))
      assertThat(request("GET", "/articles?" + query, null, null, false).statusCode())
          .as(query)
          .isEqualTo(400);
  }

  @Test
  void partialAndFailedResultsPreserveLastSuccessAndValidators() throws Exception {
    String id = create();
    when(transport.fetch(anyString(), isNull(), isNull()))
        .thenReturn(new PublicFeedClient.Response(200, xml("Completo"), "v1", null, null));
    write("POST", "/sources/" + id + "/refresh", null);
    dispatcher.tick();
    var success = sources.find(UUID.fromString(id)).lastSuccessAt();
    when(clock.instant()).thenReturn(time.plusSeconds(61));
    String partial =
        new String(xml("Válido"), StandardCharsets.UTF_8)
            .replace("</channel>", "<item><guid>invalid</guid></item></channel>");
    when(transport.fetch(anyString(), eq("v1"), isNull()))
        .thenReturn(
            new PublicFeedClient.Response(
                200, partial.getBytes(StandardCharsets.UTF_8), "v2", null, null));
    write("POST", "/sources/" + id + "/refresh", null);
    dispatcher.tick();
    var result = sources.find(UUID.fromString(id));
    assertThat(result.lastResult()).isEqualTo("partial");
    assertThat(result.etag()).isEqualTo("v1");
    assertThat(result.lastSuccessAt()).isEqualTo(success);
    assertThat(result.ignoredItems()).isEqualTo(1);
    when(clock.instant()).thenReturn(time.plusSeconds(122));
    when(transport.fetch(anyString(), eq("v1"), isNull()))
        .thenThrow(new FeedTransportException("HTTP_503", "Servidor indisponível", null));
    write("POST", "/sources/" + id + "/refresh", null);
    dispatcher.tick();
    result = sources.find(UUID.fromString(id));
    assertThat(result.lastResult()).isEqualTo("failed");
    assertThat(result.etag()).isEqualTo("v1");
    assertThat(result.lastSuccessAt()).isEqualTo(success);
    assertThat(result.manualRequestedAt()).isNull();
  }

  @Test
  void dispatcherRetainsRequestAfterCommitFailureAndWaitsBeforeRetry() throws Exception {
    String id = create();
    when(transport.fetch(anyString(), isNull(), isNull()))
        .thenReturn(new PublicFeedClient.Response(200, xml("Novo"), "v1", null, null));
    write("POST", "/sources/" + id + "/refresh", null);
    jdbc.sql(
            "CREATE FUNCTION b03_fail_result() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.last_result <> 'never' THEN RAISE EXCEPTION 'test-only failure'; END IF; RETURN NEW; END $$")
        .update();
    jdbc.sql(
            "CREATE CONSTRAINT TRIGGER b03_fail_result AFTER UPDATE ON sources DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION b03_fail_result()")
        .update();
    try {
      dispatcher.tick();
      assertThat(sources.find(UUID.fromString(id)).manualRequestedAt()).isNotNull();
      assertThat(jdbc.sql("SELECT count(*) FROM articles").query(Long.class).single()).isZero();
      dispatcher.tick();
      verify(transport, times(1)).fetch(anyString(), isNull(), isNull());
    } finally {
      jdbc.sql("DROP TRIGGER b03_fail_result ON sources").update();
      jdbc.sql("DROP FUNCTION b03_fail_result()").update();
    }
    when(clock.instant()).thenReturn(time.plusSeconds(5));
    dispatcher.tick();
    assertThat(sources.find(UUID.fromString(id)).manualRequestedAt()).isNull();
    assertThat(jdbc.sql("SELECT count(*) FROM articles").query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void invalidXmlPreservesHttpRetryAfterAndBlocksNewRequest() throws Exception {
    String id = create();
    when(transport.fetch(anyString(), isNull(), isNull()))
        .thenReturn(
            new PublicFeedClient.Response(
                200,
                "<html/>".getBytes(StandardCharsets.UTF_8),
                null,
                null,
                time.plusSeconds(3600)));
    write("POST", "/sources/" + id + "/refresh", null);
    dispatcher.tick();
    assertThat(sources.find(UUID.fromString(id)).lastResult()).isEqualTo("failed");
    when(clock.instant()).thenReturn(time.plusSeconds(61));
    var limited = write("POST", "/sources/" + id + "/refresh", null);
    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(limited.headers().firstValue("Retry-After")).contains("3539");
  }

  @Test
  void aFreshDispatcherResumesOnlyDurableEligibleManualRequests() throws Exception {
    String id = create();
    write("POST", "/sources/" + id + "/refresh", null);
    jdbc.sql("UPDATE sources SET retry_after_at=:retry WHERE id=:id")
        .param("retry", time.plusSeconds(120).atOffset(ZoneOffset.UTC))
        .param("id", UUID.fromString(id))
        .update();
    var fresh =
        new ManualFeedDispatcher(
            sources, service, activity, transport, parser, writer, clock, true);
    fresh.ready();
    fresh.tick();
    verifyNoInteractions(transport);
    assertThat(sources.find(UUID.fromString(id)).manualRequestedAt()).isNotNull();
    when(clock.instant()).thenReturn(time.plusSeconds(121));
    when(transport.fetch(anyString(), isNull(), isNull()))
        .thenReturn(new PublicFeedClient.Response(200, xml("Retomado"), null, null, null));
    fresh.tick();
    assertThat(sources.find(UUID.fromString(id)).manualRequestedAt()).isNull();
    assertThat(jdbc.sql("SELECT title FROM articles").query(String.class).single())
        .isEqualTo("Retomado");
  }

  @Test
  void sharedHttpParameterValidationPreservesUnsupportedCode() throws Exception {
    String id = create();
    var wrongType =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/sources"))
                .header("Origin", "http://127.0.0.1:5173")
                .header("Content-Type", "text/plain")
                .header(csrf.get("headerName").asString(), csrf.get("token").asString())
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(wrongType.statusCode()).as(wrongType.body()).isEqualTo(400);
    assertThat(write("POST", "/sources/" + id + "/refresh", "{\"other\":true}").statusCode())
        .isEqualTo(400);
    for (String path :
        List.of(
            "/sources?other=1",
            "/sources/" + id + "?other=1",
            "/articles/" + UUID.randomUUID() + "?other=1")) {
      var response = request("GET", path, null, null, false);
      assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(400);
      assertThat(body(response).get("code").asString()).isEqualTo("UNSUPPORTED_PARAMETERS");
    }
  }
}
