package views.sandboxes;

import lombok.Builder;
import lombok.Value;
import views.BaseViewModel;

/**
 * View model for the demo wrapper page that embeds a live CiviForm sandbox
 * inside an iframe with a persistent demo banner and role switcher.
 */
@Value
@Builder
public class DemoWrapperViewModel implements BaseViewModel {
  /** Human-readable city name (e.g. "Santa Cruz, CA"). */
  String cityName;

  /** Sandbox ID (e.g. "sb-a1b2c3d4"). */
  String sandboxId;

  /** Direct URL to the CiviForm instance (e.g. "http://localhost:10001"). */
  String sandboxUrl;

  /** Number of days remaining until sandbox expires. */
  long daysRemaining;

  /** Whether the sandbox is expired (daysRemaining <= 0). */
  boolean expired;
}
