package controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.NOT_FOUND;
import static play.mvc.Http.Status.OK;
import static play.mvc.Http.Status.SEE_OTHER;
import static play.test.Helpers.contentAsString;

import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import models.SandboxInstance;
import models.SandboxStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import play.test.WithApplication;
import services.SandboxService;

/**
 * Unit tests for {@link SandboxController}.
 *
 * <p>Strategy: bind a Mockito mock of {@link SandboxService} via Guice so the full Play
 * routing + controller wiring is exercised without any real database or Docker socket.
 *
 * <p>Uses {@link Helpers#route(Application, Http.RequestBuilder)} (Play 3.0 API) to drive
 * requests through the full router. {@code invokeWithContext} was removed in Play 3.0.
 *
 * <p>Sprint 1 required tests (from pr-testing-standards.md):
 * - POST /sandboxes → 303 redirect to /sandboxes/:id
 * - POST /sandboxes/:id/access → redirect to sandbox URL on correct PIN
 * - POST /sandboxes/:id/access → 400 on wrong PIN
 * - GET /sandboxes/:id/status → returns HTML fragment (not full page)
 */
public class SandboxControllerTest extends WithApplication {

  private SandboxService sandboxService;

  @Override
  protected Application provideApplication() {
    sandboxService = mock(SandboxService.class);
    return new GuiceApplicationBuilder()
        .overrides(new AbstractModule() {
          @Override
          protected void configure() {
            bind(SandboxService.class).toInstance(sandboxService);
          }
        })
        .build();
  }

  // ── POST /sandboxes — create sandbox ──────────────────────────────────────

  @Test
  public void create_redirectsToSandboxDetailPage() {
    SandboxInstance created = makeSandbox("sb-abc123", SandboxStatus.PROVISIONING);
    when(sandboxService.createSandbox(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(created));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("POST")
        .uri("/sandboxes")
        .bodyForm(com.google.common.collect.ImmutableMap.of(
            "cityName", "Burlington, VT",
            "version", "latest",
            "adminEmail", "admin@test.com",
            "notes", ""));

    Result result = Helpers.route(app, request);

    // Must be a 303 redirect, not 200 or redirect to home
    assertThat(result.status()).isEqualTo(SEE_OTHER);
    assertThat(result.redirectLocation()).isPresent();
    assertThat(result.redirectLocation().get()).isEqualTo("/sandboxes/sb-abc123");
  }

  @Test
  public void create_notRedirectingToHome() {
    SandboxInstance created = makeSandbox("sb-xyz999", SandboxStatus.PROVISIONING);
    when(sandboxService.createSandbox(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(created));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("POST")
        .uri("/sandboxes")
        .bodyForm(com.google.common.collect.ImmutableMap.of(
            "cityName", "Portland, OR",
            "version", "latest",
            "adminEmail", "",
            "notes", ""));

    Result result = Helpers.route(app, request);

    assertThat(result.redirectLocation().orElse("")).isNotEqualTo("/");
    assertThat(result.redirectLocation().orElse("")).isNotEqualTo("/sandboxes");
  }

  // ── POST /sandboxes/:id/access — PIN validation ───────────────────────────

  @Test
  public void validateAccess_correctPin_redirectsToSandboxUrl() {
    SandboxInstance sandbox = makeSandbox("sb-pin1", SandboxStatus.RUNNING);
    when(sandboxService.validatePin("sb-pin1", "482917"))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(sandbox)));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("POST")
        .uri("/sandboxes/sb-pin1/access")
        .bodyForm(com.google.common.collect.ImmutableMap.of("pin", "482917"));

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(SEE_OTHER);
    assertThat(result.redirectLocation()).isPresent();
    assertThat(result.redirectLocation().get()).isEqualTo("http://localhost:10001");
  }

