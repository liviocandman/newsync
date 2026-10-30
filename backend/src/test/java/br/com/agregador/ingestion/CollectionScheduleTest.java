package br.com.agregador.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class CollectionScheduleTest {
  @Test
  void failureStepsJitterCeilingAndRetryAfterFloorAreBounded() {
    var now = OffsetDateTime.parse("2026-10-29T12:00:00Z");
    long[] minutes = {5, 15, 30, 60, 120, 240, 360, 360};
    for (int failures = 0; failures < minutes.length; failures++) {
      assertThat(new CollectionSchedule(() -> 0).next(now, "failed", failures, null))
          .isEqualTo(now.plusMinutes(minutes[failures]));
      assertThat(new CollectionSchedule(() -> 1).next(now, "failed", failures, null))
          .isEqualTo(now.plusSeconds(Math.min(21600, minutes[failures] * 66)));
      assertThat(new CollectionSchedule(() -> .5).next(now, "failed", failures, now.plusDays(2)))
          .isEqualTo(now.plusDays(2));
    }
    for (String result : new String[] {"success", "not_modified", "partial"}) {
      assertThat(
              new CollectionSchedule(
                      () -> {
                        throw new AssertionError();
                      })
                  .next(now, result, 8, null))
          .isEqualTo(now.plusMinutes(30));
      assertThat(new CollectionSchedule(() -> 0).next(now, result, 8, now.plusDays(2)))
          .isEqualTo(now.plusDays(2));
    }
  }
}
