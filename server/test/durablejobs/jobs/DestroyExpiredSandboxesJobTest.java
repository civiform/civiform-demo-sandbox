package durablejobs.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import models.JobType;
import models.PersistedDurableJobModel;
import models.SandboxInstance;
import models.SandboxStatus;
import org.junit.Before;
import org.junit.Test;
import services.SandboxRepository;
import services.SandboxService;
import services.SlackAlertClient;

/** Unit tests for {@link DestroyExpiredSandboxesJob}. */
public class DestroyExpiredSandboxesJobTest {

  // Today is 2026-08-15 UTC; startOfToday cutoff must be 2026-08-15T00:00:00Z so any sandbox
  // that expired yesterday (2026-08-14) or earlier is selected.
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-15T02:00:00Z"), ZoneOffset.UTC);
  private static final Instant EXPECTED_CUTOFF = Instant.parse("2026-08-15T00:00:00Z");

  private SandboxRepository sandboxRepository;
  private SandboxService sandboxService;
  private SlackAlertClient slackAlertClient;
  private PersistedDurableJobModel jobModel;

  @Before
  public void setUp() {
    sandboxRepository = mock(SandboxRepository.class);
    sandboxService = mock(SandboxService.class);
    slackAlertClient = mock(SlackAlertClient.class);
    jobModel =
        new PersistedDurableJobModel(
            "DESTROY_EXPIRED_SANDBOXES", JobType.RECURRING, FIXED_CLOCK.instant());
  }

  private DestroyExpiredSandboxesJob newJob() {
    return new DestroyExpiredSandboxesJob(
        FIXED_CLOCK, sandboxRepository, sandboxService, slackAlertClient, jobModel);
  }

  private static SandboxInstance expiredSandbox(String id, SandboxStatus status, int attempts) {
    return SandboxInstance.builder()
        .id(id)
        .cityName("Burlington, VT")
        .subdomain("burlington-" + id)
        .databaseName("sb_" + id.replace("-", "_"))
        .status(status)
        .expiresAt(Instant.parse("2026-08-14T18:00:00Z"))
        .destroyAttempts(attempts)
        .build();
  }

  @Test
  public void run_usesStartOfTodayCutoffAndMaxThreeAttempts() {
    when(sandboxRepository.findExpiredForDestruction(any(), anyInt()))
        .thenReturn(ImmutableList.of());

    newJob().run();

    verify(sandboxRepository)
        .findExpiredForDestruction(
            EXPECTED_CUTOFF, DestroyExpiredSandboxesJob.MAX_DESTROY_ATTEMPTS);
    verify(sandboxService, never()).destroyExpiredSandbox(anyString());
    verify(slackAlertClient, never())
        .sendSandboxDestroyFailureAlert(any(), anyInt(), anyString());
  }

  @Test
  public void run_destroysAllEligibleSandboxes() {
    SandboxInstance sb1 = expiredSandbox("sb-1111", SandboxStatus.RUNNING, 0);
    SandboxInstance sb2 = expiredSandbox("sb-2222", SandboxStatus.DELETE_FAILED, 1);
    when(sandboxRepository.findExpiredForDestruction(EXPECTED_CUTOFF, 3))
        .thenReturn(ImmutableList.of(sb1, sb2));
    when(sandboxService.destroyExpiredSandbox("sb-1111"))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(sandboxService.destroyExpiredSandbox("sb-2222"))
        .thenReturn(CompletableFuture.completedFuture(null));

    newJob().run();

    verify(sandboxService).destroyExpiredSandbox("sb-1111");
    verify(sandboxService).destroyExpiredSandbox("sb-2222");
    verify(sandboxRepository, never()).recordDestroyFailure(anyString(), anyString());
    verify(slackAlertClient, never())
        .sendSandboxDestroyFailureAlert(any(), anyInt(), anyString());
  }

  @Test
  public void run_isolatesPerSandboxFailureSoSiblingsProceedAndDurableJobSucceeds() {
    SandboxInstance failing = expiredSandbox("sb-fail", SandboxStatus.RUNNING, 0);
    SandboxInstance succeeding = expiredSandbox("sb-ok", SandboxStatus.RUNNING, 0);
    when(sandboxRepository.findExpiredForDestruction(EXPECTED_CUTOFF, 3))
        .thenReturn(ImmutableList.of(failing, succeeding));
    when(sandboxService.destroyExpiredSandbox("sb-fail"))
        .thenReturn(
            CompletableFuture.failedFuture(new RuntimeException("S3 state lock contention")));
    when(sandboxService.destroyExpiredSandbox("sb-ok"))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(sandboxRepository.recordDestroyFailure(eq("sb-fail"), anyString())).thenReturn(1);

    // Must NOT throw — Option A isolates per-sandbox failure from the DurableJob sweep.
    newJob().run();

    verify(sandboxRepository)
        .recordDestroyFailure("sb-fail", "S3 state lock contention");
    verify(sandboxService).destroyExpiredSandbox("sb-ok");
    verify(slackAlertClient, never())
        .sendSandboxDestroyFailureAlert(any(), anyInt(), anyString());
  }

  @Test
  public void run_alertsSlackOnlyOnThirdFailureAcrossDailyRuns() {
    SandboxInstance failing = expiredSandbox("sb-stuck", SandboxStatus.RUNNING, 0);
    when(sandboxRepository.findExpiredForDestruction(EXPECTED_CUTOFF, 3))
        .thenReturn(ImmutableList.of(failing));
    when(sandboxService.destroyExpiredSandbox("sb-stuck"))
        .thenReturn(
            CompletableFuture.failedFuture(
                new RuntimeException("Terraform destroy failed: ENI still attached")));
    when(sandboxRepository.recordDestroyFailure(eq("sb-stuck"), anyString()))
        .thenReturn(1)
        .thenReturn(2)
        .thenReturn(3);

    // Day 1: attempt 1 fails -> no Slack alert
    newJob().run();
    verify(slackAlertClient, never())
        .sendSandboxDestroyFailureAlert(any(), anyInt(), anyString());

    // Day 2: attempt 2 fails -> no Slack alert
    newJob().run();
    verify(slackAlertClient, never())
        .sendSandboxDestroyFailureAlert(any(), anyInt(), anyString());

    // Day 3: attempt 3 fails -> alerts #civiform-sre-alerts exactly once
    newJob().run();
    verify(sandboxRepository, times(3)).recordDestroyFailure(eq("sb-stuck"), anyString());
    verify(slackAlertClient, times(1))
        .sendSandboxDestroyFailureAlert(
            eq(failing),
            eq(3),
            eq("Terraform destroy failed: ENI still attached"));
  }

  @Test
  public void getPersistedDurableJob_returnsAssociatedJobModel() {
    assertThat(newJob().getPersistedDurableJob()).isSameAs(jobModel);
    assertThat(newJob().jobName()).isEqualTo("DESTROY_EXPIRED_SANDBOXES");
  }
}
