package br.com.agregador.ingestion;

import br.com.agregador.auth.DatabaseFailures;
import br.com.agregador.sources.CollectionActivity;
import br.com.agregador.sources.SourceRepository;
import br.com.agregador.sources.SourceService;
import java.io.ByteArrayInputStream;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionTimedOutException;

@Component
@Profile("!demo")
public class ManualFeedDispatcher {
  private static final Logger log = LoggerFactory.getLogger(ManualFeedDispatcher.class);
  private String operationalFailure;
  private UUID operationalSource;
  private final SourceRepository sources;
  private final SourceService service;
  private final CollectionActivity activity;
  private final PublicFeedClient transport;
  private final FeedParser parser;
  private final CollectionWriter writer;
  private final Clock clock;
  private final boolean enabled;
  private volatile boolean ready;
  private volatile Instant databaseRetryAt = Instant.MIN;
  private final AtomicBoolean running = new AtomicBoolean();
  private final Map<UUID, Instant> sourceRetryAt = new HashMap<>();
  private final Map<UUID, Integer> sourceFailures = new HashMap<>();

  public ManualFeedDispatcher(
      SourceRepository sources,
      SourceService service,
      CollectionActivity activity,
      PublicFeedClient transport,
      FeedParser parser,
      CollectionWriter writer,
      Clock clock,
      @Value("${app.collection.enabled:true}") boolean enabled) {
    this.sources = sources;
    this.service = service;
    this.activity = activity;
    this.transport = transport;
    this.parser = parser;
    this.writer = writer;
    this.clock = clock;
    this.enabled = enabled;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void ready() {
    ready = true;
  }

  @Scheduled(
      fixedDelayString = "${app.collection.delay-ms:1000}",
      initialDelayString = "${app.collection.initial-delay-ms:1000}")
  public void tick() {
    if (!enabled
        || !ready
        || clock.instant().isBefore(databaseRetryAt)
        || !running.compareAndSet(false, true)) return;
    UUID sourceId = null;
    UUID attemptId = null;
    try {
      var deferred = new HashSet<UUID>();
      sourceRetryAt.forEach(
          (id, until) -> {
            if (clock.instant().isBefore(until)) deferred.add(id);
          });
      var next = sources.nextEligible(OffsetDateTime.now(clock), deferred);
      if (next.isEmpty()) {
        if (sourceFailures.isEmpty()) recovered();
        return;
      }
      var captured = next.get();
      sourceId = captured.id();
      attemptId = UUID.randomUUID();
      if (!activity.start(captured.id(), captured.lifecycleVersion())) return;
      try {
        if (!service.begin(captured)) return;
        collect(captured);
        sourceRetryAt.remove(captured.id());
        sourceFailures.remove(captured.id());
        recovered();
      } catch (RuntimeException failure) {
        if (DatabaseFailures.temporary(failure) && !sourceContention(failure)) throw failure;
        if (!DatabaseFailures.temporary(failure) && !sourceContention(failure)) {
          try {
            service.finish(
                captured,
                "failed",
                null,
                null,
                null,
                "PERSISTENCE_FAILED",
                "Não foi possível confirmar os artigos desta fonte.",
                0);
          } catch (RuntimeException unconfirmed) {
            if (DatabaseFailures.temporary(unconfirmed) && !sourceContention(unconfirmed))
              throw unconfirmed;
            failure.addSuppressed(unconfirmed);
          }
        }
        int failures =
            sourceFailures.merge(
                captured.id(), 1, (previous, increment) -> Math.min(7, previous + increment));
        long seconds = Math.min(300, 5L << Math.min(failures - 1, 6));
        sourceRetryAt.put(captured.id(), clock.instant().plusSeconds(seconds));
        reportFailure("COLLECTION_UNCONFIRMED", sourceId, attemptId, failure);
      } finally {
        activity.clear();
      }
    } catch (RuntimeException databaseOrCommitFailure) {
      String code =
          DatabaseFailures.temporary(databaseOrCommitFailure)
              ? "COLLECTION_DATABASE_UNAVAILABLE"
              : "COLLECTION_UNCONFIRMED";
      reportFailure(code, sourceId, attemptId, databaseOrCommitFailure);
      databaseRetryAt = clock.instant().plusSeconds(5);
    } finally {
      running.set(false);
    }
  }

  private void reportFailure(String code, UUID sourceId, UUID attemptId, RuntimeException failure) {
    if (!code.equals(operationalFailure)
        || !java.util.Objects.equals(sourceId, operationalSource)) {
      // Driver messages can contain feed content and URLs; retain types and stack locations only.
      log.warn("{} sourceId={} attemptId={}", code, sourceId, attemptId, diagnostic(failure));
      operationalFailure = code;
      operationalSource = sourceId;
    }
  }

  private static RuntimeException diagnostic(Throwable failure) {
    var chain = new java.util.ArrayList<Throwable>();
    var seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    for (Throwable cause = failure;
        cause != null && seen.add(cause) && chain.size() < 16;
        cause = cause.getCause()) chain.add(cause);
    RuntimeException result = null;
    for (var cause : chain.reversed()) {
      String type = cause.getClass().getName();
      if (cause instanceof SQLException sql
          && sql.getSQLState() != null
          && sql.getSQLState().matches("[A-Z0-9]{5}")) type += " SQLState=" + sql.getSQLState();
      var safe = new IllegalStateException(type, result);
      safe.setStackTrace(cause.getStackTrace());
      result = safe;
    }
    return result;
  }

  private static boolean sourceContention(Throwable failure) {
    var seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
      if (cause instanceof QueryTimeoutException
          || cause instanceof TransactionTimedOutException
          || cause instanceof PessimisticLockingFailureException) return true;
      if (cause instanceof SQLException sql
          && sql.getSQLState() != null
          && (sql.getSQLState().equals("57014")
              || sql.getSQLState().equals("55P03")
              || sql.getSQLState().startsWith("40"))) return true;
    }
    return false;
  }

