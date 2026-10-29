package br.com.agregador.ingestion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/** The sole external client. Each hop owns a fresh, DNS-pinned connection manager. */
@Component
public class PublicFeedClient {
  static final int MAX_BYTES = 5 * 1024 * 1024;
  private static final Set<String> XML_TYPES =
      Set.of("application/rss+xml", "application/atom+xml", "application/xml", "text/xml");
  private final Resolver resolver;
  private final ManagerFactory managers;
  private final Duration budget;
  private final Clock clock;

  public record Response(
      int status,
      byte[] body,
      String etag,
      String lastModified,
      Instant retryAfter,
      String contentType) {
    public Response(int status, byte[] body, String etag, String lastModified, Instant retryAfter) {
      this(status, body, etag, lastModified, retryAfter, null);
    }

    public Response {
      body = body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }
  }

  @FunctionalInterface
  interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  @FunctionalInterface
  interface ManagerFactory {
    HttpClientConnectionManager create(DnsResolver pinned, Timeout timeout);
  }

  public PublicFeedClient() {
    this(
        InetAddress::getAllByName,
        (pinned, timeout) ->
            PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinned)
                .setDefaultConnectionConfig(
                    ConnectionConfig.custom()
                        .setConnectTimeout(timeout)
                        .setSocketTimeout(timeout)
                        .build())
                .build(),
        Duration.ofSeconds(15),
        Clock.systemUTC());
  }

  // Package-private injection is a test seam, never a property permitting private destinations.
  PublicFeedClient(Resolver resolver, ManagerFactory managers, Duration budget, Clock clock) {
    this.resolver = resolver;
    this.managers = managers;
    this.budget = budget;
    this.clock = clock;
  }

  public Response fetch(String feedUrl, String etag, String lastModified) {
    URI initial;
    try {
      initial = URI.create(PublicFeedUrl.normalize(feedUrl));
    } catch (IllegalArgumentException invalid) {
      throw failure("DESTINATION_REJECTED", "Destino externo recusado");
    }
    long deadline = System.nanoTime() + budget.toNanos();
    AtomicReference<HttpGet> currentRequest = new AtomicReference<>();
    AtomicReference<Instant> observedRetry = new AtomicReference<>();
    FutureTask<Response> task =
        new FutureTask<>(
            () -> download(initial, etag, lastModified, deadline, currentRequest, observedRetry));
    Thread.ofVirtual().name("feed-download").start(task);
    try {
      return task.get(remaining(deadline), TimeUnit.NANOSECONDS);
    } catch (TimeoutException error) {
      throw new FeedTransportException(
          "DOWNLOAD_TIMEOUT", "A coleta excedeu o tempo permitido", observedRetry.get());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new FeedTransportException(
          "DOWNLOAD_INTERRUPTED", "A coleta foi interrompida", observedRetry.get());
    } catch (ExecutionException error) {
      if (error.getCause() instanceof FeedTransportException known)
        throw new FeedTransportException(
            known.code(),
            known.getMessage(),
            known.retryAfter() == null ? observedRetry.get() : known.retryAfter());
      throw new FeedTransportException(
          "DOWNLOAD_FAILED", "Não foi possível receber o feed", observedRetry.get());
    } finally {
      HttpGet request = currentRequest.get();
      if (request != null) request.cancel();
      task.cancel(true);
    }
  }

  private Response download(
      URI initial,
      String etag,
      String lastModified,
      long deadline,
      AtomicReference<HttpGet> currentRequest,
      AtomicReference<Instant> observedRetry)
      throws IOException {
    URI target = initial;
    for (int redirects = 0; ; redirects++) {
      remaining(deadline);
      String hostname = target.getHost();
      if (hostname.startsWith("[")) hostname = hostname.substring(1, hostname.length() - 1);
      InetAddress[] candidates;
      try {
        candidates = resolver.resolve(hostname);
      } catch (UnknownHostException error) {
        throw failure("DNS_FAILED", "Não foi possível resolver o destino");
      }
      if (candidates == null || candidates.length == 0)
        throw failure("DNS_FAILED", "Não foi possível resolver o destino");
      for (InetAddress candidate : candidates) {
        if (!PublicFeedUrl.isPublicAddress(candidate))
          throw failure("DESTINATION_REJECTED", "Destino externo recusado");
      }
      Timeout timeout =
          Timeout.ofMilliseconds(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining(deadline))));
      DnsResolver pinned = pinned(hostname, candidates);
      HttpGet request = new HttpGet(target);
      currentRequest.set(request);
      request.setConfig(
          RequestConfig.custom()
              .setResponseTimeout(timeout)
              .setConnectionRequestTimeout(timeout)
              .setAuthenticationEnabled(false)
              .setRedirectsEnabled(false)
              .setHardCancellationEnabled(true)
              .build());
      request.setHeader(
          "Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml");
      request.setHeader("Accept-Encoding", "gzip");
      request.setHeader("User-Agent", "Caderno/0.1");
      request.setHeader("Connection", "close");
      // Validators belong to the registered resource; do not forward them to another origin.
      if (sameOrigin(initial, target)) {
        String safeEtag = entityTag(etag);
        String safeModified = modifiedSince(lastModified);
        if (safeEtag != null) request.setHeader("If-None-Match", safeEtag);
        if (safeModified != null) request.setHeader("If-Modified-Since", safeModified);
      }
      try (var client =
          HttpClients.custom()
              .setConnectionManager(managers.create(pinned, timeout))
              .disableRedirectHandling()
              .disableAutomaticRetries()
              .disableCookieManagement()
              .disableAuthCaching()
              .disableConnectionState()
              .disableContentCompression()
              .build()) {
        Hop hop =
            client.execute(
                request,
                response -> {
                  Instant retry = retryAfter(header(response, "Retry-After"), clock.instant());
                  if (retry != null) observedRetry.set(retry);
                  return receive(response, deadline);
                });
        if (hop.location() == null) {
          if (hop.response().status() == 304
              && request.getFirstHeader("If-None-Match") == null
              && request.getFirstHeader("If-Modified-Since") == null)
            throw failure(
                "UNEXPECTED_NOT_MODIFIED",
                "O servidor respondeu sem conteúdo antes da primeira coleta completa");
          return hop.response();
        }
        if (redirects >= 3)
          throw failure("REDIRECT_LIMIT", "O feed excedeu o limite de redirecionamentos");
        try {
          target = URI.create(PublicFeedUrl.normalize(target.resolve(hop.location()).toString()));
        } catch (IllegalArgumentException invalid) {
          throw failure("DESTINATION_REJECTED", "Redirecionamento externo recusado");
        }
      }
    }
  }

  private record Hop(Response response, String location) {}

  private Hop receive(ClassicHttpResponse response, long deadline) throws IOException {
    remaining(deadline);
    int status = response.getCode();
    Instant retry = retryAfter(header(response, "Retry-After"), clock.instant());
    if (Set.of(301, 302, 303, 307, 308).contains(status)) {
      if (retry != null && retry.isAfter(clock.instant()))
        throw new FeedTransportException(
            "HTTP_REDIRECT_RETRY", "O servidor pediu espera antes do redirecionamento", retry);
      String location = header(response, "Location");
      if (location == null || location.length() > 4096)
        throw failure("INVALID_REDIRECT", "Redirecionamento inválido");
      // Close unread bodies immediately; a redirect must not consume the body budget.
      if (response.getEntity() != null) response.getEntity().close();
      return new Hop(null, location);
    }
    if (status != 200 && status != 304) {
      if (response.getEntity() != null) response.getEntity().close();
      throw new FeedTransportException(
          "HTTP_" + status, "O servidor recusou ou não conseguiu entregar o feed", retry);
    }
    String etag = entityTag(header(response, "ETag"));
    String modified = modifiedSince(header(response, "Last-Modified"));
    if (status == 304) {
      if (response.getEntity() != null) response.getEntity().close();
      return new Hop(new Response(status, new byte[0], etag, modified, retry), null);
    }
    String type = header(response, "Content-Type");
    if (type == null || !XML_TYPES.contains(type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT)))
      throw failure("UNSUPPORTED_MEDIA_TYPE", "A resposta não tem um tipo de feed XML aceito");
    if (response.getEntity() == null)
      throw failure("EMPTY_RESPONSE", "O servidor não entregou um documento de feed");
    String encoding = header(response, "Content-Encoding");
    try (InputStream raw = response.getEntity().getContent();
        InputStream decoded =
            encoding == null || encoding.equalsIgnoreCase("identity")
                ? raw
                : encoding.equalsIgnoreCase("gzip")
                    ? new GZIPInputStream(raw)
                    : unsupportedEncoding()) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int length;
      while ((length = decoded.read(buffer)) != -1) {
        remaining(deadline);
        if (output.size() + length > MAX_BYTES)
          throw failure("FEED_TOO_LARGE", "O feed excede 5 MiB descomprimidos");
        output.write(buffer, 0, length);
      }
      return new Hop(new Response(status, output.toByteArray(), etag, modified, retry, type), null);
    }
  }

  private static InputStream unsupportedEncoding() {
    throw failure("UNSUPPORTED_ENCODING", "A compressão da resposta não é suportada");
  }

  private static DnsResolver pinned(String hostname, InetAddress[] candidates) {
    InetAddress[] validated = candidates.clone();
    return new DnsResolver() {
      @Override
      public InetAddress[] resolve(String host) throws UnknownHostException {
        if (!hostname.equalsIgnoreCase(host)) throw new UnknownHostException();
        return validated.clone();
      }

      @Override
      public String resolveCanonicalHostname(String host) throws UnknownHostException {
        if (!hostname.equalsIgnoreCase(host)) throw new UnknownHostException();
        return hostname;
      }
    };
  }

  private static boolean sameOrigin(URI a, URI b) {
    return a.getScheme().equalsIgnoreCase(b.getScheme())
        && a.getHost().equalsIgnoreCase(b.getHost())
        && effectivePort(a) == effectivePort(b);
  }

  private static int effectivePort(URI uri) {
    return uri.getPort() != -1
        ? uri.getPort()
        : uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
  }

  private static String entityTag(String value) {
    value = validator(value);
    if (value == null) return null;
    String tag = value.startsWith("W/") ? value.substring(2) : value;
    if (tag.length() < 2 || tag.charAt(0) != '"' || tag.charAt(tag.length() - 1) != '"')
      return null;
    for (int index = 1; index < tag.length() - 1; index++) {
      char character = tag.charAt(index);
      if (character != 0x21 && (character < 0x23 || character > 0xff)) return null;
    }
    return value;
  }

  private static String modifiedSince(String value) {
    value = validator(value);
    if (value == null) return null;
    try {
      ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
      return value;
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  static String validator(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 512
        || value.chars().anyMatch(c -> c < 32 || c == 127)) return null;
    return value;
  }

  static Instant retryAfter(String value, Instant now) {
    if (value == null || value.length() > 128) return null;
    try {
      if (value.matches("[0-9]{1,12}")) return now.plusSeconds(Long.parseLong(value));
      Instant result = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      return result.isAfter(now) ? result : null;
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  private static String header(ClassicHttpResponse response, String name) {
    Header header = response.getFirstHeader(name);
    return header == null ? null : header.getValue();
  }

  private static long remaining(long deadline) {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0 || Thread.currentThread().isInterrupted())
      throw failure("DOWNLOAD_TIMEOUT", "A coleta excedeu o tempo permitido");
    return nanos;
  }

  private static FeedTransportException failure(String code, String message) {
    return new FeedTransportException(code, message, null);
  }
}
