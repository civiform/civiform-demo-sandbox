package durablejobs.jobs;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import durablejobs.DurableJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import models.PersistedDurableJobModel;
import models.SandboxInstance;
import org.apache.commons.lang3.exception.ExceptionUtils;
import play.Logger;
import play.Logger.ALogger;
import services.SandboxLifecycleConfig;
import services.SandboxRepository;
import services.SandboxService;
import services.SlackAlertClient;

/**
 * Daily {@link DurableJob} that manages the two-stage post-expiration lifecycle for sandboxes:
 *
 * <ol>
 *   <li><b>Destroy after grace period:</b> Finds all sandboxes past their post-expiration grace
 *       period ({@code expires_at < startOfToday - autodeletionGraceDays}, default {@value
 *       #DEFAULT_AUTODELETION_GRACE_DAYS} days), destroys their infrastructure and database via
 *       {@link SandboxService#destroyExpiredSandbox(String, Instant)}, and marks their rows {@code
 *       DELETED}.
 *   <li><b>Disable on expiration date:</b> When {@code autodeletionGraceDays > 0}, finds all
 *       remaining {@code RUNNING} sandboxes that have expired ({@code expires_at < startOfToday}),
 *       disables external access (e.g. removing ALB listener rules via Terraform or stopping the
 *       Docker container) via {@link SandboxService#disableExpiredSandbox(String, Instant)}, and
 *       marks their rows {@code STOPPED}.
 * </ol>
 *
 * <p>Per-sandbox failures and timeouts are isolated (Option A): if destroying one sandbox throws or
 * exceeds {@code perSandboxTimeout}, it is marked {@code DELETE_FAILED} with {@code
 * destroy_attempts} incremented so it will be retried on the next daily run without blocking
 * sibling teardowns or failing the overall {@link DurableJob}. Once a sandbox fails {@value
 * #MAX_DESTROY_ATTEMPTS} times, {@link SlackAlertClient} notifies Exygy's {@code
 * #civiform-sre-alerts} channel and automatic retries pause for that sandbox. If disabling an
 * expired sandbox fails, its status remains {@code RUNNING} and the error is logged so the next
 * daily run retries automatically without consuming {@code destroy_attempts}.
 */
public final class DestroyExpiredSandboxesJob extends DurableJob {

  private static final ALogger log = Logger.of(DestroyExpiredSandboxesJob.class);

  public static final int MAX_DESTROY_ATTEMPTS = 3;
  public static final Duration DEFAULT_PER_SANDBOX_TIMEOUT = Duration.ofMinutes(20);
  public static final long DEFAULT_AUTODELETION_GRACE_DAYS =
      SandboxLifecycleConfig.DEFAULT_AUTODELETION_GRACE_DAYS;

  private final Clock clock;
  private final SandboxRepository sandboxRepository;
  private final SandboxService sandboxService;
  private final SlackAlertClient slackAlertClient;
  private final PersistedDurableJobModel persistedDurableJobModel;
  private final Duration perSandboxTimeout;
  private final long autodeletionGraceDays;

  public DestroyExpiredSandboxesJob(
      Clock clock,
      SandboxRepository sandboxRepository,
      SandboxService sandboxService,
      SlackAlertClient slackAlertClient,
      PersistedDurableJobModel persistedDurableJobModel) {
    this(
        clock,
        sandboxRepository,
        sandboxService,
        slackAlertClient,
        persistedDurableJobModel,
        DEFAULT_PER_SANDBOX_TIMEOUT,
        DEFAULT_AUTODELETION_GRACE_DAYS);
  }

  public DestroyExpiredSandboxesJob(
      Clock clock,
      SandboxRepository sandboxRepository,
      SandboxService sandboxService,
      SlackAlertClient slackAlertClient,
      PersistedDurableJobModel persistedDurableJobModel,
      Duration perSandboxTimeout) {
    this(
        clock,
        sandboxRepository,
        sandboxService,
        slackAlertClient,
        persistedDurableJobModel,
        perSandboxTimeout,
        DEFAULT_AUTODELETION_GRACE_DAYS);
  }

  public DestroyExpiredSandboxesJob(
      Clock clock,
      SandboxRepository sandboxRepository,
      SandboxService sandboxService,
      SlackAlertClient slackAlertClient,
      PersistedDurableJobModel persistedDurableJobModel,
      Duration perSandboxTimeout,
      long autodeletionGraceDays) {
    checkArgument(autodeletionGraceDays >= 0, "autodeletionGraceDays must be >= 0");
    this.clock = checkNotNull(clock);
    this.sandboxRepository = checkNotNull(sandboxRepository);
    this.sandboxService = checkNotNull(sandboxService);
    this.slackAlertClient = checkNotNull(slackAlertClient);
    this.persistedDurableJobModel = checkNotNull(persistedDurableJobModel);
    this.perSandboxTimeout = checkNotNull(perSandboxTimeout);
    this.autodeletionGraceDays = autodeletionGraceDays;
  }

  @Override
  public PersistedDurableJobModel getPersistedDurableJob() {
    return persistedDurableJobModel;
  }

