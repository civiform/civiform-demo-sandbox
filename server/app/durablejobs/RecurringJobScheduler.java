package durablejobs;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import durablejobs.BindingAnnotations.RecurringJobsProviderName;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import models.JobType;
import models.PersistedDurableJobModel;
import play.db.Database;
import services.PersistedDurableJobRepository;

/**
 * Schedules recurring {@link DurableJob}s in the database.
 *
 * <p>Replicates CiviForm's {@code RecurringJobScheduler}.
 */
@Singleton
public final class RecurringJobScheduler extends AbstractJobScheduler {

  private final PersistedDurableJobRepository persistedDurableJobRepository;

  @Inject
  public RecurringJobScheduler(
      Clock clock,
      Database db,
      @RecurringJobsProviderName DurableJobRegistry durableJobRegistry,
      PersistedDurableJobRepository persistedDurableJobRepository) {
    super(clock, db, durableJobRegistry, persistedDurableJobRepository);
    this.persistedDurableJobRepository = checkNotNull(persistedDurableJobRepository);
  }

  @Override
  protected synchronized ImmutableList<JobType> allowedJobTypes() {
    return ImmutableList.of(JobType.RECURRING);
  }

  @Override
  protected synchronized Optional<PersistedDurableJobModel> findScheduledJob(
      Connection conn, DurableJobRegistry.RegisteredJob registeredJob) throws SQLException {
    return persistedDurableJobRepository.findScheduledRecurringJob(
        conn, registeredJob.getJobName().getJobNameString());
  }
}
