package br.com.agregador.sources;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SourceRepository {
  private final JdbcClient jdbc;

  public SourceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
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
      Diagnostic diagnostic) {}

  public record Row(
      UUID id,
      String feedUrl,
      String title,
      String status,
      long lifecycleVersion,
      String lastResult,
      OffsetDateTime lastSuccessAt,
      OffsetDateTime lastAttemptAt,
      OffsetDateTime manualRequestedAt,
      OffsetDateTime lastManualRefreshAt,
      OffsetDateTime retryAfterAt,
      String etag,
      String lastModified,
      String diagnosticCode,
      String diagnosticMessage,
      int ignoredItems) {
    public Source view() {
      return new Source(
          id,
          feedUrl,
          title,
          status,
          lastResult,
          lastSuccessAt,
          lastAttemptAt,
          manualRequestedAt,
          retryAfterAt,
          diagnosticCode == null
              ? null
              : new Diagnostic(diagnosticCode, diagnosticMessage, ignoredItems));
    }
  }

  private static final String COLUMNS =
      "id,feed_url,title,status,lifecycle_version,last_result,last_success_at,last_attempt_at,manual_requested_at,last_manual_refresh_at,retry_after_at,etag,last_modified,diagnostic_code,diagnostic_message,ignored_items";

  // Only the fixed demo/test infrastructure uses idempotent registration.
  public UUID register(String url, String title) {
    return jdbc.sql(
            """
      INSERT INTO sources(id,feed_url,normalized_feed_url,title,created_at,updated_at)
      VALUES (:id,:url,:url,:title,now(),now())
      ON CONFLICT(normalized_feed_url) DO UPDATE SET normalized_feed_url=excluded.normalized_feed_url
      RETURNING id
      """)
        .param("id", UUID.randomUUID())
        .param("url", url)
        .param("title", title)
        .query(UUID.class)
        .single();
  }

  public void writeLimits() {
    jdbc.sql("SET LOCAL statement_timeout='5s'").update();
    jdbc.sql("SET LOCAL lock_timeout='2s'").update();
  }

  public UUID create(String url, String title, OffsetDateTime now) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
      INSERT INTO sources(id,feed_url,normalized_feed_url,title,created_at,updated_at,next_poll_at)
      VALUES (:id,:url,:url,:title,:now,:now,:now)
      """)
        .param("id", id)
        .param("url", url)
        .param("title", title)
        .param("now", now)
        .update();
    return id;
  }

  public List<Source> list() {
    return jdbc
        .sql("SELECT " + COLUMNS + " FROM sources ORDER BY created_at,id")
        .query(Row.class)
        .list()
        .stream()
        .map(Row::view)
        .toList();
  }

  public Row find(UUID id) {
    return row(id, false);
  }

  public Row lock(UUID id) {
    return row(id, true);
  }

  private Row row(UUID id, boolean lock) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM sources WHERE id=:id" + (lock ? " FOR UPDATE" : ""))
        .param("id", id)
        .query(Row.class)
        .optional()
        .orElseThrow(NoSuchElementException::new);
  }

  public long lockActive(UUID id) {
    Row row = lock(id);
    if (!row.status().equals("active")) throw new IllegalStateException("Fonte não ativa");
    return row.lifecycleVersion();
  }

  public void confirm(UUID id, boolean partial, OffsetDateTime now) {
    jdbc.sql(
            """
      UPDATE sources SET last_result=:result,
      last_success_at=CASE WHEN :partial THEN last_success_at ELSE :now END,updated_at=:now WHERE id=:id
      """)
        .param("id", id)
        .param("result", partial ? "partial" : "success")
        .param("partial", partial)
        .param("now", now)
        .update();
  }

  public void edit(UUID id, String title, String state, boolean transition, OffsetDateTime now) {
    jdbc.sql(
            """
      UPDATE sources SET title=:title,status=:state,updated_at=:now,
      lifecycle_version=lifecycle_version + CASE WHEN :transition THEN 1 ELSE 0 END,
      manual_requested_at=CASE WHEN :transition THEN NULL ELSE manual_requested_at END,
      next_poll_at=CASE WHEN :transition AND :state='active' THEN :now ELSE next_poll_at END
      WHERE id=:id
      """)
        .param("id", id)
        .param("title", title)
        .param("state", state)
        .param("transition", transition)
        .param("now", now)
        .update();
  }

  public void request(UUID id, OffsetDateTime now) {
    jdbc.sql(
            "UPDATE sources SET manual_requested_at=:now,last_manual_refresh_at=:now,next_poll_at=:now,updated_at=:now WHERE id=:id")
        .param("id", id)
        .param("now", now)
        .update();
  }

  public Optional<Row> nextManual(OffsetDateTime now) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " "
                + """
      FROM sources WHERE status='active' AND manual_requested_at IS NOT NULL
      AND (retry_after_at IS NULL OR retry_after_at<=:now)
      ORDER BY manual_requested_at,id LIMIT 1
      """)
        .param("now", now)
        .query(Row.class)
        .optional();
  }

  public void attempted(UUID id, OffsetDateTime now) {
    jdbc.sql("UPDATE sources SET last_attempt_at=:now,updated_at=:now WHERE id=:id")
        .param("id", id)
        .param("now", now)
        .update();
  }

  public void complete(
      Row captured,
      String result,
      String etag,
      String modified,
      OffsetDateTime retryAfter,
      String code,
      String message,
      int ignored,
      OffsetDateTime now) {
    boolean full = result.equals("success") || result.equals("not_modified");
    jdbc.sql(
            """
      UPDATE sources SET last_result=:result,updated_at=:now,next_poll_at=:next,
      last_success_at=CASE WHEN :full THEN :now ELSE last_success_at END,
      consecutive_failures=CASE WHEN :failed THEN consecutive_failures+1 ELSE 0 END,
      etag=CASE WHEN :replaceValidators THEN :etag ELSE etag END,
      last_modified=CASE WHEN :replaceValidators THEN :modified ELSE last_modified END,
      retry_after_at=:retry,diagnostic_code=:code,diagnostic_message=:message,ignored_items=:ignored,
      manual_requested_at=CASE WHEN manual_requested_at=:captured THEN NULL ELSE manual_requested_at END
      WHERE id=:id AND status='active' AND lifecycle_version=:version
      """)
        .param("id", captured.id())
        .param("version", captured.lifecycleVersion())
        .param("captured", captured.manualRequestedAt(), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("result", result)
        .param("now", now)
        .param("next", now.plusMinutes(30))
        .param("full", full)
        .param("failed", result.equals("failed"))
        .param("replaceValidators", result.equals("success"))
        .param("etag", etag, Types.VARCHAR)
        .param("modified", modified, Types.VARCHAR)
        .param("retry", retryAfter, Types.TIMESTAMP_WITH_TIMEZONE)
        .param("code", code, Types.VARCHAR)
        .param("message", message, Types.VARCHAR)
        .param("ignored", ignored)
        .update();
  }
}
