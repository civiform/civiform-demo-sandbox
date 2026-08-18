package views.sandboxes;

import com.google.common.collect.ImmutableList;
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

  /** Convenience factory — builds from a raw sandbox list. */
  public static SandboxListViewModel of(ImmutableList<SandboxInstance> instances) {
    ImmutableList<SandboxRowViewModel> rows = instances.stream()
        .map(SandboxRowViewModel::of)
        .collect(ImmutableList.toImmutableList());

    int total    = rows.size();
    int active   = (int) rows.stream()
        .filter(r -> r.getDaysRemaining() > 0
            && "RUNNING".equals(r.getSandbox().getStatus().name()))
        .count();
    int expiring = (int) rows.stream()
        .filter(r -> r.getDaysRemaining() > 0 && r.getDaysRemaining() <= 5)
        .count();

    return SandboxListViewModel.builder()
        .sandboxes(rows)
        .totalCount(total)
        .activeCount(active)
        .expiringCount(expiring)
        .build();
  }
}
