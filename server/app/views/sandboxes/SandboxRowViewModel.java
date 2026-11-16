package views.sandboxes;

import java.time.Instant;
import java.time.LocalDate;
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
    // Use UTC calendar dates (not raw Instant durations) so the dashboard's
    // "days remaining" matches CiviForm's demo banner, which compares the
    // DEMO_BANNER_EXPIRATION_DATE (a YYYY-MM-DD string in UTC) against LocalDate.now().
    LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
    LocalDate expiryDate = sandbox.getExpiresAt().atZone(ZoneOffset.UTC).toLocalDate();
    long days = ChronoUnit.DAYS.between(today, expiryDate);
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
