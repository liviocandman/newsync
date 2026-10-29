package br.com.agregador;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import br.com.agregador.ingestion.*;
import br.com.agregador.sources.*;
import com.zaxxer.hikari.HikariDataSource;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.collection.initial-delay-ms=3600000",
      "spring.datasource.hikari.maximum-pool-size=2",
      "spring.datasource.hikari.minimum-idle=0"
    })
class CollectionOperationalIT {
  // A dedicated container: stop/start never affects the shared test DB or a personal installation.
  static final int DATABASE_PORT = databasePort();

  private static int databasePort() {
    try (var socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    } catch (java.io.IOException error) {
      throw new IllegalStateException(error);
    }
  }

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              "postgres@sha256:7e32e9833a6fb1c92c32552794cb6ed569d51b445a54907d35fc112ef39684db")
          .withCreateContainerCmdModifier(
              cmd ->
                  cmd.getHostConfig()
                      .withPortBindings(
                          new com.github.dockerjava.api.model.PortBinding(
                              com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort(
                                  "127.0.0.1", DATABASE_PORT),
                              new com.github.dockerjava.api.model.ExposedPort(5432))));

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    p.add("spring.datasource.username", POSTGRES::getUsername);
    p.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired HikariDataSource pool;
  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired SourceService service;
  @Autowired SourceRepository sources;
  @Autowired ManualFeedDispatcher dispatcher;
  @MockitoBean PublicFeedClient transport;
  @LocalServerPort int port;

  @BeforeEach
  void clean() {
    jdbc.sql("TRUNCATE articles,sources CASCADE").update();
    reset(transport);
  }

  Connection external() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(22))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  void elapsed(String code, long start, long min, long max) {
    long ms = (System.nanoTime() - start) / 1_000_000;
    System.out.println(code + " elapsedMs=" + ms + " deadlineMs=" + max);
    assertThat(ms).isBetween(min, max);
  }

  @Test
  void exhaustedPoolReturnsSafe503AndRecoversAfterRelease() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          try (var first = pool.getConnection();
              var second = pool.getConnection()) {
            long start = System.nanoTime();
            var response = get("/api/v1/sources");
            elapsed("POOL_ACQUISITION", start, 2500, 9000);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body())
                .contains("DATABASE_UNAVAILABLE")
                .doesNotContain("SQLException", "HikariPool", "jdbc:");
          }
          assertThat(get("/api/v1/sources").statusCode()).isEqualTo(200);
          assertThat(get("/health/ready").statusCode()).isEqualTo(200);
        });
  }

  @Test
  void sourceAndArticleLocksRollbackAndPoolRemainsReusable() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(25),
        () -> {
          UUID id = sources.create("https://example.org/feed", "Lock", OffsetDateTime.now());
          when(transport.fetch(anyString(), isNull(), isNull()))
              .thenReturn(
                  new PublicFeedClient.Response(200, SourceHttpIT.xml("Lock"), null, null, null));
          try (var lock = external()) {
            lock.setAutoCommit(false);
            try (var statement =
                lock.prepareStatement("SELECT id FROM sources WHERE id=? FOR UPDATE")) {
              statement.setObject(1, id);
              statement.execute();
            }
            long start = System.nanoTime();
            assertThatThrownBy(() -> service.refresh(id))
                .satisfies(
                    error ->
                        assertThat(br.com.agregador.auth.DatabaseFailures.temporary(error))
                            .isTrue());
            elapsed("SOURCE_LOCK", start, 1500, 8000);
            assertThat(sources.find(id).manualRequestedAt()).isNull();
            lock.rollback();
          }
          service.refresh(id);
          dispatcher.tick();
          UUID article = jdbc.sql("SELECT id FROM articles").query(UUID.class).single();
          jdbc.sql("UPDATE articles SET read_at=now(),saved_at=now()").update();
          var identity = jdbc.sql("SELECT id,read_at,saved_at FROM articles").query().listOfRows();
          jdbc.sql("UPDATE sources SET next_poll_at=now()-interval '1 minute'").update();
          var before = sources.find(id);
          when(transport.fetch(anyString(), isNull(), isNull()))
              .thenReturn(
                  new PublicFeedClient.Response(
                      200, SourceHttpIT.xml("Changed"), "new", null, null));
          try (var lock = external()) {
            lock.setAutoCommit(false);
            try (var statement =
                lock.prepareStatement("SELECT id FROM articles WHERE id=? FOR UPDATE")) {
              statement.setObject(1, article);
              statement.execute();
            }
            long start = System.nanoTime();
            dispatcher.tick();
            elapsed("ARTICLE_LOCK", start, 1500, 8000);
            assertThat(sources.find(id).nextPollAt()).isEqualTo(before.nextPollAt());
            assertThat(sources.find(id).etag()).isNull();
            assertThat(jdbc.sql("SELECT title FROM articles").query(String.class).single())
                .isEqualTo("Lock");
            lock.rollback();
          }
          assertThat(jdbc.sql("SHOW lock_timeout").query(String.class).single()).isEqualTo("0");
          long deadline = System.nanoTime() + Duration.ofSeconds(9).toNanos();
          while (sources.find(id).etag() == null) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            dispatcher.tick();
            Thread.sleep(100);
          }
          assertThat(jdbc.sql("SELECT title FROM articles").query(String.class).single())
              .isEqualTo("Changed");
          assertThat(jdbc.sql("SELECT id,read_at,saved_at FROM articles").query().listOfRows())
              .isEqualTo(identity);
          assertThat(get("/health/ready").statusCode()).isEqualTo(200);
        });
  }

  @Test
  void slowQueriesTimeoutRollbackAndDoNotLeakLocalSettings() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(25),
        () -> {
          long start = System.nanoTime();
          assertThatThrownBy(() -> jdbc.sql("SELECT pg_sleep(12)").query().listOfRows())
              .satisfies(
                  error ->
                      assertThat(br.com.agregador.auth.DatabaseFailures.temporary(error)).isTrue());
          elapsed("READ_QUERY", start, 4000, 10000);
          start = System.nanoTime();
          var transaction = new TransactionTemplate(transactions);
          transaction.setTimeout(10);
          assertThatThrownBy(
                  () ->
                      transaction.execute(
                          status -> {
                            sources.writeLimits();
                            sources.create(
                                "https://rollback.example/feed", "Rollback", OffsetDateTime.now());
                            return jdbc.sql("SELECT pg_sleep(12)").query().listOfRows();
                          }))
              .satisfies(
                  error ->
                      assertThat(br.com.agregador.auth.DatabaseFailures.temporary(error)).isTrue());
          elapsed("WRITE_QUERY", start, 4000, 10000);
          assertThat(sources.list()).isEmpty();
          assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single())
              .isEqualTo("0");
          assertThat(jdbc.sql("SHOW lock_timeout").query(String.class).single()).isEqualTo("0");
          assertThat(get("/health/ready").statusCode()).isEqualTo(200);
        });
  }

  @Test
  void databaseLossAfterReadyReturns503AndSameJvmResumesDurableWork() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(55),
        () -> {
          UUID id =
              sources.create("https://recovery.example/feed", "Recovery", OffsetDateTime.now());
          service.refresh(id);
          when(transport.fetch(anyString(), isNull(), isNull()))
              .thenReturn(
                  new PublicFeedClient.Response(
                      200, SourceHttpIT.xml("Recovered"), null, null, null));
          dispatcher.tick();
          jdbc.sql("UPDATE articles SET read_at=now(),saved_at=now()").update();
          var identity = jdbc.sql("SELECT id,read_at,saved_at FROM articles").query().listOfRows();
          jdbc.sql("UPDATE sources SET last_manual_refresh_at=NULL").update();
          service.refresh(id);
          assertThat(get("/health/ready").statusCode()).isEqualTo(200);
          clearInvocations(transport);
          var writeClient =
              HttpClient.newBuilder()
                  .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                  .build();
          var csrfResponse =
              writeClient.send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          var csrf = new tools.jackson.databind.ObjectMapper().readTree(csrfResponse.body());
          var refreshRequest =
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/api/v1/sources/" + id + "/refresh"))
                  .timeout(Duration.ofSeconds(15))
                  .header("Origin", "http://127.0.0.1:5173")
                  .header(csrf.get("headerName").asString(), csrf.get("token").asString())
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build();
          var docker = POSTGRES.getDockerClient();
          docker.stopContainerCmd(POSTGRES.getContainerId()).withTimeout(1).exec();
          long start = System.nanoTime();
          try {
            assertThat(get("/health/ready").statusCode()).isEqualTo(503);
            var response = get("/api/v1/sources");
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body())
                .contains("DATABASE_UNAVAILABLE")
                .doesNotContain("jdbc:", "SQLException");
            var openingFailure =
                writeClient.send(refreshRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(openingFailure.statusCode()).isEqualTo(503);
            assertThat(openingFailure.body())
                .contains("DATABASE_UNAVAILABLE")
                .doesNotContain("jdbc:", "SQLException");
            assertThatThrownBy(() -> service.refresh(id))
                .satisfies(
                    error ->
                        assertThat(br.com.agregador.auth.DatabaseFailures.temporary(error))
                            .isTrue());
            dispatcher.tick();
            dispatcher.tick();
            verifyNoInteractions(transport);
          } finally {
            docker.startContainerCmd(POSTGRES.getContainerId()).exec();
            writeClient.close();
            elapsed("DATABASE_OUTAGE", start, 0, 22000);
          }
          long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
          while (get("/health/ready").statusCode() != 200) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(100);
          }
          // Wait with a bounded external deadline for the existing dispatcher's persisted retry
          // delay.
          while (sources.find(id).manualRequestedAt() != null) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            dispatcher.tick();
            Thread.sleep(100);
          }
          assertThat(sources.find(id).manualRequestedAt()).isNull();
          assertThat(sources.find(id).lastResult()).isEqualTo("success");
          assertThat(jdbc.sql("SELECT title FROM articles").query(String.class).single())
              .isEqualTo("Recovered");
          assertThat(jdbc.sql("SELECT id,read_at,saved_at FROM articles").query().listOfRows())
              .isEqualTo(identity);
          System.out.println(
              "DATABASE_RECOVERED ready=200 sameJvm=true personalStatesPreserved=true result="
                  + sources.find(id).lastResult());
          verify(transport, times(1)).fetch(anyString(), isNull(), isNull());
        });
  }
}
