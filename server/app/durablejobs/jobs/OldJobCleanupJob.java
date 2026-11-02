package durablejobs.jobs;

import static com.google.common.base.Preconditions.checkNotNull;

import durablejobs.DurableJob;
import models.PersistedDurableJobModel;
import play.Logger;
import play.Logger.ALogger;
import services.PersistedDurableJobRepository;

/**
 * Weekly {@link DurableJob} that deletes completed recurring job rows older than six months so
 * {@code persisted_durable_jobs} does not grow without bound.
 *
 * <p>Replicates CiviForm's {@code OldJobCleanupJob}.
 */
public final class OldJobCleanupJob extends DurableJob {

  private static final ALogger log = Logger.of(OldJobCleanupJob.class);

  private final PersistedDurableJobRepository persistedDurableJobRepository;
  private final PersistedDurableJobModel persistedDurableJobModel;

  public OldJobCleanupJob(
      PersistedDurableJobRepository persistedDurableJobRepository,
      PersistedDurableJobModel persistedDurableJobModel) {
    this.persistedDurableJobRepository = checkNotNull(persistedDurableJobRepository);
    this.persistedDurableJobModel = checkNotNull(persistedDurableJobModel);
  }

  @Override
  public PersistedDurableJobModel getPersistedDurableJob() {
    return persistedDurableJobModel;
  }

  @Override
  public void run() {
    int deletedCount = persistedDurableJobRepository.deleteJobsOlderThanSixMonths();
    log.info("OldJobCleanupJob deleted {} durable job row(s) older than 6 months", deletedCount);
  }
}
