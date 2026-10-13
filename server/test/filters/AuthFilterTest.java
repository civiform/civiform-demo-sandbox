package filters;

import static org.assertj.core.api.Assertions.assertThat;
import static play.mvc.Http.Status.OK;
import static play.mvc.Http.Status.SEE_OTHER;

import com.google.inject.AbstractModule;
import controllers.AuthController;
import org.junit.Test;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import play.test.WithApplication;
import services.InMemorySandboxService;
import services.SandboxService;

/** Unit tests for {@link AuthFilter}. */
public class AuthFilterTest extends WithApplication {

  @Override
  protected Application provideApplication() {
    return new GuiceApplicationBuilder()
        .overrides(
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(SandboxService.class).to(InMemorySandboxService.class);
              }
            })
        .build();
  }

  @Test
  public void isPublicEndpoint_allowsPublicPaths() {
    assertThat(AuthFilter.isPublicEndpoint("/login")).isTrue();
    assertThat(AuthFilter.isPublicEndpoint("/login/")).isTrue();
    assertThat(AuthFilter.isPublicEndpoint("/login/auth0")).isTrue();
    assertThat(AuthFilter.isPublicEndpoint("/callback")).isTrue();
    assertThat(AuthFilter.isPublicEndpoint("/logout")).isTrue();
    assertThat(AuthFilter.isPublicEndpoint("/health")).isTrue();
    assertThat(AuthFilter.isPublicEndpoint("/ready")).isTrue();
    assertThat(AuthFilter.isPublicEndpoint("/assets/dist/main.css")).isTrue();
    assertThat(AuthFilter.isPublicEndpoint("/sandboxes/sb-123/access")).isTrue();
  }

  @Test
  public void isPublicEndpoint_rejectsProtectedPaths() {
    assertThat(AuthFilter.isPublicEndpoint("/")).isFalse();
    assertThat(AuthFilter.isPublicEndpoint("/sandboxes")).isFalse();
    assertThat(AuthFilter.isPublicEndpoint("/sandboxes/")).isFalse();
    assertThat(AuthFilter.isPublicEndpoint("/sandboxes/sb-123")).isFalse();
    assertThat(AuthFilter.isPublicEndpoint("/sandboxes/sb-123/status")).isFalse();
    assertThat(AuthFilter.isPublicEndpoint("/sandboxes/sb-123/delete")).isFalse();
    assertThat(AuthFilter.isPublicEndpoint("/sandboxes/sb-123/extend")).isFalse();
  }

  @Test
  public void publicHealthEndpoint_accessibleWithoutAuth() {
    Http.RequestBuilder request = Helpers.fakeRequest().method("GET").uri("/health");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(OK);
  }

  @Test
  public void protectedRootEndpoint_withoutAuth_redirectsToLogin() {
    Http.RequestBuilder request = Helpers.fakeRequest().method("GET").uri("/");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(SEE_OTHER);
    assertThat(result.redirectLocation()).contains("/login");
  }

  @Test
  public void protectedRootEndpoint_withAuth_allowsRequest() {
    Http.RequestBuilder request =
        Helpers.fakeRequest()
            .method("GET")
            .uri("/")
            .session(AuthController.SESSION_KEY, "true");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(OK);
  }
}
