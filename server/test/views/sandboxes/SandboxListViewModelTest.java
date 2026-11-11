package views.sandboxes;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import java.time.Duration;
import java.time.Instant;
import models.SandboxInstance;
import models.SandboxStatus;
import org.junit.Test;

/** Unit tests for {@link SandboxListViewModel}. */
public class SandboxListViewModelTest {

  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

  private static SandboxInstance sandbox(String id, SandboxStatus status, Instant expiresAt) {
    return SandboxInstance.builder()
        .id(id)
        .cityName("City " + id)
        .status(status)
        .expiresAt(expiresAt)
        .build();
  }

  @Test
  public void of_computesActiveAndExpiringCountsRelativeToProvidedNow() {
    ImmutableList<SandboxInstance> instances =
        ImmutableList.of(
            sandbox("sb-active", SandboxStatus.RUNNING, NOW.plus(Duration.ofDays(20))),
            sandbox("sb-expiring", SandboxStatus.RUNNING, NOW.plus(Duration.ofDays(3))),
            sandbox("sb-expired", SandboxStatus.RUNNING, NOW.minus(Duration.ofDays(2))),
            sandbox("sb-deleted", SandboxStatus.DELETED, NOW.plus(Duration.ofDays(2))));

    SandboxListViewModel model = SandboxListViewModel.of(instances, 7L, NOW);

    assertThat(model.getTotalCount()).isEqualTo(4);
    assertThat(model.getActiveCount()).isEqualTo(2);
    assertThat(model.getExpiringCount()).isEqualTo(1);
    assertThat(model.getSandboxes().get(0).getDaysRemaining()).isEqualTo(20L);
    assertThat(model.getSandboxes().get(1).getDaysRemaining()).isEqualTo(3L);
    assertThat(model.getSandboxes().get(2).getDaysRemaining()).isEqualTo(0L);
  }
}
