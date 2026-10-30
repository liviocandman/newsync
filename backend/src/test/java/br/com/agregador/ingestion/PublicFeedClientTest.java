package br.com.agregador.ingestion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.DetachedSocketFactory;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("deprecation")
class PublicFeedClientTest {
  private static final String PUBLIC = "93.184.216.34";
  private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
  private HttpServer server;
  private final AtomicReference<Handler> handler = new AtomicReference<>();
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicInteger dns = new AtomicInteger();
  private final List<InetAddress> dialed = Collections.synchronizedList(new ArrayList<>());
  private final List<String> hosts = Collections.synchronizedList(new ArrayList<>());

  @FunctionalInterface
  interface Handler {
    void handle(HttpExchange exchange) throws IOException;
  }

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          hosts.add(exchange.getRequestHeaders().getFirst("Host"));
          try {
            handler.get().handle(exchange);
          } finally {
            exchange.close();
          }
        });
    handler.set(
        exchange ->
            send(exchange, 200, "application/rss+xml", "<rss/>".getBytes(StandardCharsets.UTF_8)));
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private PublicFeedClient client(PublicFeedClient.Resolver resolver, Duration budget) {
    return new PublicFeedClient(
        host -> {
          dns.incrementAndGet();
          return resolver.resolve(host);
        },
        this::manager,
        budget,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private PublicFeedClient client() {
    return client(host -> new InetAddress[] {InetAddress.getByName(PUBLIC)}, Duration.ofSeconds(3));
  }

  private PoolingHttpClientConnectionManager manager(DnsResolver pinned, Timeout timeout) {
    return manager(pinned, timeout, server.getAddress());
  }

  private PoolingHttpClientConnectionManager manager(
      DnsResolver pinned, Timeout timeout, InetSocketAddress controlledDestination) {
    return manager(
        pinned, timeout, controlledDestination, DefaultClientTlsStrategy.createDefault());
  }

  private PoolingHttpClientConnectionManager manager(
      DnsResolver pinned,
      Timeout timeout,
      InetSocketAddress controlledDestination,
      TlsSocketStrategy tlsStrategy) {
    DetachedSocketFactory sockets =
        proxy ->
            new Socket() {
              @Override
              public void connect(SocketAddress endpoint, int wait) throws IOException {
                InetSocketAddress remote = (InetSocketAddress) endpoint;
                // Observe the actual Socket.connect target selected by Apache, then route only in
                // tests.
                assertTrue(PublicFeedUrl.isPublicAddress(remote.getAddress()));
                dialed.add(remote.getAddress());
                super.connect(controlledDestination, wait);
              }
            };
    var tls = RegistryBuilder.<TlsSocketStrategy>create().register("https", tlsStrategy).build();
    var operator = new DefaultHttpClientConnectionOperator(sockets, null, pinned, tls);
    var manager =
        new PoolingHttpClientConnectionManager(
            operator,
            PoolConcurrencyPolicy.STRICT,
            PoolReusePolicy.LIFO,
            TimeValue.ofSeconds(5),
            null);
    manager.setDefaultConnectionConfig(
        ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build());
    return manager;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "application/rss+xml",
        "application/atom+xml",
        "application/xml",
        "text/xml; charset=UTF-8"
      })
  void receivesXmlFromValidatedPinnedAddressWithOriginalHost(String type) {
    handler.set(exchange -> send(exchange, 200, type, "<rss/>".getBytes(StandardCharsets.UTF_8)));
    var response = client().fetch("http://feed.example/feed?signature=a%2Fb", null, null);
    assertEquals(200, response.status());
    assertEquals("<rss/>", new String(response.body(), StandardCharsets.UTF_8));
    assertEquals(1, dns.get());
    assertEquals(List.of(PUBLIC), dialed.stream().map(InetAddress::getHostAddress).toList());
    assertEquals(List.of("feed.example"), hosts);
  }

  @Test
  void rebindingCannotTriggerIndependentDnsLookupBeforeConnection() {
    var client =
        client(
            host ->
                new InetAddress[] {InetAddress.getByName(dns.get() == 1 ? PUBLIC : "127.0.0.1")},
            Duration.ofSeconds(3));
    assertEquals(200, client.fetch("http://feed.example/", null, null).status());
    assertEquals(1, dns.get());
    assertCode("DESTINATION_REJECTED", () -> client.fetch("http://feed.example/", null, null));
    assertEquals(1, requests.get());
    assertEquals(1, dialed.size());
  }

  @Test
  void mixedResolutionIsRejectedBeforeAnyConnection() {
    var client =
        client(
            host ->
                new InetAddress[] {InetAddress.getByName(PUBLIC), InetAddress.getLoopbackAddress()},
            Duration.ofSeconds(3));
    assertCode("DESTINATION_REJECTED", () -> client.fetch("http://feed.example/", null, null));
    assertEquals(0, requests.get());
    assertTrue(dialed.isEmpty());
  }

  @Test
  void redirectsAreRevalidatedAndNeverReachPrivateDestination() {
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().set("Location", "http://private.example/feed");
          send(exchange, 302, "text/plain", new byte[0]);
        });
    var client =
        client(
            host ->
                new InetAddress[] {
                  InetAddress.getByName(host.equals("private.example") ? "10.1.2.3" : PUBLIC)
                },
            Duration.ofSeconds(3));
    assertCode("DESTINATION_REJECTED", () -> client.fetch("http://feed.example/", null, null));
    assertEquals(1, requests.get());
    assertEquals(1, dialed.size());
    assertEquals(2, dns.get());
  }

  @Test
  void literalPrivateRedirectIsRejectedBeforeResolution() {
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().set("Location", "http://169.254.169.254/");
          send(exchange, 302, "text/plain", new byte[0]);
        });
    assertCode("DESTINATION_REJECTED", () -> client().fetch("http://feed.example/", null, null));
    assertEquals(1, requests.get());
    assertEquals(1, dns.get());
  }

  @Test
  void allowsThreeRedirectsAndRejectsFourth() {
    handler.set(
        exchange -> {
          int step = Integer.parseInt(exchange.getRequestURI().getPath().substring(1));
          if (step < 3) {
            exchange.getResponseHeaders().set("Location", "/" + (step + 1));
            send(exchange, 302, "text/plain", new byte[0]);
          } else send(exchange, 200, "application/xml", "<rss/>".getBytes(StandardCharsets.UTF_8));
        });
    assertEquals(200, client().fetch("http://feed.example/0", null, null).status());
    assertEquals(4, requests.get());
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/again");
          send(exchange, 302, "text/plain", new byte[0]);
        });
    assertCode("REDIRECT_LIMIT", () -> client().fetch("http://feed.example/again", null, null));
    assertEquals(8, requests.get());
  }

  @Test
  void keepsConditionalHeadersOnlyForSameOriginAndNeverUsesCookiesOrAuthentication() {
    handler.set(
        exchange -> {
          assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
          assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
          if (exchange.getRequestURI().getPath().equals("/first")) {
            assertEquals("\"previous\"", exchange.getRequestHeaders().getFirst("If-None-Match"));
            exchange.getResponseHeaders().set("Set-Cookie", "private=value");
            exchange.getResponseHeaders().set("Location", "http://another.example/final");
            send(exchange, 302, "text/plain", new byte[0]);
          } else {
            assertNull(exchange.getRequestHeaders().getFirst("If-None-Match"));
            exchange.getResponseHeaders().set("ETag", "\"next\"");
            send(exchange, 200, "application/xml", "<rss/>".getBytes(StandardCharsets.UTF_8));
          }
        });
    assertEquals(
        "\"next\"", client().fetch("http://feed.example/first", "\"previous\"", null).etag());
    assertEquals(List.of("feed.example", "another.example"), hosts);
  }

  @Test
  void notModifiedRequiresAnExistingValidator() {
    handler.set(exchange -> send(exchange, 304, "application/xml", null));
    assertCode("UNEXPECTED_NOT_MODIFIED", () -> client().fetch("http://feed.example/", null, null));
    assertEquals(304, client().fetch("http://feed.example/", "\"known\"", null).status());
    assertNull(PublicFeedClient.validator("value\r\nInjected: header"));
    assertNull(PublicFeedClient.validator("x".repeat(513)));
    assertCode(
        "UNEXPECTED_NOT_MODIFIED",
        () -> client().fetch("http://feed.example/", "invalid-etag", "invalid-date"));
  }

  @Test
  void crossOriginNotModifiedWithoutSentValidatorsIsRejected() {
    handler.set(
        exchange -> {
          if (exchange.getRequestURI().getPath().equals("/initial")) {
            exchange.getResponseHeaders().set("Location", "http://other.example/final");
            send(exchange, 302, "text/plain", new byte[0]);
          } else {
            assertNull(exchange.getRequestHeaders().getFirst("If-None-Match"));
            send(exchange, 304, "application/xml", null);
          }
        });
    assertCode(
        "UNEXPECTED_NOT_MODIFIED",
        () -> client().fetch("http://feed.example/initial", "\"known\"", null));
    assertEquals(2, requests.get());
  }

  @Test
  void keepsHttpCharsetWithExactBytesForTheXmlParser() {
    byte[] body =
        "<rss version=\"2.0\"><channel><title>Café</title><link>https://example.com/</link><description>Ação</description><item><title>Notícia</title><guid>one</guid></item></channel></rss>"
            .getBytes(StandardCharsets.ISO_8859_1);
    handler.set(exchange -> send(exchange, 200, "text/xml; charset=ISO-8859-1", body));
    var response = client().fetch("http://feed.example/", null, null);
    assertArrayEquals(body, response.body());
    assertEquals("text/xml; charset=ISO-8859-1", response.contentType());
  }

  @Test
  void redirectRetryAfterPreventsTheNextConnection() {
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/next");
          exchange.getResponseHeaders().set("Retry-After", "120");
          send(exchange, 302, "text/plain", new byte[0]);
        });
    var error =
        assertThrows(
            FeedTransportException.class, () -> client().fetch("http://feed.example/", null, null));
    assertEquals("HTTP_REDIRECT_RETRY", error.code());
    assertEquals(NOW.plusSeconds(120), error.retryAfter());
    assertEquals(1, requests.get());
  }

  @Test
  void retryAfterIsLimitedToSafeDiagnosticAndSupportsDeltaOrDate() {
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().set("Retry-After", "120");
          send(exchange, 429, "text/html", "private-secret-error".getBytes(StandardCharsets.UTF_8));
        });
    var error =
        assertThrows(
            FeedTransportException.class,
            () -> client().fetch("http://feed.example/?secret=hidden", null, null));
    assertEquals("HTTP_429", error.code());
    assertEquals(NOW.plusSeconds(120), error.retryAfter());
    assertFalse(error.getMessage().contains("secret"));
    assertFalse(error.getMessage().contains("feed.example"));
    assertEquals(
        NOW.plusSeconds(60), PublicFeedClient.retryAfter("Thu, 29 Oct 2026 12:01:00 GMT", NOW));
    assertNull(PublicFeedClient.retryAfter("Thu, 29 Oct 2026 11:00:00 GMT", NOW));
    assertNull(PublicFeedClient.retryAfter("not-a-date", NOW));
  }

  @Test
  void enforcesDecompressedLimitForGzipAndChunkedBodies() throws Exception {
    byte[] oversized = new byte[PublicFeedClient.MAX_BYTES + 1];
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
      gzip.write(oversized);
    }
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().set("Content-Encoding", "gzip");
          send(exchange, 200, "application/xml", compressed.toByteArray());
        });
    assertCode("FEED_TOO_LARGE", () -> client().fetch("http://feed.example/", null, null));
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/xml");
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().write(oversized);
        });
    assertCode("FEED_TOO_LARGE", () -> client().fetch("http://feed.example/", null, null));
  }

  @Test
  void rejectsHtmlAndUnsupportedEncoding() {
    handler.set(
        exchange -> send(exchange, 200, "text/html", "<html/>".getBytes(StandardCharsets.UTF_8)));
    assertCode("UNSUPPORTED_MEDIA_TYPE", () -> client().fetch("http://feed.example/", null, null));
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().set("Content-Encoding", "br");
          send(exchange, 200, "application/xml", new byte[1]);
        });
    assertCode("UNSUPPORTED_ENCODING", () -> client().fetch("http://feed.example/", null, null));
  }

  @Test
  void retryAfterSurvivesInvalidMediaAndDecompressionFailure() {
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().set("Retry-After", "120");
          send(exchange, 200, "text/html", "<html/>".getBytes(StandardCharsets.UTF_8));
        });
    var mediaError =
        assertThrows(
            FeedTransportException.class, () -> client().fetch("http://feed.example/", null, null));
    assertEquals("UNSUPPORTED_MEDIA_TYPE", mediaError.code());
    assertEquals(NOW.plusSeconds(120), mediaError.retryAfter());
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().set("Retry-After", "120");
          exchange.getResponseHeaders().set("Content-Encoding", "gzip");
          send(exchange, 200, "application/xml", new byte[5]);
        });
    var compressionError =
        assertThrows(
            FeedTransportException.class, () -> client().fetch("http://feed.example/", null, null));
    assertEquals("DOWNLOAD_FAILED", compressionError.code());
    assertEquals(NOW.plusSeconds(120), compressionError.retryAfter());
  }

  @Test
  void interruptedBodyIsNeverReturnedAsSuccessful() {
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/xml");
          exchange.sendResponseHeaders(200, 100);
          exchange.getResponseBody().write("<rss>".getBytes(StandardCharsets.UTF_8));
        });
    assertCode("DOWNLOAD_FAILED", () -> client().fetch("http://feed.example/", null, null));
  }

  @Test
  void httpsRejectsAnUntrustedCertificateBeforeAnyHttpRequest(@TempDir Path directory)
      throws Exception {
    Path keyStoreFile = directory.resolve("test-server.p12");
    Process keytool =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "fixture",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "1",
                "-dname",
                "CN=feed.example",
                "-ext",
                "SAN=dns:feed.example",
                "-storetype",
                "PKCS12",
                "-keystore",
                keyStoreFile.toString(),
                "-storepass",
                "test-only-password",
                "-noprompt")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertEquals(0, keytool.waitFor());
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(keyStoreFile)) {
      keyStore.load(input, "test-only-password".toCharArray());
    }
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(keyStore, "test-only-password".toCharArray());
    SSLContext serverTls = SSLContext.getInstance("TLS");
    serverTls.init(keys.getKeyManagers(), null, null);
    HttpsServer tls =
        HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    tls.setHttpsConfigurator(new HttpsConfigurator(serverTls));
    AtomicInteger reached = new AtomicInteger();
    tls.createContext(
        "/",
        exchange -> {
          reached.incrementAndGet();
          exchange.close();
        });
    tls.start();
    try {
      var client =
          new PublicFeedClient(
              host -> new InetAddress[] {InetAddress.getByName(PUBLIC)},
              (pinned, timeout) -> manager(pinned, timeout, tls.getAddress()),
              Duration.ofSeconds(3),
              Clock.fixed(NOW, ZoneOffset.UTC));
      assertCode("DOWNLOAD_FAILED", () -> client.fetch("https://feed.example/feed", null, null));
      assertEquals(0, reached.get());
    } finally {
      tls.stop(0);
    }
  }

  @Test
  void totalBudgetIncludesBlockingDnsWithoutAnyLateConnection() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    var client =
        client(
            host -> {
              try {
                release.await();
              } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
              }
              return new InetAddress[] {InetAddress.getByName(PUBLIC)};
            },
            Duration.ofMillis(120));
    long started = System.nanoTime();
    assertCode("DOWNLOAD_TIMEOUT", () -> client.fetch("http://feed.example/", null, null));
    release.countDown();
    assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1500);
    assertTrue(dialed.isEmpty());
  }

  @Test
  void totalBudgetIncludesSlowStreamingAndAllRedirects() {
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/xml");
          exchange.sendResponseHeaders(200, 0);
          try {
            for (int i = 0; i < 20; i++) {
              exchange.getResponseBody().write('x');
              exchange.getResponseBody().flush();
              Thread.sleep(40);
            }
          } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
          }
        });
    var client =
        client(host -> new InetAddress[] {InetAddress.getByName(PUBLIC)}, Duration.ofMillis(250));
    long started = System.nanoTime();
    assertCode("DOWNLOAD_TIMEOUT", () -> client.fetch("http://feed.example/", null, null));
    assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1500);
  }

  private static void send(HttpExchange exchange, int status, String type, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(status, body == null || body.length == 0 ? -1 : body.length);
    if (body != null && body.length != 0) exchange.getResponseBody().write(body);
  }

  private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
    assertEquals(code, assertThrows(FeedTransportException.class, action).code());
  }
}
