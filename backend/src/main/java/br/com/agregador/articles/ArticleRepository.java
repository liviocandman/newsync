package br.com.agregador.articles;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ArticleRepository {
  private final JdbcClient jdbc;

  public ArticleRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record Summary(
      UUID id,
      UUID sourceId,
      String title,
      OffsetDateTime publishedAt,
      boolean read,
      boolean saved) {}

  public record Article(
      UUID id,
      UUID sourceId,
      String title,
      String originalUrl,
      OffsetDateTime publishedAt,
      String contentHtml,
      String contentText,
      String contentOrigin,
      String completeness,
      boolean read,
      boolean saved,
      OffsetDateTime readAt,
      OffsetDateTime savedAt) {}

  public record State(
      UUID id, boolean read, boolean saved, OffsetDateTime readAt, OffsetDateTime savedAt) {}

  public List<Summary> list() {
    return jdbc.sql(
            """
            SELECT id,source_id,title,published_at,read_at IS NOT NULL AS read,saved_at IS NOT NULL AS saved
            FROM articles ORDER BY sort_at DESC,id DESC
            """)
        .query(Summary.class)
        .list();
  }

  public Article find(UUID id) {
    return jdbc.sql(
            """
            SELECT id,source_id,title,original_url,published_at,content_html,content_text,content_origin,completeness,
                read_at IS NOT NULL AS read,saved_at IS NOT NULL AS saved,read_at,saved_at FROM articles WHERE id=:id
            """)
        .param("id", id)
        .query(Article.class)
        .optional()
        .orElseThrow(NoSuchElementException::new);
  }

  public State setState(UUID id, Map<String, Object> patch) {
    var assignments = new ArrayList<String>();
    for (String field : List.of("read", "saved")) {
      if (patch.containsKey(field)) {
        String column = field + "_at";
        assignments.add(
            column
                + "="
                + ((Boolean) patch.get(field)
                    ? "coalesce(" + column + ",statement_timestamp())"
                    : "NULL"));
      }
    }
    return jdbc.sql(
            "UPDATE articles SET "
                + String.join(",", assignments)
                + " WHERE id=:id "
                + "RETURNING id,read_at IS NOT NULL AS read,saved_at IS NOT NULL AS saved,read_at,saved_at")
        .param("id", id)
        .query(State.class)
        .optional()
        .orElseThrow(NoSuchElementException::new);
  }
}
