package durablejobs;

import models.PersistedDurableJobModel;

/**
 * Represents code that runs in a background thread.
 *
 * <p>In contrast to plain Pekko scheduler tasks, durable jobs are backed by {@link
 * PersistedDurableJobModel} records in the database, making them durable across server restarts and
 * providing automated retry tracking. Replicates CiviForm's {@code durablejobs.DurableJob}.
 */
public abstract class DurableJob {

  /** Returns the {@link PersistedDurableJobModel} associated with this instance. */
  public abstract PersistedDurableJobModel getPersistedDurableJob();

  /** Returns the job name identifying which type of job this is. */
  public String jobName() {
    return getPersistedDurableJob().getJobName();
  }

  /** Executes the job. */
  public abstract void run();
}