  private void recovered() {
    if (operationalFailure != null) {
      log.info("COLLECTION_RECOVERED");
      operationalFailure = null;
      operationalSource = null;
    }
  }

  private void collect(SourceRepository.Row captured) {
    PublicFeedClient.Response response;
    try {
      response = transport.fetch(captured.feedUrl(), captured.etag(), captured.lastModified());
    } catch (FeedTransportException error) {
      service.finish(
          captured, "failed", null, null, error.retryAfter(), error.code(), error.getMessage(), 0);
      return;
    }
    if (response.status() == 304) {
      service.finish(captured, "not_modified", null, null, response.retryAfter(), null, null, 0);
      return;
    }
    FeedParser.Feed feed;
    try {
      feed = parser.parse(new ByteArrayInputStream(response.body()), response.contentType());
    } catch (IllegalArgumentException invalidFeed) {
      service.finish(
          captured,
          "failed",
          null,
          null,
          response.retryAfter(),
          "INVALID_FEED",
          "Não foi possível interpretar um feed seguro dentro dos limites.",
          0);
      return;
    }
    writer.confirm(
        captured.id(),
        captured.lifecycleVersion(),
        feed,
        captured.manualRequestedAt(),
        resolved ->
            service.complete(
                captured,
                resolved.partial() ? "partial" : "success",
                response.etag(),
                response.lastModified(),
                response.retryAfter() == null
                    ? null
                    : response.retryAfter().atOffset(ZoneOffset.UTC),
                resolved.partial() ? "PARTIAL_FEED" : null,
                resolved.partial()
                    ? "Alguns itens foram ignorados ou apresentaram identidade ambígua."
                    : null,
                resolved.ignoredItems(),
                OffsetDateTime.now(clock)));
  }
}
