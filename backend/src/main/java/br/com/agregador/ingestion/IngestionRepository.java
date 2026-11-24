package br.com.agregador.ingestion;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Editorial persistence: never assigns read_at, saved_at, first_seen_at or sort_at on update. */
@Repository
public class IngestionRepository {
  private final JdbcClient jdbc;
  private final NamedParameterJdbcOperations batch;

  public IngestionRepository(JdbcClient jdbc, NamedParameterJdbcOperations batch) {
    this.jdbc = jdbc;
    this.batch = batch;
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
      UUID id,
      String dedupKey,
      String externalId,
      String title,
      OffsetDateTime publishedAt,
      String normalizedUrl) {}

  private static final class Candidates {
    final Map<String, Existing> byKey = new HashMap<>();
    final Map<String, List<Existing>> byUrl = new HashMap<>();

    void remember(Existing old, Existing value) {
      if (old != null) {
        byKey.remove(old.dedupKey());
        if (old.normalizedUrl() != null)
          byUrl.get(old.normalizedUrl()).removeIf(row -> row.id().equals(old.id()));
      }
      byKey.put(value.dedupKey(), value);
      if (value.normalizedUrl() != null)
        byUrl.computeIfAbsent(value.normalizedUrl(), ignored -> new ArrayList<>()).add(value);
    }
  }

  private Candidates candidates(UUID sourceId, FeedParser.Feed feed) {
    var keys = new HashSet<String>();
    var urls = new HashSet<String>();
    for (var item : feed.items()) {
      if (item.externalId() != null) keys.add(FeedParser.key("id", item.externalId()));
      keys.add(FeedParser.fingerprint(item));
      if (item.normalizedUrl() != null) {
        urls.add(item.normalizedUrl());
        keys.add(FeedParser.key("url", item.normalizedUrl()));
      }
    }
    var result = new Candidates();
    jdbc.sql(
            "SELECT id,dedup_key,external_id,title,published_at,normalized_article_url AS normalized_url FROM articles WHERE source_id=:source AND (dedup_key IN (:keys)"
                + (urls.isEmpty() ? "" : " OR normalized_article_url IN (:urls)")
                + ")")
        .param("source", sourceId)
        .params(urls.isEmpty() ? Map.of("keys", keys) : Map.of("keys", keys, "urls", urls))
        .query(Existing.class)
        .list()
        .forEach(row -> result.remember(null, row));
    return result;
  }

  private record Write(UUID id, String key, FeedParser.Item item, boolean insert) {}

  public FeedParser.Feed persist(UUID sourceId, FeedParser.Feed feed, OffsetDateTime now) {
    if (feed.items().isEmpty()) return feed;
    var candidates = candidates(sourceId, feed);
    var writes = new ArrayList<Write>();
    Map<String, Long> occurrences = new HashMap<>();
    for (var item : feed.items())
      if (item.normalizedUrl() != null) occurrences.merge(item.normalizedUrl(), 1L, Long::sum);
    int warnings = 0;
    for (var item : feed.items()) {
      var urlMatches = candidates.byUrl.getOrDefault(item.normalizedUrl(), List.of());
      Existing match =
          item.externalId() == null
              ? null
              : candidates.byKey.get(FeedParser.key("id", item.externalId()));
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
            urlKey != null && !ambiguous && !candidates.byKey.containsKey(urlKey)
                ? urlKey
                : FeedParser.fingerprint(item);
        match = candidates.byKey.get(key); // Only exact deterministic fingerprints may repeat.
      }
      UUID id = match == null ? UUID.randomUUID() : match.id();
      writes.add(new Write(id, key, item, match == null));
      candidates.remember(
          match,
          new Existing(
              id,
              key,
              item.externalId() == null && match != null ? match.externalId() : item.externalId(),
              item.title(),
              item.publishedAt() == null
                  ? (match == null ? null : match.publishedAt())
                  : item.publishedAt().atOffset(ZoneOffset.UTC),
              item.normalizedUrl() == null && match != null
                  ? match.normalizedUrl()
                  : item.normalizedUrl()));
    }
    flush(sourceId, writes, now);
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

