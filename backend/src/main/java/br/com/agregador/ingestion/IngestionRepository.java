package br.com.agregador.ingestion;

import java.sql.Types;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Editorial persistence: never assigns read_at, saved_at, first_seen_at or sort_at on update. */
@Repository
public class IngestionRepository {
  private final JdbcClient jdbc;

  public IngestionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void writeLimits() {
    jdbc.sql("SET LOCAL statement_timeout='5s'").update();
    jdbc.sql("SET LOCAL lock_timeout='2s'").update();
  }

  record SourceLock(long lifecycleVersion, String status, OffsetDateTime manualRequestedAt) {}

  public boolean lockCurrent(
      UUID sourceId, long version, OffsetDateTime requestedAt, boolean manual) {
    var row =
        jdbc.sql(
                "SELECT lifecycle_version,status,manual_requested_at FROM sources WHERE id=:id FOR UPDATE")
            .param("id", sourceId)
            .query(SourceLock.class)
            .optional();
    if (row.isEmpty()) return false;
    SourceLock source = row.get();
    if (!"active".equals(source.status()) || source.lifecycleVersion() != version) return false;
    return !manual
        || Objects.equals(
            requestedAt == null ? null : requestedAt.toInstant(),
            source.manualRequestedAt() == null ? null : source.manualRequestedAt().toInstant());
  }

  record Existing(
      UUID id, String dedupKey, String externalId, String title, OffsetDateTime publishedAt) {}

  private Existing byKey(UUID sourceId, String key) {
    return jdbc.sql(
            "SELECT id,dedup_key,external_id,title,published_at FROM articles WHERE source_id=:source AND dedup_key=:key")
        .param("source", sourceId)
        .param("key", key)
        .query(Existing.class)
        .optional()
        .orElse(null);
  }

  private List<Existing> byUrl(UUID sourceId, String url) {
    if (url == null) return List.of();
    return jdbc.sql(
            "SELECT id,dedup_key,external_id,title,published_at FROM articles WHERE source_id=:source AND normalized_article_url=:url ORDER BY id")
        .param("source", sourceId)
        .param("url", url)
        .query(Existing.class)
        .list();
  }

  public FeedParser.Feed persist(UUID sourceId, FeedParser.Feed feed, OffsetDateTime now) {
    Map<String, Long> occurrences = new HashMap<>();
    for (var item : feed.items())
      if (item.normalizedUrl() != null) occurrences.merge(item.normalizedUrl(), 1L, Long::sum);
    int warnings = 0;
    for (var item : feed.items()) {
      var urlMatches = byUrl(sourceId, item.normalizedUrl());
      Existing match =
          item.externalId() == null
              ? null
              : byKey(sourceId, FeedParser.key("id", item.externalId()));
      boolean ambiguous =
          item.urlAmbiguous()
              || item.normalizedUrl() != null
                  && (occurrences.get(item.normalizedUrl()) > 1 || urlMatches.size() > 1);
      if (match != null) {
        UUID matchedId = match.id();
        if (urlMatches.stream().anyMatch(other -> !other.id().equals(matchedId))) warnings++;
      } else if (!ambiguous && urlMatches.size() == 1) {
        Existing candidate = urlMatches.getFirst();
        if (!candidate.dedupKey().startsWith("v1:fp:") && compatible(item, candidate))
          match = candidate;
        else warnings++;
      } else if (ambiguous) warnings++;

      String key;
      if (match != null) {
        key =
            item.externalId() != null ? FeedParser.key("id", item.externalId()) : match.dedupKey();
      } else if (item.externalId() != null) {
        key = FeedParser.key("id", item.externalId());
      } else {
        String urlKey =
            item.normalizedUrl() == null ? null : FeedParser.key("url", item.normalizedUrl());
        key =
            urlKey != null && !ambiguous && byKey(sourceId, urlKey) == null
                ? urlKey
                : FeedParser.fingerprint(item);
        match = byKey(sourceId, key); // Only exact deterministic fingerprints may repeat.
      }
      if (match == null) insert(sourceId, key, item, now);
      else update(match.id(), key, item, now);
    }
    return new FeedParser.Feed(
        feed.items(), feed.partial() || warnings > 0, feed.ignoredItems() + warnings);
  }

  private static boolean compatible(FeedParser.Item item, Existing stored) {
    if (item.externalId() != null
        && stored.externalId() != null
        && !item.externalId().equals(stored.externalId())) return false;
    Instant previous = stored.publishedAt() == null ? null : stored.publishedAt().toInstant();
    if (item.publishedAt() != null && previous != null) return item.publishedAt().equals(previous);
    return FeedParser.normalizedTitle(item.title())
        .equals(FeedParser.normalizedTitle(stored.title()));
  }

  private void insert(UUID sourceId, String key, FeedParser.Item item, OffsetDateTime now) {
    OffsetDateTime published =
        item.publishedAt() == null ? null : item.publishedAt().atOffset(ZoneOffset.UTC);
    OffsetDateTime sort =
        published == null || published.isAfter(now.plusHours(24)) ? now : published;
    bind(
            jdbc.sql(
                """
        INSERT INTO articles(id,source_id,dedup_key,external_id,title,original_url,normalized_article_url,author,
          content_html,content_text,published_at,sort_at,first_seen_at,last_seen_at,updated_at)
        VALUES (:id,:source,:key,:external,:title,:url,:normalized,:author,:html,:text,:published,:sort,:now,:now,:now)
        """),
            item,
            key,
            now)
        .param("id", UUID.randomUUID())
        .param("source", sourceId)
        .param("sort", sort)
        .update();
  }

  private void update(UUID id, String key, FeedParser.Item item, OffsetDateTime now) {
    bind(
            jdbc.sql(
                """
        UPDATE articles SET dedup_key=:key,external_id=coalesce(:external,external_id),title=:title,
          original_url=coalesce(:url,original_url),normalized_article_url=coalesce(:normalized,normalized_article_url),
          author=coalesce(:author,author),content_html=coalesce(:html,content_html),content_text=coalesce(:text,content_text),
          published_at=coalesce(:published,published_at),last_seen_at=:now,updated_at=:now
        WHERE id=:id
        """),
            item,
            key,
            now)
        .param("id", id)
        .update();
  }

  private JdbcClient.StatementSpec bind(
      JdbcClient.StatementSpec statement, FeedParser.Item item, String key, OffsetDateTime now) {
    return statement
        .param("key", key)
        .param("external", item.externalId(), Types.VARCHAR)
        .param("title", item.title())
        .param("url", item.url(), Types.VARCHAR)
        .param("normalized", item.normalizedUrl(), Types.VARCHAR)
        .param("author", item.author(), Types.VARCHAR)
        .param("html", item.html(), Types.VARCHAR)
        .param("text", item.text(), Types.VARCHAR)
        .param(
            "published",
            item.publishedAt() == null ? null : item.publishedAt().atOffset(ZoneOffset.UTC),
            Types.TIMESTAMP_WITH_TIMEZONE)
        .param("now", now);
  }
}
