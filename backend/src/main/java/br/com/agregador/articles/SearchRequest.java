package br.com.agregador.articles;

import br.com.agregador.auth.HttpParameters.UnsupportedParameters;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.util.MultiValueMap;

public record SearchRequest(
    String q, UUID sourceId, OffsetDateTime from, OffsetDateTime to, int limit, int offset) {
  public static SearchRequest parse(MultiValueMap<String, String> params) {
    if (!Set.of("q", "sourceId", "from", "to", "limit", "offset").containsAll(params.keySet()))
      throw new UnsupportedParameters();
    if (params.values().stream().anyMatch(values -> values.size() != 1))
      throw new IllegalArgumentException();
    String q = params.getFirst("q");
    if (q == null) throw new IllegalArgumentException();
    q = q.replaceAll("(?U)^\\s+|\\s+$", "");
    if (q.isEmpty() || q.codePointCount(0, q.length()) > 200) throw new IllegalArgumentException();
    UUID source = null;
    if (params.containsKey("sourceId")) {
      String value = params.getFirst("sourceId");
      if (!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        throw new IllegalArgumentException();
      source = UUID.fromString(value);
    }
    OffsetDateTime from = timestamp(params, "from"), to = timestamp(params, "to");
    if (from != null && to != null && !from.isBefore(to)) throw new IllegalArgumentException();
    int limit = integer(params, "limit", 30), offset = integer(params, "offset", 0);
    if (limit < 1 || limit > 100 || offset < 0) throw new IllegalArgumentException();
    return new SearchRequest(q, source, from, to, limit, offset);
  }

  private static OffsetDateTime timestamp(MultiValueMap<String, String> params, String key) {
    if (!params.containsKey(key)) return null;
    try {
      return OffsetDateTime.parse(params.getFirst(key)).withOffsetSameInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException();
    }
  }

  private static int integer(MultiValueMap<String, String> params, String key, int fallback) {
    if (!params.containsKey(key)) return fallback;
    String value = params.getFirst(key);
    if (!value.matches("[0-9]+")) throw new IllegalArgumentException();
    return Integer.parseInt(value);
  }
}