  private static final String INSERT =
      """
        INSERT INTO articles(id,source_id,dedup_key,external_id,title,original_url,normalized_article_url,author,
          content_html,content_text,published_at,sort_at,first_seen_at,last_seen_at,updated_at,search_document)
        VALUES (:id,:source,:key,:external,:title,:url,:normalized,:author,:html,:text,:published,:sort,:now,:now,:now,
          setweight(to_tsvector('simple',:title),'A') || setweight(to_tsvector('simple',coalesce(:text,'')),'B'))
        """;

  private static final String UPDATE =
      """
        UPDATE articles SET dedup_key=:key,external_id=coalesce(:external,external_id),title=:title,
          original_url=coalesce(:url,original_url),normalized_article_url=coalesce(:normalized,normalized_article_url),
          author=coalesce(:author,author),content_html=coalesce(:html,content_html),content_text=coalesce(:text,content_text),
          published_at=coalesce(:published,published_at),last_seen_at=:now,updated_at=:now,
          search_document=CASE WHEN title IS DISTINCT FROM :title OR content_text IS DISTINCT FROM coalesce(:text,content_text)
            THEN setweight(to_tsvector('simple',:title),'A') || setweight(to_tsvector('simple',coalesce(:text,content_text,'')),'B')
            ELSE search_document END
        WHERE id=:id AND ROW(dedup_key,external_id,title,original_url,normalized_article_url,author,content_html,content_text,published_at)
          IS DISTINCT FROM ROW(:key,coalesce(:external,external_id),:title,coalesce(:url,original_url),
            coalesce(:normalized,normalized_article_url),coalesce(:author,author),coalesce(:html,content_html),
            coalesce(:text,content_text),coalesce(:published,published_at))
        """;

  private void flush(UUID sourceId, List<Write> writes, OffsetDateTime now) {
    // Preserve statement order when identities are promoted or URLs are reused within one feed.
    for (int start = 0; start < writes.size(); ) {
      boolean insert = writes.get(start).insert();
      int end = start + 1;
      while (end < writes.size() && end - start < 100 && writes.get(end).insert() == insert) end++;
      var parameters = new ArrayList<SqlParameterSource>();
      for (var write : writes.subList(start, end)) parameters.add(bind(sourceId, write, now));
      batch.batchUpdate(insert ? INSERT : UPDATE, parameters.toArray(SqlParameterSource[]::new));
      start = end;
    }
    var seen = writes.stream().filter(write -> !write.insert()).map(Write::id).distinct().toList();
    if (!seen.isEmpty())
      jdbc.sql("UPDATE articles SET last_seen_at=:now WHERE id IN (:ids)")
          .param("now", now)
          .param("ids", seen)
          .update();
  }

  private SqlParameterSource bind(UUID sourceId, Write write, OffsetDateTime now) {
    var item = write.item();
    var published = item.publishedAt() == null ? null : item.publishedAt().atOffset(ZoneOffset.UTC);
    return new MapSqlParameterSource()
        .addValue("id", write.id())
        .addValue("source", sourceId)
        .addValue(
            "sort", published == null || published.isAfter(now.plusHours(24)) ? now : published)
        .addValue("key", write.key())
        .addValue("external", item.externalId(), Types.VARCHAR)
        .addValue("title", item.title())
        .addValue("url", item.url(), Types.VARCHAR)
        .addValue("normalized", item.normalizedUrl(), Types.VARCHAR)
        .addValue("author", item.author(), Types.VARCHAR)
        .addValue("html", item.html(), Types.VARCHAR)
        .addValue("text", item.text(), Types.VARCHAR)
        .addValue(
            "published",
            item.publishedAt() == null ? null : item.publishedAt().atOffset(ZoneOffset.UTC),
            Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("now", now);
  }
}
