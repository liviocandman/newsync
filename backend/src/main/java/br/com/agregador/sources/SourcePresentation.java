package br.com.agregador.sources;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class SourcePresentation {
  private final CollectionActivity activity;
  private final boolean enabled;

  public SourcePresentation(
      CollectionActivity activity,
      Environment environment,
      @Value("${app.collection.enabled:true}") boolean enabled) {
    this.activity = activity;
    this.enabled = enabled && !environment.matchesProfiles("demo");
  }

  public record Diagnostic(String code, String message, int ignoredItems) {}

  public record Source(
      UUID id,
      String feedUrl,
      String title,
      String state,
      String lastResult,
      OffsetDateTime lastSuccessAt,
      OffsetDateTime lastAttemptAt,
      OffsetDateTime manualRequestedAt,
      OffsetDateTime retryAfterAt,
      Diagnostic diagnostic,
      OffsetDateTime nextPollAt,
      int consecutiveFailures,
      boolean collecting,
      boolean collectionEnabled) {}

  public Source view(SourceRepository.Row row) {
    boolean collecting = activity.matches(row.id(), row.lifecycleVersion());
    return new Source(
        row.id(),
        row.feedUrl(),
        row.title(),
        row.status(),
        row.lastResult(),
        row.lastSuccessAt(),
        row.lastAttemptAt(),
        row.manualRequestedAt(),
        row.retryAfterAt(),
        row.diagnosticCode() == null
            ? null
            : new Diagnostic(row.diagnosticCode(), row.diagnosticMessage(), row.ignoredItems()),
        row.nextPollAt(),
        row.consecutiveFailures(),
        collecting,
        enabled);
  }
}
