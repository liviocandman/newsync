package br.com.agregador.sources;

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

  public record Source(UUID id, String title, String lastResult, OffsetDateTime lastSuccessAt) {}

  public List<Source> list() {
    return jdbc.sql(
            "SELECT id,title,last_result,last_success_at FROM sources ORDER BY created_at,id")
        .query(Source.class)
        .list();
  }

  public long lockActive(UUID id) {
    return jdbc.sql(
            "SELECT lifecycle_version FROM sources WHERE id=:id AND status='active' FOR UPDATE")
        .param("id", id)
        .query(Long.class)
        .optional()
        .orElseThrow(() -> new IllegalStateException("Fonte não ativa"));
  }

  public void confirm(UUID id, boolean partial, OffsetDateTime now) {
    jdbc.sql(
            """
            UPDATE sources SET last_result=:result,
              last_success_at=CASE WHEN :partial THEN last_success_at ELSE :now END,updated_at=:now WHERE id=:id
            """)
        .param("result", partial ? "partial" : "success")
        .param("partial", partial)
        .param("now", now)
        .param("id", id)
        .update();
  }
}
