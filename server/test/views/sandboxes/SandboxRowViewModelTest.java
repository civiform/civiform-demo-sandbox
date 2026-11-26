package views.sandboxes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import models.SandboxInstance;
import models.SandboxStatus;
import org.junit.Test;

/** Unit tests for {@link SandboxRowViewModel}. */
public class SandboxRowViewModelTest {

  private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

  private static SandboxInstance sandboxExpiringAt(Instant expiresAt) {
    return SandboxInstance.builder()
        .id("sb-test1234")
        .cityName("Burlington, VT")
        .status(SandboxStatus.RUNNING)
        .expiresAt(expiresAt)
        .build();
  }

  @Test
  public void of_activeSandbox_setsAutoDeletionDaysToConfiguredGracePeriod() {
    SandboxInstance active = sandboxExpiringAt(NOW.plus(Duration.ofDays(10)));

    SandboxRowViewModel defaultRow = SandboxRowViewModel.of(active, 7L, NOW);
    assertThat(defaultRow.getDaysRemaining()).isEqualTo(10L);
    assertThat(defaultRow.getAutoDeletionDays()).isEqualTo(7L);

    SandboxRowViewModel customRow = SandboxRowViewModel.of(active, 14L, NOW);
    assertThat(customRow.getAutoDeletionDays()).isEqualTo(14L);
  }

  @Test
  public void of_justExpiredSandbox_showsFullConfiguredGracePeriod() {
    SandboxInstance justExpired = sandboxExpiringAt(NOW.minus(Duration.ofHours(6)));

    SandboxRowViewModel row = SandboxRowViewModel.of(justExpired, 7L, NOW);
    assertThat(row.getDaysRemaining()).isEqualTo(0L);
    assertThat(row.getAutoDeletionDays()).isEqualTo(7L);
  }

  @Test
  public void of_expiredMidGracePeriod_subtractsElapsedDaysFromConfiguredGracePeriod() {
    SandboxInstance expiredThreeDaysAgo = sandboxExpiringAt(NOW.minus(Duration.ofDays(3)));

    assertThat(SandboxRowViewModel.of(expiredThreeDaysAgo, 7L, NOW).getAutoDeletionDays())
        .isEqualTo(4L);
    assertThat(SandboxRowViewModel.of(expiredThreeDaysAgo, 12L, NOW).getAutoDeletionDays())
        .isEqualTo(9L);
  }

  @Test
  public void of_expiredPastGracePeriod_clampsAutoDeletionDaysToZero() {
    SandboxInstance expiredSevenDaysAgo = sandboxExpiringAt(NOW.minus(Duration.ofDays(7)));
    SandboxInstance expiredTenDaysAgo = sandboxExpiringAt(NOW.minus(Duration.ofDays(10)));

    assertThat(SandboxRowViewModel.of(expiredSevenDaysAgo, 7L, NOW).getAutoDeletionDays())
        .isEqualTo(0L);
    assertThat(SandboxRowViewModel.of(expiredTenDaysAgo, 7L, NOW).getAutoDeletionDays())
        .isEqualTo(0L);
  }

  /**
   * Regression test for #101: at 6pm Pacific on Nov 19, it is already Nov 20 in UTC.
   * CiviForm's demo banner counts in America/Los_Angeles, so it still shows 1 day
   * remaining. The dashboard must agree, using Pacific time for "today".
   */
  @Test
  public void of_eveningPacific_matchesCiviFormBannerDayCount() {
    // 6pm Pacific on Nov 19 = 2:00 AM UTC on Nov 20
    Instant eveningPacific = Instant.parse("2026-11-20T02:00:00Z");
    // Sandbox expires on Nov 20 UTC
    SandboxInstance sandbox = sandboxExpiringAt(Instant.parse("2026-11-20T23:59:59Z"));

    SandboxRowViewModel row = SandboxRowViewModel.of(sandbox, 7L, eveningPacific);

    // In UTC "today" would be Nov 20, so days = 0 (expired). But in Pacific time
    // "today" is still Nov 19, so there is 1 day remaining — matching the banner.
    assertThat(row.getDaysRemaining()).isEqualTo(1L);
  }
}
