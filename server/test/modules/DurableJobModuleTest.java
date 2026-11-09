package modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import durablejobs.RecurringDurableJobRunner;
import durablejobs.RecurringJobScheduler;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import play.Environment;
import play.inject.ApplicationLifecycle;
import services.SandboxRepository;

/** Unit tests for {@link DurableJobModule.DurableJobRunnerScheduler}. */
public class DurableJobModuleTest {

  @Test
  public void poller_runsOnDedicatedThreadAndWaitsForLongRunBeforeNextTick() throws Exception {
    Environment environment = mock(Environment.class);
    when(environment.isTest()).thenReturn(false);

    ApplicationLifecycle lifecycle = mock(ApplicationLifecycle.class);
    SandboxRepository sandboxRepository = mock(SandboxRepository.class);
    RecurringJobScheduler jobScheduler = mock(RecurringJobScheduler.class);
    RecurringDurableJobRunner jobRunner = mock(RecurringDurableJobRunner.class);

    CountDownLatch firstRunEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstRun = new CountDownLatch(1);
    CountDownLatch secondRunEntered = new CountDownLatch(1);
    AtomicReference<String> observedThreadName = new AtomicReference<>();
    AtomicInteger scheduleCalls = new AtomicInteger(0);
    AtomicInteger runCalls = new AtomicInteger(0);

    doAnswer(
            invocation -> {
              scheduleCalls.incrementAndGet();
              return null;
            })
        .when(jobScheduler)
        .scheduleJobs();

    doAnswer(
            invocation -> {
              int callNumber = runCalls.incrementAndGet();
              observedThreadName.set(Thread.currentThread().getName());
              if (callNumber == 1) {
                firstRunEntered.countDown();
                assertThat(releaseFirstRun.await(5, TimeUnit.SECONDS)).isTrue();
              } else if (callNumber == 2) {
                secondRunEntered.countDown();
              }
              return null;
            })
        .when(jobRunner)
        .runJobs();

    Config config =
        ConfigFactory.parseMap(
            Map.of(
                "durable_jobs.enabled", true,
                "durable_jobs.poll_interval_seconds", 1));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Callable<CompletionStage<?>>> stopHookCaptor =
        ArgumentCaptor.forClass(Callable.class);

    new DurableJobModule.DurableJobRunnerScheduler(
        environment,
        lifecycle,
        config,
        () -> sandboxRepository,
        () -> jobRunner,
        () -> jobScheduler,
        0L,
        10L);

    verify(lifecycle).addStopHook(stopHookCaptor.capture());
    try {
      assertThat(firstRunEntered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(observedThreadName.get()).isEqualTo(DurableJobModule.POLLER_THREAD_NAME);

      // Hold the first run longer than several 10ms poll intervals: with scheduleWithFixedDelay on
      // a single-thread executor, no overlapping tick may invoke scheduleJobs() while runJobs() is
      // still executing.
      Thread.sleep(50L);
      assertThat(scheduleCalls.get()).isEqualTo(1);
      assertThat(runCalls.get()).isEqualTo(1);

      releaseFirstRun.countDown();
      assertThat(secondRunEntered.await(5, TimeUnit.SECONDS)).isTrue();
      // Schema initialization runs only once when the poller succeeds.
      verify(sandboxRepository, times(1)).ensureSchemaInitialized();
    } finally {
      stopHookCaptor.getValue().call().toCompletableFuture().join();
    }
  }

  @Test
  public void poller_doesNotStartInTestModeOrWhenDisabled() {
    ApplicationLifecycle lifecycle = mock(ApplicationLifecycle.class);
    SandboxRepository sandboxRepository = mock(SandboxRepository.class);
    RecurringJobScheduler jobScheduler = mock(RecurringJobScheduler.class);
    RecurringDurableJobRunner jobRunner = mock(RecurringDurableJobRunner.class);

    Environment testEnv = mock(Environment.class);
    when(testEnv.isTest()).thenReturn(true);

    new DurableJobModule.DurableJobRunnerScheduler(
        testEnv,
        lifecycle,
        ConfigFactory.parseMap(Map.of("durable_jobs.enabled", true)),
        () -> sandboxRepository,
        () -> jobRunner,
        () -> jobScheduler,
        0L,
        10L);

    Environment prodEnv = mock(Environment.class);
    when(prodEnv.isTest()).thenReturn(false);

    new DurableJobModule.DurableJobRunnerScheduler(
        prodEnv,
        lifecycle,
        ConfigFactory.parseMap(Map.of("durable_jobs.enabled", false)),
        () -> sandboxRepository,
        () -> jobRunner,
        () -> jobScheduler,
        0L,
        10L);

    verify(lifecycle, never()).addStopHook(any());
  }

  @Test
  public void provideRecurringDurableJobRegistry_wiresConfiguredAutodeletionGraceDays()
      throws Exception {
    java.time.Clock fixedClock =
        java.time.Clock.fixed(
            java.time.Instant.parse("2026-08-20T02:00:00Z"), java.time.ZoneOffset.UTC);
    SandboxRepository sandboxRepository = mock(SandboxRepository.class);
    when(sandboxRepository.findExpiredForDestruction(any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(com.google.common.collect.ImmutableList.of());
    services.SandboxService sandboxService = mock(services.SandboxService.class);
    services.PersistedDurableJobRepository jobRepo =
        mock(services.PersistedDurableJobRepository.class);
    services.SlackAlertClient slackClient = mock(services.SlackAlertClient.class);

    Config config = ConfigFactory.empty();
    durablejobs.DurableJobRegistry registry =
        new DurableJobModule()
            .provideRecurringDurableJobRegistry(
                config,
                fixedClock,
                () -> sandboxRepository,
                () -> sandboxService,
                () -> jobRepo,
                () -> slackClient,
                services.SandboxLifecycleConfig.withGraceDays(10));

    models.PersistedDurableJobModel jobModel =
        new models.PersistedDurableJobModel(
            "DESTROY_EXPIRED_SANDBOXES", models.JobType.RECURRING, fixedClock.instant());
    registry
        .get(durablejobs.DurableJobName.DESTROY_EXPIRED_SANDBOXES)
        .getFactory()
        .create(jobModel)
        .run();

    verify(sandboxRepository)
        .findExpiredForDestruction(
            java.time.Instant.parse("2026-08-10T00:00:00Z"),
            durablejobs.jobs.DestroyExpiredSandboxesJob.MAX_DESTROY_ATTEMPTS);
  }
}
