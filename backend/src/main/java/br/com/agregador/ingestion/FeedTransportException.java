package br.com.agregador.ingestion;

import java.time.Instant;

/** Fixed diagnostic messages intentionally omit URL, remote headers and response bodies. */
public final class FeedTransportException extends RuntimeException {
  private final String code;
  private final Instant retryAfter;

  public FeedTransportException(String code, String message, Instant retryAfter) {
    super(message);
    this.code = code;
    this.retryAfter = retryAfter;
  }

  public String code() {
    return code;
  }

  public Instant retryAfter() {
    return retryAfter;
  }
}
