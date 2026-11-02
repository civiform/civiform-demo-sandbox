package durablejobs;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.Value;
import models.JobType;

/**
 * Looks up a concrete {@link DurableJob} factory by its {@link DurableJobName}, and exposes all
 * recurring jobs together with their {@link JobExecutionTimeResolver}s.
 *
 * <p>Replicates CiviForm's {@code DurableJobRegistry}.
 */
public final class DurableJobRegistry {

  private final Map<String, RegisteredJob> registeredJobs = new HashMap<>();

  @Override
  public String toString() {
    return "DurableJobRegistry" + registeredJobs.entrySet();
  }

  /** A {@link DurableJob} entry registered in {@link DurableJobRegistry}. */
  @Value
  public static class RegisteredJob {
    DurableJobFactory factory;
    DurableJobName jobName;
    JobType jobType;
    Optional<JobExecutionTimeResolver> recurringJobExecutionTimeResolver;

    /** True if this job is recurring. */
    public boolean isRecurring() {
      return recurringJobExecutionTimeResolver.isPresent();
    }
  }

  /** Registers a factory without a time resolver (intended for unit tests). */
  @VisibleForTesting
  public void registerWithNoTimeResolver(
      DurableJobName jobName, JobType jobType, DurableJobFactory durableJobFactory) {
    validateJobName(jobName);
    registeredJobs.put(
        jobName.getJobNameString(),
        new RegisteredJob(
            checkNotNull(durableJobFactory), jobName, checkNotNull(jobType), Optional.empty()));
  }

  /**
   * Registers a recurring job factory along with the {@link JobExecutionTimeResolver} defining its
   * schedule. Can only be used with {@link JobType#RECURRING}.
   */
  public void register(
      DurableJobName jobName,
      JobType jobType,
      DurableJobFactory durableJobFactory,
      JobExecutionTimeResolver jobExecutionTimeResolver) {
    validateJobName(jobName);
    if (jobType != JobType.RECURRING) {
      throw new IllegalArgumentException("Recurring jobs must have JobType.RECURRING.");
    }
    registeredJobs.put(
        jobName.getJobNameString(),
        new RegisteredJob(
            checkNotNull(durableJobFactory),
            jobName,
            jobType,
            Optional.of(checkNotNull(jobExecutionTimeResolver))));
  }

  private void validateJobName(DurableJobName jobName) {
    checkNotNull(jobName);
    if (registeredJobs.containsKey(jobName.getJobNameString())) {
      throw new IllegalArgumentException(
          String.format("Unable to register durable job with duplicate job name: %s", jobName));
    }
  }

  /** Retrieves the registered job for {@code jobName} or throws {@link JobNotFoundException}. */
  public RegisteredJob get(DurableJobName jobName) throws JobNotFoundException {
    return Optional.ofNullable(registeredJobs.get(jobName.getJobNameString()))
        .orElseThrow(() -> new JobNotFoundException(jobName.getJobNameString()));
  }

  /** Returns all jobs registered with a {@link JobExecutionTimeResolver}. */
  public ImmutableSet<RegisteredJob> getRecurringJobs() {
    return registeredJobs.values().stream()
        .filter(RegisteredJob::isRecurring)
        .collect(ImmutableSet.toImmutableSet());
  }
}
