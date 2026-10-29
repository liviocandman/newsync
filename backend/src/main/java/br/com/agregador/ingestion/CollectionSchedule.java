package br.com.agregador.ingestion;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.function.DoubleSupplier;
import org.springframework.stereotype.Component;

@Component
public class CollectionSchedule {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final long[] MINUTES = {5, 15, 30, 60, 120, 240, 360};
  private final DoubleSupplier jitter;

  public CollectionSchedule() {
    this(() -> RANDOM.nextDouble());
  }

  public CollectionSchedule(DoubleSupplier jitter) {
    this.jitter = jitter;
  }

  public OffsetDateTime next(
      OffsetDateTime completed, String result, int failures, OffsetDateTime retryAfter) {
    long seconds = 30 * 60;
    if (result.equals("failed")) {
      long base = MINUTES[Math.min(Math.max(0, failures), MINUTES.length - 1)] * 60;
      seconds =
          Math.min(360 * 60, base + (long) (base * .1 * Math.clamp(jitter.getAsDouble(), 0, 1)));
    }
    var local = completed.plusSeconds(seconds);
    return retryAfter != null && retryAfter.isAfter(local) ? retryAfter : local;
  }
}
