package br.com.agregador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import br.com.agregador.ingestion.CollectionWriter;
import br.com.agregador.ingestion.FeedParser;
import br.com.agregador.ingestion.FeedTransportException;
import br.com.agregador.ingestion.ManualFeedDispatcher;
import br.com.agregador.ingestion.PublicFeedClient;
import br.com.agregador.sources.CollectionActivity;
import br.com.agregador.sources.SourceRepository;
import br.com.agregador.sources.SourceService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.collection.initial-delay-ms=3600000", "app.collection.delay-ms=3600000"})
@org.junit.jupiter.api.extension.ExtendWith(
    org.springframework.boot.test.system.OutputCaptureExtension.class)
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
    "paused,false,false", "paused,true,false", "archived,false,false", "archived,true,false",
    "paused,false,true", "paused,true,true", "archived,false,true", "archived,true,true"
  })
  void staleDownloadCannotChangeReactivatedSourceOrConsumeNewRequest(
      String state, boolean failed, boolean automatic) throws Exception {
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
    if (!automatic) write("POST", "/sources/" + id + "/refresh", null);
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
  void dispatcherRetainsRequestAfterCommitFailureAndWaitsBeforeRetry(
      org.springframework.boot.test.system.CapturedOutput output) throws Exception {
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
      when(clock.instant()).thenReturn(time.plusSeconds(5));
      dispatcher.tick();
      verify(transport, times(2)).fetch(anyString(), isNull(), isNull());
      assertThat(output.getOut().split("COLLECTION_UNCONFIRMED", -1)).hasSize(2);
    } finally {
      jdbc.sql("DROP TRIGGER b03_fail_result ON sources").update();
      jdbc.sql("DROP FUNCTION b03_fail_result()").update();
    }
    when(clock.instant()).thenReturn(time.plusSeconds(10));
    dispatcher.tick();
    assertThat(output.getOut()).contains("COLLECTION_RECOVERED");
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

  @Test
  void automaticSelectionSkipsIneligibleSourcesAndProcessesOnePerTick() throws Exception {
    String id = create();
    verifyNoInteractions(transport);
    UUID second =
        sources.create(
            "https://second.example/feed", "Segunda", time.plusSeconds(1).atOffset(ZoneOffset.UTC));
    UUID future =
        sources.create(
            "https://future.example/feed",
            "Futura",
            time.plusSeconds(3600).atOffset(ZoneOffset.UTC));
    for (String state : List.of("paused", "archived")) {
      UUID other =
          sources.create(
              "https://" + state + ".example/feed",
              state,
              time.minusSeconds(60).atOffset(ZoneOffset.UTC));
      jdbc.sql("UPDATE sources SET status=:state WHERE id=:id")
          .param("state", state)
          .param("id", other)
          .update();
    }
    UUID blocked =
        sources.create(
            "https://blocked.example/feed",
            "Bloqueada",
            time.minusSeconds(120).atOffset(ZoneOffset.UTC));
    jdbc.sql("UPDATE sources SET retry_after_at=:at,manual_requested_at=next_poll_at WHERE id=:id")
        .param("at", time.plusSeconds(3600).atOffset(ZoneOffset.UTC))
        .param("id", blocked)
        .update();
    when(clock.instant()).thenReturn(time.plusSeconds(2));
    when(transport.fetch(anyString(), isNull(), isNull()))
        .thenReturn(new PublicFeedClient.Response(200, xml("Automática"), "v1", null, null));
    dispatcher.tick();
    verify(transport, times(1)).fetch(anyString(), isNull(), isNull());
    assertThat(sources.find(UUID.fromString(id)).lastResult()).isEqualTo("success");
    assertThat(sources.find(second).lastResult()).isEqualTo("never");
    dispatcher.tick();
    verify(transport, times(2)).fetch(anyString(), isNull(), isNull());
    assertThat(sources.find(second).lastResult()).isEqualTo("success");
    dispatcher.tick();
    verifyNoMoreInteractions(transport);
    assertThat(sources.find(future).lastAttemptAt()).isNull();
    assertThat(sources.find(blocked).lastAttemptAt()).isNull();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM sources WHERE status<>'active' AND last_attempt_at IS NOT NULL")
                .query(Long.class)
                .single())
        .isZero();
    write("PATCH", "/sources/" + id, "{\"state\":\"paused\"}");
    write("PATCH", "/sources/" + id, "{\"state\":\"active\"}");
    when(transport.fetch(anyString(), eq("v1"), isNull()))
        .thenReturn(new PublicFeedClient.Response(304, new byte[0], null, null, null));
    dispatcher.tick();
    assertThat(sources.find(UUID.fromString(id)).lastResult()).isEqualTo("not_modified");
    assertThat(
            body(request("GET", "/sources/" + id, null, null, false))
                .get("collectionEnabled")
                .asBoolean())
        .isTrue();
  }

  @Test
  void automaticCaptureRevalidatesManualMarkAndDisabledDispatcherDoesNothing() throws Exception {
    UUID id = UUID.fromString(create());
    var captured = sources.find(id);
    service.refresh(id);
    assertThat(service.begin(captured)).isFalse();
    var disabled =
        new ManualFeedDispatcher(
            sources, service, activity, transport, parser, writer, clock, false);
    disabled.ready();
    disabled.tick();
    verifyNoInteractions(transport);
    assertThat(sources.find(id).lastAttemptAt()).isNull();
  }

  @Test
  void manualDuringAutomaticCoalescesAndHasPriorityBetweenSequentialAttempts() throws Exception {
    UUID a = UUID.fromString(create());
    UUID c =
        sources.create("https://c.example/feed", "C", time.plusSeconds(1).atOffset(ZoneOffset.UTC));
    UUID b =
        sources.create(
            "https://b.example/feed", "B", time.plusSeconds(3600).atOffset(ZoneOffset.UTC));
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(transport.fetch(eq(sources.find(a).feedUrl()), isNull(), isNull()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              started.countDown();
              if (!release.await(10, TimeUnit.SECONDS)) throw new TimeoutException();
              return new PublicFeedClient.Response(200, xml("A"), null, null, null);
            });
    when(transport.fetch(eq("https://b.example/feed"), isNull(), isNull()))
        .thenReturn(new PublicFeedClient.Response(200, xml("B"), null, null, null));
    when(transport.fetch(eq("https://c.example/feed"), isNull(), isNull()))
        .thenReturn(new PublicFeedClient.Response(200, xml("C"), null, null, null));
    try (var executor = Executors.newSingleThreadExecutor()) {
      var work = executor.submit(dispatcher::tick);
      try {
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(service.refresh(a).status()).isEqualTo("collecting");
        assertThat(sources.find(a).manualRequestedAt()).isNull();
        assertThat(service.detail(a).collecting()).isTrue();
        when(clock.instant()).thenReturn(time.plusSeconds(2));
        assertThat(service.refresh(b).status()).isEqualTo("scheduled");
        dispatcher.tick();
        verify(transport, times(1)).fetch(anyString(), isNull(), isNull());
      } finally {
        release.countDown();
      }
      work.get(15, TimeUnit.SECONDS);
    }
    assertThat(sources.find(a).lastResult()).isEqualTo("success");
    dispatcher.tick();
    assertThat(sources.find(b).lastResult()).isEqualTo("success");
    assertThat(sources.find(c).lastResult()).isEqualTo("never");
    dispatcher.tick();
    assertThat(sources.find(c).lastResult()).isEqualTo("success");
    assertThat(service.detail(a).collecting()).isFalse();
  }

  @Test
  void persistedBackoffRetryAfterAndRestartRespectCompletionAndPreservePersonalStates()
      throws Exception {
    UUID id = UUID.fromString(create());
    when(transport.fetch(anyString(), isNull(), isNull()))
        .thenReturn(new PublicFeedClient.Response(200, xml("Inicial"), null, null, null));
    dispatcher.tick();
    jdbc.sql("UPDATE articles SET read_at=:at,saved_at=:at")
        .param("at", time.atOffset(ZoneOffset.UTC))
        .update();
    var identity = jdbc.sql("SELECT id,read_at,saved_at FROM articles").query().listOfRows();
    var lastSuccess = sources.find(id).lastSuccessAt();
    when(clock.instant()).thenReturn(time.plusSeconds(1801));
    when(transport.fetch(anyString(), isNull(), isNull()))
        .thenThrow(new FeedTransportException("HTTP_503", "Indisponível", time.plusSeconds(86400)));
    dispatcher.tick();
    var failed = sources.find(id);
    assertThat(failed.consecutiveFailures()).isEqualTo(1);
    assertThat(failed.nextPollAt().toInstant()).isEqualTo(time.plusSeconds(86400));
    assertThat(failed.lastSuccessAt()).isEqualTo(lastSuccess);
    var fresh =
        new ManualFeedDispatcher(
            sources, service, activity, transport, parser, writer, clock, true);
    fresh.ready();
    fresh.tick();
    verify(transport, times(2)).fetch(anyString(), isNull(), isNull());
    when(clock.instant()).thenReturn(time.plusSeconds(86401));
    doReturn(new PublicFeedClient.Response(200, xml("Retomada"), null, null, null))
        .when(transport)
        .fetch(anyString(), isNull(), isNull());
    fresh.tick();
    assertThat(sources.find(id).consecutiveFailures()).isZero();
    fresh.tick();
    verify(transport, times(3)).fetch(anyString(), isNull(), isNull());
    assertThat(jdbc.sql("SELECT id,read_at,saved_at FROM articles").query().listOfRows())
        .isEqualTo(identity);
  }

  @Test
  void failedSourceBacksOffWhileAnotherProgressesAndManualCanAdvanceLocalDelay() throws Exception {
    UUID a = UUID.fromString(create());
    UUID b = sources.create("https://b.example/feed", "B", time.atOffset(ZoneOffset.UTC));
    service.refresh(a);
    when(transport.fetch(eq(sources.find(a).feedUrl()), isNull(), isNull()))
        .thenThrow(new FeedTransportException("HTTP_503", "Indisponível", null));
    when(transport.fetch(eq("https://b.example/feed"), isNull(), isNull()))
        .thenReturn(new PublicFeedClient.Response(200, xml("B"), null, null, null));
    dispatcher.tick();
    var row = sources.find(a);
    assertThat(row.consecutiveFailures()).isEqualTo(1);
    assertThat(row.nextPollAt().toInstant())
        .isBetween(time.plusSeconds(300), time.plusSeconds(330));
    dispatcher.tick();
    assertThat(sources.find(b).lastResult()).isEqualTo("success");
    when(clock.instant()).thenReturn(time.plusSeconds(61));
    service.refresh(a);
    dispatcher.tick();
    assertThat(sources.find(a).consecutiveFailures()).isEqualTo(2);
    assertThat(sources.find(a).nextPollAt().toInstant())
        .isBetween(time.plusSeconds(961), time.plusSeconds(1051));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "not_modified", "partial"})
  void confirmedResultsResetFailuresUsingCompletionTime(String result) throws Exception {
    UUID id = UUID.fromString(create());
    jdbc.sql(
            "UPDATE sources SET consecutive_failures=6,etag='previous',last_success_at=:at WHERE id=:id")
        .param("at", time.minusSeconds(100).atOffset(ZoneOffset.UTC))
        .param("id", id)
        .update();
    when(transport.fetch(anyString(), eq("previous"), isNull()))
        .thenAnswer(
            call -> {
              when(clock.instant()).thenReturn(time.plusSeconds(12));
              if (result.equals("not_modified"))
                return new PublicFeedClient.Response(304, new byte[0], null, null, null);
              String feed = new String(xml("Resultado"), StandardCharsets.UTF_8);
              if (result.equals("partial"))
                feed = feed.replace("</channel>", "<item><guid>invalid</guid></item></channel>");
              return new PublicFeedClient.Response(
                  200, feed.getBytes(StandardCharsets.UTF_8), "new", null, null);
            });
    dispatcher.tick();
    var row = sources.find(id);
    assertThat(row.lastResult()).isEqualTo(result);
    assertThat(row.consecutiveFailures()).isZero();
    assertThat(row.nextPollAt().toInstant()).isEqualTo(time.plusSeconds(1812));
    assertThat(row.lastSuccessAt().toInstant())
        .isEqualTo(result.equals("partial") ? time.minusSeconds(100) : time.plusSeconds(12));
    assertThat(row.etag()).isEqualTo(result.equals("success") ? "new" : "previous");
  }

  @Test
  void automaticRestartBeforeConfirmationRepeatsIdempotentlyAndHonorsCommittedSchedule()
      throws Exception {
    UUID id = UUID.fromString(create());
    when(transport.fetch(anyString(), isNull(), isNull()))
        .thenReturn(new PublicFeedClient.Response(200, xml("Mesmo artigo"), null, null, null));
    dispatcher.tick();
    jdbc.sql("UPDATE articles SET read_at=:at,saved_at=:at")
        .param("at", time.atOffset(ZoneOffset.UTC))
        .update();
    var identity = jdbc.sql("SELECT id,read_at,saved_at FROM articles").query().listOfRows();
    when(clock.instant()).thenReturn(time.plusSeconds(1801));
    var captured =
        sources.nextEligible(time.plusSeconds(1801).atOffset(ZoneOffset.UTC)).orElseThrow();
    assertThat(service.begin(captured)).isTrue();
    // Simulate process loss after begin and before confirmation; no durable collecting state
    // exists.
    var freshActivity = new CollectionActivity();
    var fresh =
        new ManualFeedDispatcher(
            sources, service, freshActivity, transport, parser, writer, clock, true);
    fresh.ready();
    fresh.tick();
    assertThat(jdbc.sql("SELECT id,read_at,saved_at FROM articles").query().listOfRows())
        .isEqualTo(identity);
    assertThat(sources.find(id).nextPollAt().toInstant()).isEqualTo(time.plusSeconds(3601));
    fresh.tick();
    verify(transport, times(2)).fetch(anyString(), isNull(), isNull());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
  void failureStepsPersistWithBoundedJitter(int previousFailures) throws Exception {
    UUID id = UUID.fromString(create());
    jdbc.sql("UPDATE sources SET consecutive_failures=:failures WHERE id=:id")
        .param("failures", previousFailures)
        .param("id", id)
        .update();
    when(transport.fetch(anyString(), isNull(), isNull()))
        .thenThrow(new FeedTransportException("HTTP_503", "Indisponível", null));
    dispatcher.tick();
    long[] seconds = {300, 900, 1800, 3600, 7200, 14400, 21600, 21600};
    var row = sources.find(id);
    assertThat(row.consecutiveFailures()).isEqualTo(previousFailures + 1);
    System.out.println(
        "BACKOFF_SQL "
            + jdbc.sql(
                    "SELECT consecutive_failures,next_poll_at,last_attempt_at FROM sources WHERE id=:id")
                .param("id", id)
                .query()
                .listOfRows());
    assertThat(row.nextPollAt().toInstant())
        .isBetween(
            time.plusSeconds(seconds[previousFailures]),
            time.plusSeconds(Math.min(21600, seconds[previousFailures] * 11 / 10)));
  }

  @Test
  void eligibleManualsUseFifoAndIdBeforeMostOverdueAutomatic() throws Exception {
    UUID a = UUID.fromString(create());
    UUID b =
        sources.create(
            "https://b.example/feed", "B", time.minusSeconds(1).atOffset(ZoneOffset.UTC));
    UUID c =
        sources.create(
            "https://c.example/feed", "C", time.minusSeconds(3600).atOffset(ZoneOffset.UTC));
    sources.request(a, time.minusSeconds(10).atOffset(ZoneOffset.UTC));
    sources.request(b, time.minusSeconds(20).atOffset(ZoneOffset.UTC));
    var now = time.atOffset(ZoneOffset.UTC);
    assertThat(sources.nextEligible(now).orElseThrow().id()).isEqualTo(b);
    sources.request(a, time.minusSeconds(20).atOffset(ZoneOffset.UTC));
    UUID first = a.toString().compareTo(b.toString()) < 0 ? a : b;
    assertThat(sources.nextEligible(now).orElseThrow().id()).isEqualTo(first);
    when(transport.fetch(anyString(), isNull(), isNull()))
        .thenReturn(new PublicFeedClient.Response(200, xml("FIFO"), null, null, null));
    dispatcher.tick();
    dispatcher.tick();
    assertThat(sources.find(c).lastAttemptAt()).isNull();
    dispatcher.tick();
    assertThat(sources.find(c).lastResult()).isEqualTo("success");
  }
}
