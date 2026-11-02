package durablejobs;

import java.util.Optional;
import models.PersistedDurableJobModel;

/**
 * Links an instance of a {@link DurableJob} with its concrete type via {@link
 * PersistedDurableJobModel#getJobName()}.
 */
public enum DurableJobName {
  OLD_JOB_CLEANUP("OLD_JOB_CLEANUP"),
  // Job name reserved for unit/integration tests
  TEST("TEST");

  private final String jobName;

  DurableJobName(String jobName) {
    this.jobName = jobName;
  }

  public String getJobNameString() {
    return jobName;
  }

  public static Optional<DurableJobName> optionalValueOf(String jobName) {
    try {
      return Optional.of(DurableJobName.valueOf(jobName));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