  @Override
  public void run() {
    Instant startOfToday = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
    Instant destroyCutoff =
        LocalDate.now(clock)
            .minusDays(autodeletionGraceDays)
            .atStartOfDay(clock.getZone())
            .toInstant();

    runDestroyPhase(destroyCutoff);
    if (autodeletionGraceDays > 0) {
      runDisablePhase(startOfToday);
    }
  }

  private void runDestroyPhase(Instant cutoff) {
    ImmutableList<SandboxInstance> expired =
        sandboxRepository.findExpiredForDestruction(cutoff, MAX_DESTROY_ATTEMPTS);

    log.info(
        "DestroyExpiredSandboxesJob starting destroy phase: found {} expired sandbox(es) eligible"
            + " for teardown (cutoff={}, graceDays={}, perSandboxTimeout={})",
        expired.size(),
        cutoff,
        autodeletionGraceDays,
        perSandboxTimeout);

    for (SandboxInstance sandbox : expired) {
      CompletableFuture<Boolean> future = null;
      try {
        future =
            sandboxService.destroyExpiredSandbox(sandbox.getId(), cutoff).toCompletableFuture();
        boolean destroyed =
            Boolean.TRUE.equals(future.get(perSandboxTimeout.toMillis(), TimeUnit.MILLISECONDS));
        if (destroyed) {
          log.info(
              "[{}] Expired sandbox destroyed and marked DELETED (expiredAt={})",
              sandbox.getId(),
              sandbox.getExpiresAt());
        } else {
          log.info(
              "[{}] Skipped expired-sandbox teardown (no longer eligible at execution time)",
              sandbox.getId());
        }
      } catch (TimeoutException e) {
        future.cancel(true);
        String timeoutMsg =
            String.format(
                "Timed out after %ds waiting for expired-sandbox teardown",
                perSandboxTimeout.toSeconds());
        recordFailureAndAlertIfNeeded(sandbox, timeoutMsg, e);
      } catch (InterruptedException e) {
        if (future != null) {
          future.cancel(true);
        }
        Thread.currentThread().interrupt();
        throw new RuntimeException(
            "Interrupted while destroying expired sandbox " + sandbox.getId(), e);
      } catch (Exception e) {
        Throwable cause =
            (e instanceof ExecutionException || e instanceof CompletionException)
                    && e.getCause() != null
                ? e.getCause()
                : e;
        String errorMessage =
            cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getMessage()
                : ExceptionUtils.getStackTrace(cause);
        recordFailureAndAlertIfNeeded(sandbox, errorMessage, cause);
      }
    }
  }

  private void runDisablePhase(Instant cutoff) {
    ImmutableList<SandboxInstance> toDisable =
        sandboxRepository.findExpiredForDisabling(cutoff);

    log.info(
        "DestroyExpiredSandboxesJob starting disable phase: found {} expired sandbox(es) eligible"
            + " for disabling (cutoff={}, perSandboxTimeout={})",
        toDisable.size(),
        cutoff,
        perSandboxTimeout);

    for (SandboxInstance sandbox : toDisable) {
      CompletableFuture<Boolean> future = null;
      try {
        future =
            sandboxService.disableExpiredSandbox(sandbox.getId(), cutoff).toCompletableFuture();
        boolean disabled =
            Boolean.TRUE.equals(future.get(perSandboxTimeout.toMillis(), TimeUnit.MILLISECONDS));
        if (disabled) {
          log.info(
              "[{}] Expired sandbox disabled and marked STOPPED (expiredAt={})",
              sandbox.getId(),
              sandbox.getExpiresAt());
        } else {
          log.info(
              "[{}] Skipped expired-sandbox disable (no longer eligible at execution time)",
              sandbox.getId());
        }
      } catch (TimeoutException e) {
        future.cancel(true);
        String timeoutMsg =
            String.format(
                "Timed out after %ds waiting for expired-sandbox disable",
                perSandboxTimeout.toSeconds());
        log.error(
            "[{}] Failed to disable expired sandbox (will retry on next run): {}",
            sandbox.getId(),
            timeoutMsg,
            e);
      } catch (InterruptedException e) {
        if (future != null) {
          future.cancel(true);
        }
        Thread.currentThread().interrupt();
        throw new RuntimeException(
            "Interrupted while disabling expired sandbox " + sandbox.getId(), e);
      } catch (Exception e) {
        Throwable cause =
            (e instanceof ExecutionException || e instanceof CompletionException)
                    && e.getCause() != null
                ? e.getCause()
                : e;
        String errorMessage =
            cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getMessage()
                : ExceptionUtils.getStackTrace(cause);
        log.error(
            "[{}] Failed to disable expired sandbox (will retry on next run): {}",
            sandbox.getId(),
            errorMessage,
            cause);
      }
    }
  }

  private void recordFailureAndAlertIfNeeded(
      SandboxInstance sandbox, String errorMessage, Throwable cause) {
    int attempts = sandboxRepository.recordDestroyFailure(sandbox.getId(), errorMessage);
    log.error(
        "[{}] Failed to destroy expired sandbox (attempt {}/{}): {}",
        sandbox.getId(),
        attempts,
        MAX_DESTROY_ATTEMPTS,
        errorMessage,
        cause);

    if (attempts >= MAX_DESTROY_ATTEMPTS) {
      slackAlertClient.sendSandboxDestroyFailureAlert(sandbox, attempts, errorMessage);
    }
  }
}
