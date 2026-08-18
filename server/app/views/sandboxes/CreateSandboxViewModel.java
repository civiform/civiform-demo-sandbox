package views.sandboxes;

import lombok.Builder;
import lombok.Value;
import views.BaseViewModel;

/** View model for the Create Sandbox form page (GET /sandboxes/new). */
@Value
@Builder
public class CreateSandboxViewModel implements BaseViewModel {

  /** Optional pre-filled city name (e.g. from a failed submission). */
  String cityName;

  /** Optional pre-filled admin email. */
  String adminEmail;

  /** Optional pre-filled notes. */
  String notes;

  /** Validation error message to display, or null if none. */
  String error;

  /** Empty view model for a fresh form. */
  public static CreateSandboxViewModel empty() {
    return CreateSandboxViewModel.builder().build();
  }
}
