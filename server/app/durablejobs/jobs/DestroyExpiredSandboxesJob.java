package durablejobs.jobs;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import durablejobs.DurableJob;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CompletionException;
import models.PersistedDurableJobModel;
import models.SandboxInstance;
import org.apache.commons.lang3.exception.ExceptionUtils;
import play.Logger;
import play.Logger.ALogger;
import services.SandboxRepository;
import services.SandboxService;
import services.SlackAlertClient;

/**
 * Daily {@link DurableJob} that finds all sandboxes that expired yesterday or earlier ({@code
 * expires_at < startOfToday}), destroys their infrastructure and database via {@link
 * SandboxService#destroyExpiredSandbox(String)}, and marks their rows {@code DELETED}.
 *
 * <p>Per-sandbox failures are isolated (Option A): if destroying one sandbox fails, it is marked
 * {@code DELETE_FAILED} with {@code destroy_attempts} incremented so it will be retried on the next
 * daily run without aborting sibling teardowns or failing the overall {@link DurableJob}. Once a
 * sandbox fails {@value #MAX_DESTROY_ATTEMPTS} times, {@link SlackAlertClient} notifies Exygy's
 * {@code #civiform-sre-alerts} channel and automatic retries pause for that sandbox.
 */
public final class DestroyExpiredSandboxesJob extends DurableJob {

  private static final ALogger log = Logger.of(DestroyExpiredSandboxesJob.class);

  public static final int MAX_DESTROY_ATTEMPTS = 3;

  private final Clock clock;
  private final SandboxRepository sandboxRepository;
  private final SandboxService sandboxService;
  private final SlackAlertClient slackAlertClient;
  private final PersistedDurableJobModel persistedDurableJobModel;

  public DestroyExpiredSandboxesJob(
      Clock clock,
      SandboxRepository sandboxRepository,
      SandboxService sandboxService,
      SlackAlertClient slackAlertClient,
      PersistedDurableJobModel persistedDurableJobModel) {
    this.clock = checkNotNull(clock);
    this.sandboxRepository = checkNotNull(sandboxRepository);
    this.sandboxService = checkNotNull(sandboxService);
    this.slackAlertClient = checkNotNull(slackAlertClient);
    this.persistedDurableJobModel = checkNotNull(persistedDurableJobModel);
  }

  @Override
  public PersistedDurableJobModel getPersistedDurableJob() {
    return persistedDurableJobModel;
  }

  @Override
  public void run() {
    Instant startOfToday = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
    ImmutableList<SandboxInstance> expired =
        sandboxRepository.findExpiredForDestruction(startOfToday, MAX_DESTROY_ATTEMPTS);

    log.info(
        "DestroyExpiredSandboxesJob starting: found {} expired sandbox(es) eligible for teardown"
            + " (cutoff={})",
        expired.size(),
        startOfToday);

    for (SandboxInstance sandbox : expired) {
      try {
        sandboxService.destroyExpiredSandbox(sandbox.getId()).toCompletableFuture().join();
        log.info(
            "[{}] Expired sandbox destroyed and marked DELETED (expiredAt={})",
            sandbox.getId(),
            sandbox.getExpiresAt());
      } catch (Exception e) {
        Throwable cause =
            e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        String errorMessage =
            cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getMessage()
                : ExceptionUtils.getStackTrace(cause);

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
  }
}
