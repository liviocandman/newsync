package br.com.agregador.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import br.com.agregador.sources.CollectionActivity;
import br.com.agregador.sources.SourceRepository;
import br.com.agregador.sources.SourceService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.DetachedSocketFactory;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "app.collection.enabled=false")
@SuppressWarnings("deprecation")
class AutomaticCollectionIT {
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:7e32e9833a6fb1c92c32552794cb6ed569d51b445a54907d35fc112ef39684db");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    p.add("spring.datasource.username", POSTGRES::getUsername);
    p.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired SourceRepository sources;
  @Autowired SourceService service;
  @Autowired CollectionWriter writer;
  @Autowired FeedParser parser;
  @Autowired JdbcClient jdbc;
  @MockitoBean Clock clock;

  @Test
  void realHttpAutomaticCollectionCommitsConditionalResultsRetryAfterAndPersonalStates()
      throws Exception {
    var now = Instant.parse("2026-10-31T12:00:00Z");
    when(clock.instant()).thenReturn(now);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    var requests = new AtomicInteger();
    var status = new AtomicInteger(200);
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/feed",
        exchange -> {
          requests.incrementAndGet();
          try {
            if (status.get() == 304) {
              assertThat(exchange.getRequestHeaders().getFirst("If-None-Match"))
                  .isEqualTo("\"v1\"");
              exchange.sendResponseHeaders(304, -1);
            } else if (status.get() == 503) {
              exchange.getResponseHeaders().set("Retry-After", "43200");
              exchange.sendResponseHeaders(503, -1);
            } else {
              byte[] xml =
                  ("<rss version='2.0'><channel><title>F</title><link>https://example.org</link><description>F</description><item><guid>stable</guid><title>Real HTTP</title></item></channel></rss>")
                      .getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/rss+xml");
              exchange.getResponseHeaders().set("ETag", "\"v1\"");
              exchange.sendResponseHeaders(200, xml.length);
              exchange.getResponseBody().write(xml);
            }
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      UUID id = service.create(Map.of("feedUrl", "http://feed.example/feed")).id();
      assertThat(requests.get()).isZero();
      assertThat(sources.find(id).nextPollAt().toInstant()).isEqualTo(now);
      PublicFeedClient transport =
          new PublicFeedClient(
              host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")},
              (pinned, timeout) -> {
                DetachedSocketFactory sockets =
                    proxy ->
                        new Socket() {
                          @Override
                          public void connect(SocketAddress endpoint, int wait) throws IOException {
                            assertThat(
                                    PublicFeedUrl.isPublicAddress(
                                        ((InetSocketAddress) endpoint).getAddress()))
                                .isTrue();
                            super.connect(server.getAddress(), wait);
                          }
                        };
                var operator =
                    new DefaultHttpClientConnectionOperator(
                        sockets,
                        null,
                        pinned,
                        RegistryBuilder.<org.apache.hc.client5.http.ssl.TlsSocketStrategy>create()
                            .register(
                                "https",
                                org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy
                                    .createDefault())
                            .build());
                var manager =
                    new PoolingHttpClientConnectionManager(
                        operator,
                        PoolConcurrencyPolicy.STRICT,
                        PoolReusePolicy.LIFO,
                        TimeValue.ofSeconds(5),
                        null);
                manager.setDefaultConnectionConfig(
                    ConnectionConfig.custom()
                        .setConnectTimeout(timeout)
                        .setSocketTimeout(timeout)
                        .build());
                return manager;
              },
              Duration.ofSeconds(5),
              clock);
      var activity = new CollectionActivity();
      var dispatcher =
          new ManualFeedDispatcher(
              sources, service, activity, transport, parser, writer, clock, true);
      dispatcher.ready();
      dispatcher.tick();
      assertThat(requests.get()).isEqualTo(1);
      assertThat(sources.find(id).lastResult()).isEqualTo("success");
      assertThat(sources.find(id).lastAttemptAt().toInstant()).isEqualTo(now);
      assertThat(sources.find(id).lastSuccessAt().toInstant()).isEqualTo(now);
      assertThat(sources.find(id).manualRequestedAt()).isNull();
      jdbc.sql("UPDATE articles SET read_at=now(),saved_at=now()").update();
      var identity = jdbc.sql("SELECT id,read_at,saved_at FROM articles").query().listOfRows();
      dispatcher.tick();
      assertThat(requests.get()).isEqualTo(1);
      status.set(304);
      jdbc.sql("UPDATE sources SET next_poll_at=:at")
          .param("at", now.atOffset(ZoneOffset.UTC))
          .update();
      dispatcher.tick();
      assertThat(requests.get()).isEqualTo(2);
      assertThat(sources.find(id).lastResult()).isEqualTo("not_modified");
      assertThat(sources.find(id).etag()).isEqualTo("\"v1\"");
      status.set(503);
      jdbc.sql("UPDATE sources SET next_poll_at=:at")
          .param("at", now.atOffset(ZoneOffset.UTC))
          .update();
      dispatcher.tick();
      assertThat(requests.get()).isEqualTo(3);
      var failed = sources.find(id);
      assertThat(failed.lastResult()).isEqualTo("failed");
      assertThat(failed.consecutiveFailures()).isEqualTo(1);
      assertThat(failed.retryAfterAt().toInstant()).isEqualTo(now.plusSeconds(43200));
      assertThat(failed.nextPollAt()).isEqualTo(failed.retryAfterAt());
      System.out.println(
          "AUTOMATIC_SQL "
              + jdbc.sql(
                      "SELECT last_result,consecutive_failures,last_success_at,last_attempt_at,next_poll_at,retry_after_at,manual_requested_at FROM sources")
                  .query()
                  .listOfRows());
      dispatcher.tick();
      assertThat(requests.get()).isEqualTo(3);
      assertThat(jdbc.sql("SELECT id,read_at,saved_at FROM articles").query().listOfRows())
          .isEqualTo(identity);
    } finally {
      server.stop(0);
    }
  }
}
