package views.sandboxes;

import com.google.common.collect.ImmutableList;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import models.SandboxInstance;
import views.BaseViewModel;

/**
 * View model for the sandbox list dashboard.
 * Carries pre-computed stats (totalCount, activeCount, expiringCount)
 * and per-row view models so the template stays logic-free.
 */
@Value
@Builder
public class SandboxListViewModel implements BaseViewModel {

  /** Pre-computed row models (daysRemaining, expiryFormatted, etc.) */
  ImmutableList<SandboxRowViewModel> sandboxes;

  /** Total number of sandboxes. */
  int totalCount;

  /** Number of sandboxes with status RUNNING and daysRemaining > 0. */
  int activeCount;

  /** Number of sandboxes expiring in ≤ 5 days (but not yet expired). */
  int expiringCount;

  /** Days after expiration before a sandbox is automatically deleted. */
  long autodeletionGraceDays;

  /** Convenience factory — builds from a raw sandbox list at the given instant. */
  public static SandboxListViewModel of(
      ImmutableList<SandboxInstance> instances, long autodeletionGraceDays, Instant now) {
    ImmutableList<SandboxRowViewModel> rows = instances.stream()
        .map(instance -> SandboxRowViewModel.of(instance, autodeletionGraceDays, now))
        .collect(ImmutableList.toImmutableList());

    int total    = rows.size();
    int active   = (int) rows.stream()
        .filter(r -> r.getDaysRemaining() > 0
            && "RUNNING".equals(r.getSandbox().getStatus().name())
            && !"DELETED".equals(r.getSandbox().getStatus().name()))
        .count();
    int expiring = (int) rows.stream()
        .filter(r -> r.getDaysRemaining() > 0
            && r.getDaysRemaining() <= 5
            && !"DELETED".equals(r.getSandbox().getStatus().name()))
        .count();

    return SandboxListViewModel.builder()
        .sandboxes(rows)
        .totalCount(total)
        .activeCount(active)
        .expiringCount(expiring)
        .autodeletionGraceDays(autodeletionGraceDays)
        .build();
  }
}
