package durablejobs;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * Implementations of {@link JobExecutionTimeResolver} for recurring {@link DurableJob}s.
 *
 * <p>All implementations use the supplied {@link Clock} parameter so time zones and test clocks are
 * respected deterministically.
 */
public final class RecurringJobExecutionTimeResolvers {

  private RecurringJobExecutionTimeResolvers() {}

  /**
   * Every day at 02:00 in {@code clock}'s zone (UTC in production).
   *
   * <p>If today's 02:00 is strictly after {@code clock.instant()}, resolves to today at 02:00;
   * otherwise resolves to tomorrow at 02:00. Used for {@link
   * DurableJobName#DESTROY_EXPIRED_SANDBOXES}.
   */
  public static final class Daily2Am implements JobExecutionTimeResolver {

    @Override
    public Instant resolveExecutionTime(Clock clock) {
      Instant now = clock.instant();
      LocalDate today = LocalDate.now(clock);
      Instant todayAt2Am = today.atTime(2, 0).atZone(clock.getZone()).toInstant();
      if (todayAt2Am.isAfter(now)) {
        return todayAt2Am;
      }
      return today.plusDays(1).atTime(2, 0).atZone(clock.getZone()).toInstant();
    }
  }

  /** Every Sunday at 02:00 in {@code clock}'s zone. Used for {@link DurableJobName#OLD_JOB_CLEANUP}. */
  public static final class Sunday2Am implements JobExecutionTimeResolver {

    @Override
    public Instant resolveExecutionTime(Clock clock) {
      return LocalDate.now(clock)
          .with(TemporalAdjusters.next(DayOfWeek.SUNDAY))
          .atStartOfDay(clock.getZone())
          .plus(2, ChronoUnit.HOURS)
          .toInstant();
    }
  }
}
