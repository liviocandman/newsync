package br.com.agregador.ingestion;

import br.com.agregador.auth.DatabaseFailures;
import br.com.agregador.sources.*;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@Profile("!demo")
public class ManualFeedDispatcher {
  private static final Logger log = LoggerFactory.getLogger(ManualFeedDispatcher.class);
  private String operationalFailure;
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
      var next = sources.nextEligible(OffsetDateTime.now(clock));
      if (next.isEmpty()) {
        recovered();
        return;
      }
      var captured = next.get();
      sourceId = captured.id();
      attemptId = UUID.randomUUID();
      if (!activity.start(captured.id(), captured.lifecycleVersion())) return;
      try {
        if (!service.begin(captured)) return;
        collect(captured);
        recovered();
      } finally {
        activity.clear();
      }
    } catch (RuntimeException databaseOrCommitFailure) {
      String code =
          DatabaseFailures.temporary(databaseOrCommitFailure)
              ? "COLLECTION_DATABASE_UNAVAILABLE"
              : "COLLECTION_UNCONFIRMED";
      if (!code.equals(operationalFailure)) {
        log.warn("{} sourceId={} attemptId={}", code, sourceId, attemptId);
        operationalFailure = code;
      }
      databaseRetryAt = clock.instant().plusSeconds(5);
    } finally {
      running.set(false);
    }
  }

  private void recovered() {
    if (operationalFailure != null) {
      log.info("COLLECTION_RECOVERED");
      operationalFailure = null;
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
            sources.complete(
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
