package modules;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.typesafe.config.Config;
import durablejobs.BindingAnnotations.RecurringJobsProviderName;
import durablejobs.DurableJobName;
import durablejobs.DurableJobRegistry;
import durablejobs.RecurringDurableJobRunner;
import durablejobs.RecurringJobExecutionTimeResolvers;
import durablejobs.RecurringJobScheduler;
import durablejobs.jobs.DestroyExpiredSandboxesJob;
import durablejobs.jobs.OldJobCleanupJob;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import models.JobType;
import play.Environment;
import play.Logger;
import play.Logger.ALogger;
import play.inject.ApplicationLifecycle;
import services.PersistedDurableJobRepository;
import services.SandboxRepository;
import services.SandboxService;
import services.SlackAlertClient;

/**
 * Configures recurring {@link durablejobs.DurableJob}s and starts the background poller that
 * invokes {@link RecurringJobScheduler} and {@link RecurringDurableJobRunner}.
 *
 * <p>Replicates CiviForm's {@code DurableJobModule}, using a dedicated single-thread {@link
 * ScheduledExecutorService} with {@code scheduleWithFixedDelay} so long-running jobs never block
 * Play's HTTP request threads or pile up overlapping ticks.
 */
public final class DurableJobModule extends AbstractModule {

  private static final ALogger log = Logger.of(DurableJobModule.class);
  static final String POLLER_THREAD_NAME = "durable-job-poller";

  @Override
  protected void configure() {
    bind(Clock.class).toInstance(Clock.systemUTC());
    bind(DurableJobRunnerScheduler.class).asEagerSingleton();
  }

  /**
   * Starts a dedicated single-thread poller at server boot time to schedule and execute recurring
   * durable jobs.
   *
   * <p>Uses {@link ScheduledExecutorService#scheduleWithFixedDelay} on its own daemon thread
   * (distinct from both Play's default request dispatcher and {@link
   * durablejobs.DurableJobExecutionContext}) so a multi-sandbox teardown exceeding the poll
   * interval neither consumes web worker threads nor queues overlapping ticks on {@code runJobs()}.
   *
   * <p>Skipped automatically when {@code durable_jobs.enabled = false} or in Play {@code TEST} mode
   * so isolated unit tests do not attempt background JDBC connections.
   */
  @Singleton
  public static final class DurableJobRunnerScheduler {

    @Inject
    public DurableJobRunnerScheduler(
        Environment environment,
        ApplicationLifecycle applicationLifecycle,
        Config config,
        Provider<SandboxRepository> sandboxRepositoryProvider,
        Provider<RecurringDurableJobRunner> recurringDurableJobRunnerProvider,
        Provider<RecurringJobScheduler> recurringJobSchedulerProvider) {
      this(
          environment,
          applicationLifecycle,
          config,
          sandboxRepositoryProvider,
          recurringDurableJobRunnerProvider,
          recurringJobSchedulerProvider,
          // Jitter initial delay up to 30s matching CiviForm to reduce multi-server lock contention.
          TimeUnit.SECONDS.toMillis(ThreadLocalRandom.current().nextInt(30)),
          TimeUnit.SECONDS.toMillis(
              Math.max(1, config.getInt("durable_jobs.poll_interval_seconds"))));
    }

    @VisibleForTesting
    DurableJobRunnerScheduler(
        Environment environment,
        ApplicationLifecycle applicationLifecycle,
        Config config,
        Provider<SandboxRepository> sandboxRepositoryProvider,
        Provider<RecurringDurableJobRunner> recurringDurableJobRunnerProvider,
        Provider<RecurringJobScheduler> recurringJobSchedulerProvider,
        long initialDelayMillis,
        long pollDelayMillis) {
      boolean enabled =
          !config.hasPath("durable_jobs.enabled") || config.getBoolean("durable_jobs.enabled");
      if (!enabled || environment.isTest()) {
        log.debug(
            "DurableJobRunnerScheduler disabled (enabled={}, isTest={})",
            enabled,
            environment.isTest());
        return;
      }

      AtomicBoolean schemaInitialized = new AtomicBoolean(false);
      ScheduledExecutorService pollerExecutor =
          Executors.newSingleThreadScheduledExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, POLLER_THREAD_NAME);
                thread.setDaemon(true);
                return thread;
              });

      ScheduledFuture<?> scheduledPoll =
          pollerExecutor.scheduleWithFixedDelay(
              () -> {
                try {
                  if (schemaInitialized.compareAndSet(false, true)) {
                    sandboxRepositoryProvider.get().ensureSchemaInitialized();
                  }
                  recurringJobSchedulerProvider.get().scheduleJobs();
                  recurringDurableJobRunnerProvider.get().runJobs();
                } catch (Throwable t) {
                  schemaInitialized.set(false);
                  if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                  }
                  log.error("DurableJobRunnerScheduler poll cycle failed: {}", t.getMessage(), t);
                }
              },
              initialDelayMillis,
              Math.max(1L, pollDelayMillis),
              TimeUnit.MILLISECONDS);

      applicationLifecycle.addStopHook(
          () -> {
            scheduledPoll.cancel(false);
            pollerExecutor.shutdownNow();
            return CompletableFuture.completedFuture(null);
          });

      log.info(
          "DurableJobRunnerScheduler started on dedicated thread '{}' (pollDelay={}ms,"
              + " initialDelay={}ms)",
          POLLER_THREAD_NAME,
          pollDelayMillis,
          initialDelayMillis);
    }
  }

  @Provides
  @Singleton
  @RecurringJobsProviderName
  public DurableJobRegistry provideRecurringDurableJobRegistry(
      Config config,
      Clock clock,
      Provider<SandboxRepository> sandboxRepositoryProvider,
      Provider<SandboxService> sandboxServiceProvider,
      Provider<PersistedDurableJobRepository> persistedDurableJobRepositoryProvider,
      Provider<SlackAlertClient> slackAlertClientProvider) {
    DurableJobRegistry durableJobRegistry = new DurableJobRegistry();
    java.time.Duration destroySandboxTimeout =
        config.hasPath("durable_jobs.destroy_sandbox_timeout_minutes")
            ? java.time.Duration.ofMinutes(
                Math.max(1, config.getInt("durable_jobs.destroy_sandbox_timeout_minutes")))
            : DestroyExpiredSandboxesJob.DEFAULT_PER_SANDBOX_TIMEOUT;

    durableJobRegistry.register(
        DurableJobName.DESTROY_EXPIRED_SANDBOXES,
        JobType.RECURRING,
        persistedDurableJob ->
            new DestroyExpiredSandboxesJob(
                clock,
                sandboxRepositoryProvider.get(),
                sandboxServiceProvider.get(),
                slackAlertClientProvider.get(),
                persistedDurableJob,
                destroySandboxTimeout),
        new RecurringJobExecutionTimeResolvers.Daily2Am());

    durableJobRegistry.register(
        DurableJobName.OLD_JOB_CLEANUP,
        JobType.RECURRING,
        persistedDurableJob ->
            new OldJobCleanupJob(persistedDurableJobRepositoryProvider.get(), persistedDurableJob),
        new RecurringJobExecutionTimeResolvers.Sunday2Am());

    return durableJobRegistry;
  }
}
