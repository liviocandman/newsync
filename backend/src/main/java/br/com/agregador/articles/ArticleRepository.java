package br.com.agregador.articles;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
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

  public record FeedRow(
      UUID id,
      UUID sourceId,
      String title,
      OffsetDateTime publishedAt,
      boolean read,
      boolean saved,
      OffsetDateTime sortAt) {
    public Summary summary() {
      return new Summary(id, sourceId, title, publishedAt, read, saved);
    }
  }

  public List<FeedRow> page(ArticlePagination.Request filter) {
    var conditions = new ArrayList<String>();
    var values = new HashMap<String, Object>();
    if (filter.sourceId() != null) {
      conditions.add("source_id=:source");
      values.put("source", filter.sourceId());
    }
    if (filter.read() != null)
      conditions.add("read_at IS " + (filter.read() ? "NOT NULL" : "NULL"));
    if (filter.saved() != null)
      conditions.add("saved_at IS " + (filter.saved() ? "NOT NULL" : "NULL"));
    if (filter.before() != null) {
      conditions.add("(sort_at,id)<(:before,:beforeId)");
      values.put("before", filter.before());
      values.put("beforeId", filter.beforeId());
    }
    values.put("limit", filter.limit() + 1);
    return jdbc.sql(
            "SELECT id,source_id,title,published_at,read_at IS NOT NULL AS read,saved_at IS NOT NULL AS saved,sort_at FROM articles"
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions))
                + " ORDER BY sort_at DESC,id DESC LIMIT :limit")
        .params(values)
        .query(FeedRow.class)
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

  public List<Summary> search(SearchRequest request) {
    var conditions = new ArrayList<String>();
    var values = new HashMap<String, Object>();
    conditions.add("numnode(query)>0 AND search_document @@ query");
    values.put("q", request.q());
    if (request.sourceId() != null) {
      conditions.add("source_id=:source");
      values.put("source", request.sourceId());
    }
    if (request.from() != null) {
      conditions.add("sort_at>=:from");
      values.put("from", request.from());
    }
    if (request.to() != null) {
      conditions.add("sort_at<:to");
      values.put("to", request.to());
    }
    values.put("limit", request.limit() + 1);
    values.put("offset", request.offset());
    return jdbc.sql(
            "SELECT id,source_id,title,published_at,read_at IS NOT NULL AS read,saved_at IS NOT NULL AS saved"
                + " FROM articles CROSS JOIN plainto_tsquery('simple',:q) query WHERE "
                + String.join(" AND ", conditions)
                + " ORDER BY ts_rank(search_document,query) DESC,sort_at DESC,id DESC LIMIT :limit OFFSET :offset")
        .params(values)
        .query(Summary.class)
        .list();
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
