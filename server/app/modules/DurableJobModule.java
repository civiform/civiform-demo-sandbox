package modules;

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
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import models.JobType;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.Cancellable;
import play.Environment;
import play.Logger;
import play.Logger.ALogger;
import play.inject.ApplicationLifecycle;
import scala.concurrent.ExecutionContext;
import services.PersistedDurableJobRepository;
import services.SandboxRepository;
import services.SandboxService;
import services.SlackAlertClient;

/**
 * Configures recurring {@link durablejobs.DurableJob}s and starts the periodic Pekko scheduler that
 * invokes {@link RecurringJobScheduler} and {@link RecurringDurableJobRunner}.
 *
 * <p>Replicates CiviForm's {@code DurableJobModule}.
 */
public final class DurableJobModule extends AbstractModule {

  private static final ALogger log = Logger.of(DurableJobModule.class);

  @Override
  protected void configure() {
    bind(Clock.class).toInstance(Clock.systemUTC());
    bind(DurableJobRunnerScheduler.class).asEagerSingleton();
  }

  /**
   * Starts the background Pekko scheduler at server boot time to poll for recurring durable jobs.
   *
   * <p>Skipped automatically when {@code durable_jobs.enabled = false} or in Play {@code TEST} mode
   * so isolated unit tests do not attempt background JDBC connections.
   */
  @Singleton
  public static final class DurableJobRunnerScheduler {

    @Inject
    public DurableJobRunnerScheduler(
        Environment environment,
        ActorSystem actorSystem,
        ApplicationLifecycle applicationLifecycle,
        Config config,
        ExecutionContext scalaExecutionContext,
        Provider<SandboxRepository> sandboxRepositoryProvider,
        Provider<RecurringDurableJobRunner> recurringDurableJobRunnerProvider,
        Provider<RecurringJobScheduler> recurringJobSchedulerProvider) {
      boolean enabled =
          !config.hasPath("durable_jobs.enabled") || config.getBoolean("durable_jobs.enabled");
      if (!enabled || environment.isTest()) {
        log.debug(
            "DurableJobRunnerScheduler disabled (enabled={}, isTest={})",
            enabled,
            environment.isTest());
        return;
      }

      int pollIntervalSeconds = Math.max(1, config.getInt("durable_jobs.poll_interval_seconds"));
      AtomicBoolean schemaInitialized = new AtomicBoolean(false);

      // Jitter initial delay up to 30s matching CiviForm to reduce multi-server lock contention.
      Duration initialDelay = Duration.ofSeconds(ThreadLocalRandom.current().nextInt(30));
      Duration interval = Duration.ofSeconds(pollIntervalSeconds);

      Cancellable cancellable =
          actorSystem
              .scheduler()
              .scheduleAtFixedRate(
                  initialDelay,
                  interval,
                  () -> {
                    try {
                      if (schemaInitialized.compareAndSet(false, true)) {
                        sandboxRepositoryProvider.get().ensureSchemaInitialized();
                      }
                      recurringJobSchedulerProvider.get().scheduleJobs();
                      recurringDurableJobRunnerProvider.get().runJobs();
                    } catch (Throwable t) {
                      schemaInitialized.set(false);
                      log.error("DurableJobRunnerScheduler poll cycle failed: {}", t.getMessage(), t);
                    }
                  },
                  scalaExecutionContext);

      applicationLifecycle.addStopHook(
          () -> {
            cancellable.cancel();
            return CompletableFuture.completedFuture(null);
          });

      log.info(
          "DurableJobRunnerScheduler started (pollInterval={}s, initialDelay={}s)",
          pollIntervalSeconds,
          initialDelay.toSeconds());
    }
  }

  @Provides
  @Singleton
  @RecurringJobsProviderName
  public DurableJobRegistry provideRecurringDurableJobRegistry(
      Clock clock,
      Provider<SandboxRepository> sandboxRepositoryProvider,
      Provider<SandboxService> sandboxServiceProvider,
      Provider<PersistedDurableJobRepository> persistedDurableJobRepositoryProvider,
      Provider<SlackAlertClient> slackAlertClientProvider) {
    DurableJobRegistry durableJobRegistry = new DurableJobRegistry();

    durableJobRegistry.register(
        DurableJobName.DESTROY_EXPIRED_SANDBOXES,
        JobType.RECURRING,
        persistedDurableJob ->
            new DestroyExpiredSandboxesJob(
                clock,
                sandboxRepositoryProvider.get(),
                sandboxServiceProvider.get(),
                slackAlertClientProvider.get(),
                persistedDurableJob),
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
