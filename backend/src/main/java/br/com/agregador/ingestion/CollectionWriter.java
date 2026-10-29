package br.com.agregador.ingestion;

import br.com.agregador.sources.SourceRepository;
import java.sql.Types;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionWriter {
  private final JdbcClient jdbc;
  private final SourceRepository sources;

  public CollectionWriter(JdbcClient jdbc, SourceRepository sources) {
    this.jdbc = jdbc;
    this.sources = sources;
  }

  @Transactional(timeout = 10)
  public void confirm(UUID sourceId, long expectedVersion, FeedParser.Feed feed) {
    jdbc.sql("SET LOCAL statement_timeout='5s'").update();
    jdbc.sql("SET LOCAL lock_timeout='2s'").update();
    if (sources.lockActive(sourceId) != expectedVersion)
      throw new IllegalStateException("Versão da fonte alterada");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (var item : feed.items()) {
      OffsetDateTime published =
          item.publishedAt() == null ? null : item.publishedAt().atOffset(ZoneOffset.UTC);
      OffsetDateTime sort =
          published == null || published.isAfter(now.plusHours(24)) ? now : published;
      jdbc.sql(
              """
                INSERT INTO articles(id,source_id,dedup_key,external_id,title,original_url,normalized_article_url,author,
                  content_html,content_text,published_at,sort_at,first_seen_at,last_seen_at,updated_at)
                VALUES (:id,:source,:key,:external,:title,:url,:url,:author,:html,:text,:published,:sort,:now,:now,:now)
                ON CONFLICT(source_id,dedup_key) DO UPDATE SET
                  title=excluded.title,
                  original_url=coalesce(excluded.original_url,articles.original_url),
                  normalized_article_url=coalesce(excluded.normalized_article_url,articles.normalized_article_url),
                  author=coalesce(excluded.author,articles.author),
                  content_html=coalesce(excluded.content_html,articles.content_html),
                  content_text=coalesce(excluded.content_text,articles.content_text),
                  published_at=coalesce(excluded.published_at,articles.published_at),
                  last_seen_at=excluded.last_seen_at,updated_at=excluded.updated_at
                """)
          .param("id", UUID.randomUUID())
          .param("source", sourceId)
          .param("key", item.key())
          .param("external", item.externalId())
          .param("title", item.title())
          .param("url", item.url(), Types.VARCHAR)
          .param("author", item.author(), Types.VARCHAR)
          .param("html", item.html(), Types.VARCHAR)
          .param("text", item.text(), Types.VARCHAR)
          .param("published", published, Types.TIMESTAMP_WITH_TIMEZONE)
          .param("sort", sort)
          .param("now", now)
          .update();
    }
    sources.confirm(sourceId, feed.partial(), now);
  }
}
