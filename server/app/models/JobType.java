package models;

/** Defines when and how often a {@link durablejobs.DurableJob} runs. */
public enum JobType {
  /** Runs repeatedly on a schedule computed by a {@link durablejobs.JobExecutionTimeResolver}. */
  RECURRING,
  /** Runs once at server startup and never again after succeeding. */
  RUN_ONCE,
  /** Runs once each time the server starts. */
  RUN_ON_EACH_STARTUP
}
