package views.sandboxes;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

  private static final DateTimeFormatter ISO_DATE_FMT =
      DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

  /** The underlying sandbox. */
  SandboxInstance sandbox;

  /** Days until expiry (0 if expired). */
  long daysRemaining;

  /** Human-readable expiry date, e.g. "Sep 13, 2026". */
  String expiryFormatted;

  /** ISO-8601 expiry date (YYYY-MM-DD in UTC) for pre-populating date inputs. */
  String expiryIsoDate;

  /** City name shortcut (avoids sandbox.getCityName() in template). */
  String cityName;

  /** Whether the sandbox has been soft-deleted. */
  boolean deleted;

  /** Human-readable deletion date, e.g. "Oct 1, 2026". Null if not deleted. */
  String deletedFormatted;

  /** Whether the sandbox is still provisioning (not yet RUNNING). */
  boolean provisioning;

  /** Days until autodeletion (only meaningful when expired). 0 = imminent. */
  long autoDeletionDays;

  public static SandboxRowViewModel of(
      SandboxInstance sandbox, long autodeletionGraceDays, Instant now) {
    long clampedGraceDays = Math.max(0L, autodeletionGraceDays);
    long days = ChronoUnit.DAYS.between(now, sandbox.getExpiresAt());
    boolean isDeleted = sandbox.getStatus() == SandboxStatus.DELETED;
    String deletedFmt = isDeleted && sandbox.getDeletedAt() != null
        ? DISPLAY_FMT.format(sandbox.getDeletedAt())
        : null;
    boolean isProv = sandbox.getStatus() == SandboxStatus.PROVISIONING;
    long autoDeleteDays = days < 0
        ? Math.max(0L, clampedGraceDays + days)
        : clampedGraceDays;
    return new SandboxRowViewModel(
        sandbox,
        Math.max(days, 0),
        DISPLAY_FMT.format(sandbox.getExpiresAt()),
        ISO_DATE_FMT.format(sandbox.getExpiresAt()),
        sandbox.getCityName(),
        isDeleted,
        deletedFmt,
        isProv,
        autoDeleteDays);
  }
}
