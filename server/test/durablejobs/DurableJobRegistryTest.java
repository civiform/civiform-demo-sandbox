package durablejobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import models.JobType;
import models.PersistedDurableJobModel;
import org.junit.Test;

/** Unit tests for {@link DurableJobRegistry}. */
public class DurableJobRegistryTest {

  private static DurableJobFactory noOpFactory() {
    return persisted ->
        new DurableJob() {
          @Override
          public PersistedDurableJobModel getPersistedDurableJob() {
            return persisted;
          }

          @Override
          public void run() {}
        };
  }

  @Test
  public void getRegisteredJobs_returnsAllRegisteredJobsInRegistrationOrder() {
    DurableJobRegistry registry = new DurableJobRegistry();
    registry.register(
        DurableJobName.DESTROY_EXPIRED_SANDBOXES,
        JobType.RECURRING,
        noOpFactory(),
        new RecurringJobExecutionTimeResolvers.Daily2Am());
    registry.registerWithNoTimeResolver(DurableJobName.TEST, JobType.RUN_ONCE, noOpFactory());
    registry.register(
        DurableJobName.OLD_JOB_CLEANUP,
        JobType.RECURRING,
        noOpFactory(),
        new RecurringJobExecutionTimeResolvers.Sunday2Am());

    assertThat(registry.getRegisteredJobs())
        .extracting(DurableJobRegistry.RegisteredJob::getJobName)
        .containsExactly(
            DurableJobName.DESTROY_EXPIRED_SANDBOXES,
            DurableJobName.TEST,
            DurableJobName.OLD_JOB_CLEANUP);
  }

  @Test
  public void getRecurringJobs_excludesJobsWithoutTimeResolver() {
    DurableJobRegistry registry = new DurableJobRegistry();
    registry.register(
        DurableJobName.DESTROY_EXPIRED_SANDBOXES,
        JobType.RECURRING,
        noOpFactory(),
        new RecurringJobExecutionTimeResolvers.Daily2Am());
    registry.registerWithNoTimeResolver(DurableJobName.TEST, JobType.RUN_ONCE, noOpFactory());

    assertThat(registry.getRecurringJobs())
        .extracting(DurableJobRegistry.RegisteredJob::getJobName)
        .containsExactly(DurableJobName.DESTROY_EXPIRED_SANDBOXES);
  }

  @Test
  public void get_throwsJobNotFoundExceptionWhenUnregistered() {
    DurableJobRegistry registry = new DurableJobRegistry();

    assertThatThrownBy(() -> registry.get(DurableJobName.TEST))
        .isInstanceOf(JobNotFoundException.class)
        .hasMessageContaining("TEST");
  }

  @Test
  public void register_duplicateJobName_throwsIllegalArgumentException() {
    DurableJobRegistry registry = new DurableJobRegistry();
    registry.register(
        DurableJobName.OLD_JOB_CLEANUP,
        JobType.RECURRING,
        noOpFactory(),
        new RecurringJobExecutionTimeResolvers.Sunday2Am());

    assertThatThrownBy(
            () ->
                registry.register(
                    DurableJobName.OLD_JOB_CLEANUP,
                    JobType.RECURRING,
                    noOpFactory(),
                    new RecurringJobExecutionTimeResolvers.Sunday2Am()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate job name");
  }
}
