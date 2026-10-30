package br.com.agregador.articles;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

@Component
public class ArticlePagination {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final byte[] key = new byte[32];

  public ArticlePagination() {
    RANDOM.nextBytes(key);
  }

  public record Request(
      UUID sourceId, Boolean read, Boolean saved, int limit, OffsetDateTime before, UUID beforeId) {
    String filters() {
      return String.valueOf(sourceId) + ":" + read + ":" + saved + ":" + limit;
    }
  }

  public Request parse(MultiValueMap<String, String> params) {
    if (!Set.of("sourceId", "read", "saved", "limit", "cursor").containsAll(params.keySet())
        || params.values().stream().anyMatch(v -> v.size() != 1))
      throw new IllegalArgumentException();
    UUID source =
        params.containsKey("sourceId") ? UUID.fromString(params.getFirst("sourceId")) : null;
    Boolean read = bool(params, "read"), saved = bool(params, "saved");
    int limit = params.containsKey("limit") ? Integer.parseInt(params.getFirst("limit")) : 30;
    if (limit < 1 || limit > 100) throw new IllegalArgumentException();
    var request = new Request(source, read, saved, limit, null, null);
    if (!params.containsKey("cursor")) return request;
    String token = params.getFirst("cursor");
    try {
      if (token == null || token.length() > 1024) throw new IllegalArgumentException();
      String[] pieces = token.split("\\.", -1);
      if (pieces.length != 2) throw new IllegalArgumentException();
      byte[] payload = Base64.getUrlDecoder().decode(pieces[0]);
      if (!MessageDigest.isEqual(sign(payload), Base64.getUrlDecoder().decode(pieces[1])))
        throw new IllegalArgumentException();
      String[] fields = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
      if (fields.length != 3 || !fields[2].equals(request.filters()))
        throw new IllegalArgumentException();
      return new Request(
          source, read, saved, limit, OffsetDateTime.parse(fields[0]), UUID.fromString(fields[1]));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Cursor inválido");
    }
  }

  private static Boolean bool(MultiValueMap<String, String> params, String name) {
    if (!params.containsKey(name)) return null;
    String value = params.getFirst(name);
    if (!Set.of("true", "false").contains(value)) throw new IllegalArgumentException();
    return Boolean.valueOf(value);
  }

  public String cursor(Request request, OffsetDateTime time, UUID id) {
    byte[] payload = (time + "|" + id + "|" + request.filters()).getBytes(StandardCharsets.UTF_8);
    var encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString(payload) + "." + encoder.encodeToString(sign(payload));
  }

  private byte[] sign(byte[] payload) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(payload);
    } catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("HMAC indisponível", unavailable);
    }
  }
}
