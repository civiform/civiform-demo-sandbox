package durablejobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.Test;

/** Unit tests for {@link RecurringJobExecutionTimeResolvers}. */
public class RecurringJobExecutionTimeResolversTest {

  @Test
  public void daily2Am_before2AmUtc_returnsTodayAt2Am() {
    Clock clock = Clock.fixed(Instant.parse("2026-08-15T01:30:00Z"), ZoneOffset.UTC);

    Instant resolved = new RecurringJobExecutionTimeResolvers.Daily2Am().resolveExecutionTime(clock);

    assertThat(resolved).isEqualTo(Instant.parse("2026-08-15T02:00:00Z"));
  }

  @Test
  public void daily2Am_exactlyAt2AmUtc_returnsTomorrowAt2Am() {
    Clock clock = Clock.fixed(Instant.parse("2026-08-15T02:00:00Z"), ZoneOffset.UTC);

    Instant resolved = new RecurringJobExecutionTimeResolvers.Daily2Am().resolveExecutionTime(clock);

    assertThat(resolved).isEqualTo(Instant.parse("2026-08-16T02:00:00Z"));
  }

  @Test
  public void daily2Am_after2AmUtc_returnsTomorrowAt2Am() {
    Clock clock = Clock.fixed(Instant.parse("2026-08-15T14:45:12Z"), ZoneOffset.UTC);

    Instant resolved = new RecurringJobExecutionTimeResolvers.Daily2Am().resolveExecutionTime(clock);

    assertThat(resolved).isEqualTo(Instant.parse("2026-08-16T02:00:00Z"));
  }

  @Test
  public void sunday2Am_resolvesNextSundayAt2AmUtc() {
    // 2026-08-15 is a Saturday; next Sunday is 2026-08-16.
    Clock saturdayClock = Clock.fixed(Instant.parse("2026-08-15T10:00:00Z"), ZoneOffset.UTC);
    Instant fromSaturday =
        new RecurringJobExecutionTimeResolvers.Sunday2Am().resolveExecutionTime(saturdayClock);
    assertThat(fromSaturday).isEqualTo(Instant.parse("2026-08-16T02:00:00Z"));

    // On Sunday itself, TemporalAdjusters.next(SUNDAY) resolves to the following Sunday.
    Clock sundayClock = Clock.fixed(Instant.parse("2026-08-16T03:00:00Z"), ZoneOffset.UTC);
    Instant fromSunday =
        new RecurringJobExecutionTimeResolvers.Sunday2Am().resolveExecutionTime(sundayClock);
    assertThat(fromSunday).isEqualTo(Instant.parse("2026-08-23T02:00:00Z"));
  }
}
