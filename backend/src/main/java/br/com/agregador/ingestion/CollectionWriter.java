package br.com.agregador.ingestion;

import br.com.agregador.sources.SourceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionWriter {
  private final IngestionRepository editorial;
  private final SourceRepository sources;

  public CollectionWriter(IngestionRepository editorial, SourceRepository sources) {
    this.editorial = editorial;
    this.sources = sources;
  }

  @Transactional(timeout = 10)
  public void confirm(UUID sourceId, long expectedVersion, FeedParser.Feed feed) {
    editorial.writeLimits();
    if (!editorial.lockCurrent(sourceId, expectedVersion, null, false))
      throw new IllegalStateException("Versão da fonte alterada");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    var resolved = editorial.persist(sourceId, feed, now);
    sources.confirm(sourceId, resolved.partial(), now);
  }

  @Transactional(timeout = 10)
  public boolean confirm(
      UUID sourceId,
      long expectedVersion,
      FeedParser.Feed feed,
      OffsetDateTime manualRequestedAt,
      Consumer<FeedParser.Feed> confirmMetadata) {
    editorial.writeLimits();
    if (!editorial.lockCurrent(sourceId, expectedVersion, manualRequestedAt, true)) return false;
    var resolved = editorial.persist(sourceId, feed, OffsetDateTime.now(ZoneOffset.UTC));
    confirmMetadata.accept(resolved);
    return true;
  }
}