  @Test
  public void validateAccess_correctPin_setsHttpOnlyAccessCookie() {
    SandboxInstance sandbox = makeSandbox("sb-pin-cookie", SandboxStatus.RUNNING);
    when(sandboxService.validatePin("sb-pin-cookie", "482917"))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(sandbox)));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("POST")
        .uri("/sandboxes/sb-pin-cookie/access")
        .bodyForm(com.google.common.collect.ImmutableMap.of("pin", "482917"));

    Result result = Helpers.route(app, request);

    // Cookie must be present, HTTP-only, named correctly
    Optional<play.mvc.Http.Cookie> cookie = result.cookie("sb_access_sb_pin_cookie");
    assertThat(cookie).isPresent();
    assertThat(cookie.get().value()).isEqualTo("granted");
    assertThat(cookie.get().httpOnly()).isTrue();
    assertThat(cookie.get().path()).isEqualTo("/sandboxes/sb-pin-cookie");
    // 30 days in seconds = 2592000
    assertThat(cookie.get().maxAge()).isEqualTo(2_592_000);
  }

  @Test
  public void validateAccess_wrongPin_doesNotSetCookie() {
    when(sandboxService.validatePin("sb-pin2", "000000"))
        .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

    SandboxInstance sandbox = makeSandbox("sb-pin2", SandboxStatus.RUNNING);
    when(sandboxService.getSandbox("sb-pin2"))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(sandbox)));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("POST")
        .uri("/sandboxes/sb-pin2/access")
        .bodyForm(com.google.common.collect.ImmutableMap.of("pin", "000000"));

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(BAD_REQUEST);
    assertThat(contentAsString(result)).contains("Incorrect PIN");
    // No cookie on wrong PIN
    assertThat(result.cookie("sb_access_sb_pin2")).isEmpty();
  }

  @Test
  public void validateAccess_emptyPin_returns400() {
    when(sandboxService.validatePin("sb-pin3", ""))
        .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

    SandboxInstance sandbox = makeSandbox("sb-pin3", SandboxStatus.RUNNING);
    when(sandboxService.getSandbox("sb-pin3"))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(sandbox)));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("POST")
        .uri("/sandboxes/sb-pin3/access")
        .bodyForm(com.google.common.collect.ImmutableMap.of("pin", ""));

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(BAD_REQUEST);
    assertThat(result.cookie("sb_access_sb_pin3")).isEmpty();
  }

  @Test
  public void pinGate_withValidCookie_bypassesPinFormAndRedirects() {
    SandboxInstance sandbox = makeSandbox("sb-bypass", SandboxStatus.RUNNING);
    when(sandboxService.getSandbox("sb-bypass"))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(sandbox)));

    // Simulate a returning prospect who already has the access cookie
    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/sandboxes/sb-bypass/access")
        .cookie(play.mvc.Http.Cookie.builder("sb_access_sb_bypass", "granted")
            .withHttpOnly(true)
            .withPath("/sandboxes/sb-bypass")
            .build());

    Result result = Helpers.route(app, request);

    // Cookie present → skip form, redirect straight to CiviForm URL
    assertThat(result.status()).isEqualTo(SEE_OTHER);
    assertThat(result.redirectLocation().orElse("")).isEqualTo("http://localhost:10001");
  }

  @Test
  public void pinGate_withoutCookie_showsPinForm() {
    SandboxInstance sandbox = makeSandbox("sb-nobypass", SandboxStatus.RUNNING);
    when(sandboxService.getSandbox("sb-nobypass"))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(sandbox)));

    // No cookie — normal flow, show the PIN form
    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/sandboxes/sb-nobypass/access");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(OK);
    assertThat(contentAsString(result)).containsIgnoringCase("Burlington");
  }

  // ── GET /sandboxes/:id/status — HTMX partial fragment ────────────────────

  @Test
  public void statusFragment_returnsHtmlFragment_notFullPage() {
    SandboxInstance sandbox = makeSandbox("sb-status1", SandboxStatus.PROVISIONING);
    when(sandboxService.getSandbox("sb-status1"))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(sandbox)));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/sandboxes/sb-status1/status");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(OK);
    String body = contentAsString(result);
    // Must be a fragment — no full HTML skeleton
    assertThat(body).doesNotContainIgnoringCase("<!DOCTYPE");
    assertThat(body).doesNotContainIgnoringCase("<html");
    // Must contain the status text
    assertThat(body).containsIgnoringCase("PROVISIONING");
  }

  @Test
  public void statusFragment_provisioningStatus_containsHtmxPollingAttributes() {
    SandboxInstance sandbox = makeSandbox("sb-status2", SandboxStatus.PROVISIONING);
    when(sandboxService.getSandbox("sb-status2"))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(sandbox)));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/sandboxes/sb-status2/status");

    Result result = Helpers.route(app, request);

    String body = contentAsString(result);
    // While provisioning: HTMX polling attributes must be present
    assertThat(body).contains("hx-get");
    assertThat(body).contains("hx-trigger");
    assertThat(body).contains("every 3s");
  }

  @Test
  public void statusFragment_runningStatus_containsRedirectScript() {
    SandboxInstance sandbox = makeSandbox("sb-status3", SandboxStatus.RUNNING);
    when(sandboxService.getSandbox("sb-status3"))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(sandbox)));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/sandboxes/sb-status3/status");

    Result result = Helpers.route(app, request);

    String body = contentAsString(result);
    // When RUNNING: no HTMX polling (done), but redirect script injected
    assertThat(body).doesNotContain("every 3s");
    assertThat(body).contains("window.location.href");
    assertThat(body).contains("/sandboxes/sb-status3");
  }

  @Test
  public void statusFragment_runningStatus_noHtmxPollingAttributes() {
    SandboxInstance sandbox = makeSandbox("sb-status4", SandboxStatus.RUNNING);
    when(sandboxService.getSandbox("sb-status4"))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(sandbox)));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/sandboxes/sb-status4/status");

    Result result = Helpers.route(app, request);

    String body = contentAsString(result);
    // Polling must stop once RUNNING — no hx-trigger
    assertThat(body).doesNotContain("hx-trigger");
  }

  @Test
  public void statusFragment_unknownSandbox_returns404() {
    when(sandboxService.getSandbox("nonexistent"))
        .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/sandboxes/nonexistent/status");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(NOT_FOUND);
  }

  // ── GET /sandboxes/:id — detail page ─────────────────────────────────────

  @Test
  public void show_existingSandbox_returns200WithHtml() {
    SandboxInstance sandbox = makeSandbox("sb-show1", SandboxStatus.RUNNING);
    when(sandboxService.getSandbox("sb-show1"))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(sandbox)));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/sandboxes/sb-show1");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(OK);
  }

  @Test
  public void show_missingSandbox_returns404() {
    when(sandboxService.getSandbox("nope"))
        .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/sandboxes/nope");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(NOT_FOUND);
  }

  // ── GET /sandboxes/:id/access — PIN gate page ─────────────────────────────

  @Test
  public void pinGate_existingSandbox_returns200WithPinForm() {
    SandboxInstance sandbox = makeSandbox("sb-gate1", SandboxStatus.RUNNING);
    when(sandboxService.getSandbox("sb-gate1"))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(sandbox)));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/sandboxes/sb-gate1/access");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(OK);
    assertThat(contentAsString(result)).containsIgnoringCase("Burlington");
  }

  @Test
  public void pinGate_missingSandbox_returns404() {
    when(sandboxService.getSandbox("gone"))
        .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/sandboxes/gone/access");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(NOT_FOUND);
  }

  // ── GET /sandboxes — list ─────────────────────────────────────────────────

  @Test
  public void index_returnsHtmlListPage() {
    when(sandboxService.listSandboxes())
        .thenReturn(CompletableFuture.completedFuture(ImmutableList.of()));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/sandboxes");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(OK);
  }

  @Test
  public void index_jsonRequest_returnsJson() {
    when(sandboxService.listSandboxes())
        .thenReturn(CompletableFuture.completedFuture(ImmutableList.of()));

    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/sandboxes")
        .header("Accept", "application/json");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(OK);
    assertThat(result.contentType()).contains("application/json");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private SandboxInstance makeSandbox(String id, SandboxStatus status) {
    return SandboxInstance.builder()
        .id(id)
        .cityName("Burlington, VT")
        .civiformVersion("latest")
        .status(status)
        .url("http://localhost:10001")
        .pin("482917")
        .hostPort(10001)
        .schemaName("sandbox_" + id.replace("-", "_"))
        .adminEmail("admin@test.com")
        .notes("")
        .createdAt(Instant.now())
        .expiresAt(Instant.now().plus(Duration.ofDays(30)))
        .build();
  }
}
