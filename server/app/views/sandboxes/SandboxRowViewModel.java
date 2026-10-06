package views.sandboxes;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.Value;
import models.SandboxInstance;
import models.SandboxStatus;

/**
 * Per-row view model for the sandbox list table.
 * Pre-computes display values (days remaining, formatted expiry, display status)
 * so the Thymeleaf template stays logic-free.
 */
@Value
public class SandboxRowViewModel {

  private static final DateTimeFormatter DISPLAY_FMT =
      DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault());

  /** The underlying sandbox. */
  SandboxInstance sandbox;

  /** Days until expiry (0 if expired). */
  long daysRemaining;

  /** Human-readable expiry date, e.g. "Sep 13, 2026". */
  String expiryFormatted;

  /** City name shortcut (avoids sandbox.getCityName() in template). */
  String cityName;

  /** Whether the sandbox has been soft-deleted. */
  boolean deleted;

  /** Human-readable deletion date, e.g. "Oct 1, 2026". Null if not deleted. */
  String deletedFormatted;

  /** Whether the sandbox is still provisioning (not yet RUNNING). */
  boolean provisioning;

  public static SandboxRowViewModel of(SandboxInstance sandbox) {
    long days = ChronoUnit.DAYS.between(Instant.now(), sandbox.getExpiresAt());
    boolean isDeleted = sandbox.getStatus() == SandboxStatus.DELETED;
    String deletedFmt = isDeleted && sandbox.getDeletedAt() != null
        ? DISPLAY_FMT.format(sandbox.getDeletedAt())
        : null;
    boolean isProv = sandbox.getStatus() == SandboxStatus.PROVISIONING;
    return new SandboxRowViewModel(
        sandbox,
        Math.max(days, 0),
        DISPLAY_FMT.format(sandbox.getExpiresAt()),
        sandbox.getCityName(),
        isDeleted,
        deletedFmt,
        isProv);
  }
}
