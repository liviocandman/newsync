package br.com.agregador.sources;

import br.com.agregador.ingestion.PublicFeedUrl;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceService {
  private final SourceRepository sources;
  private final Clock clock;
  private final CollectionActivity activity;

  public SourceService(SourceRepository sources, Clock clock, CollectionActivity activity) {
    this.sources = sources;
    this.clock = clock;
    this.activity = activity;
  }

  public static class Rejected extends RuntimeException {
    private final int status;
    private final String code;
    private final long retryAfter;

    public Rejected(int status, String code, String message, long retryAfter) {
      super(message);
      this.status = status;
      this.code = code;
      this.retryAfter = retryAfter;
    }

    public int status() {
      return status;
    }

    public String code() {
      return code;
    }

    public long retryAfter() {
      return retryAfter;
    }
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  private void limits() {
    sources.writeLimits();
  }

  private static void fields(Map<String, Object> body, Set<String> allowed) {
    if (body == null || body.isEmpty() || !allowed.containsAll(body.keySet()))
      throw new IllegalArgumentException();
  }

  private static String title(Object value) {
    if (!(value instanceof String text) || text.isBlank() || text.length() > 300)
      throw new IllegalArgumentException();
    return text.strip();
  }

  @Transactional(timeout = 10)
  public SourceRepository.Source create(Map<String, Object> body) {
    fields(body, Set.of("feedUrl", "title"));
    if (!(body.get("feedUrl") instanceof String raw)) throw new IllegalArgumentException();
    String url = PublicFeedUrl.normalize(raw);
    String name =
        body.containsKey("title") ? title(body.get("title")) : java.net.URI.create(url).getHost();
    limits();
    try {
      return sources.find(sources.create(url, name, now())).view();
    } catch (DuplicateKeyException duplicate) {
      throw new Rejected(
          409,
          "SOURCE_EXISTS",
          "Esta URL já está cadastrada; consulte ou reative a fonte existente.",
          0);
    }
  }

  @Transactional(timeout = 10)
  public SourceRepository.Source edit(UUID id, Map<String, Object> body) {
    fields(body, Set.of("title", "state"));
    limits();
    var old = sources.lock(id);
    String name = body.containsKey("title") ? title(body.get("title")) : old.title();
    String state = old.status();
    if (body.containsKey("state")) {
      if (!(body.get("state") instanceof String text) || !Set.of("active", "paused").contains(text))
        throw new IllegalArgumentException();
      state = text;
    }
    if (old.status().equals("archived") && !state.equals("active"))
      throw new Rejected(409, "SOURCE_ARCHIVED", "Reative a fonte para alterá-la.", 0);
    sources.edit(id, name, state, !state.equals(old.status()), now());
    return sources.find(id).view();
  }

  @Transactional(timeout = 10)
  public void archive(UUID id) {
    limits();
    var old = sources.lock(id);
    sources.edit(id, old.title(), "archived", !old.status().equals("archived"), now());
  }

  public record Refresh(String status) {}

  @Transactional(timeout = 10)
  public Refresh refresh(UUID id) {
    limits();
    var row = sources.lock(id);
    var current = now();
    if (!row.status().equals("active"))
      throw new Rejected(409, "SOURCE_INACTIVE", "A fonte precisa estar ativa.", 0);
    if (row.manualRequestedAt() != null)
      return new Refresh(activity.matches(id, row.lifecycleVersion()) ? "collecting" : "scheduled");
    OffsetDateTime allowed =
        row.lastManualRefreshAt() == null ? current : row.lastManualRefreshAt().plusMinutes(1);
    if (row.retryAfterAt() != null && row.retryAfterAt().isAfter(allowed))
      allowed = row.retryAfterAt();
    if (allowed.isAfter(current))
      throw new Rejected(
          429,
          "REFRESH_COOLDOWN",
          "Aguarde antes de solicitar nova importação.",
          Math.max(1, (Duration.between(current, allowed).toMillis() + 999) / 1000));
    sources.request(id, current);
    return new Refresh("scheduled");
  }

  @Transactional(timeout = 10)
  public boolean begin(SourceRepository.Row captured) {
    limits();
    var row = sources.lock(captured.id());
    if (!current(row, captured)) return false;
    sources.attempted(row.id(), now());
    return true;
  }

  public static boolean current(SourceRepository.Row row, SourceRepository.Row captured) {
    return row.status().equals("active")
        && row.lifecycleVersion() == captured.lifecycleVersion()
        && Objects.equals(row.manualRequestedAt(), captured.manualRequestedAt());
  }

  @Transactional(timeout = 10)
  public boolean finish(
      SourceRepository.Row captured,
      String result,
      String etag,
      String modified,
      Instant retryAfter,
      String code,
      String message,
      int ignored) {
    limits();
    var row = sources.lock(captured.id());
    if (!current(row, captured)) return false;
    sources.complete(
        captured,
        result,
        etag,
        modified,
        retryAfter == null ? null : retryAfter.atOffset(ZoneOffset.UTC),
        code,
        message,
        ignored,
        now());
    return true;
  }
}
