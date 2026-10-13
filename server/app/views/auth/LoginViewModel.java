package views.auth;

import lombok.Builder;
import lombok.Value;
import views.BaseViewModel;

/** View model for the {@code GET /login} and {@code POST /login} pages. */
@Value
@Builder
public class LoginViewModel implements BaseViewModel {
  /** Pre-fills the email field after a failed login attempt. */
  String email;
  /** Non-null when the previously submitted credentials or OAuth flow failed. */
  String error;
  /**
   * True when Auth0 OIDC is active; false when the development email/password form is active.
   * Only one authentication method is active at a time.
   */
  @Builder.Default
  boolean auth0Enabled = false;
}
