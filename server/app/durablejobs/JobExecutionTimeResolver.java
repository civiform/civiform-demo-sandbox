package durablejobs;

import java.time.Clock;
import java.time.Instant;

/** Determines when a recurring {@link DurableJob} should next execute. */
@FunctionalInterface
public interface JobExecutionTimeResolver {

  /**
   * Computes the next execution time for a recurring job based on the current time and zone
   * provided by {@code clock}.
   */
  Instant resolveExecutionTime(Clock clock);
}
