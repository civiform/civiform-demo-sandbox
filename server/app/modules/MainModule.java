package modules;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.AbstractModule;
import com.typesafe.config.Config;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import javax.inject.Inject;
import play.Environment;
import play.Logger;
import play.Logger.ALogger;
import services.DockerSandboxService;
import services.SandboxRepository;
import services.SandboxService;
import services.SeedingEngine;
import services.TerraformSandboxService;

public class MainModule extends AbstractModule {

  private static final ALogger log = Logger.of(MainModule.class);

  private final Environment environment;
  private final Config config;

  @Inject
  public MainModule(Environment environment, Config config) {
    this.environment = environment;
    this.config = config;
  }

  @Override
  protected void configure() {
    bind(Clock.class).toInstance(clockFor(environment, config));
    bind(SandboxRepository.class).asEagerSingleton();
    bind(SeedingEngine.class).asEagerSingleton();
    bind(SandboxService.class).to(sandboxServiceClass()).asEagerSingleton();
  }

  /**
   * Resolves the application {@link Clock}.
   *
   * <p>Defaults to {@link Clock#systemUTC()}. In non-production modes, setting {@code
   * clock.fixedInstant} ({@code FIXED_CLOCK_INSTANT}) to an ISO-8601 instant freezes the clock so
   * browser tests render deterministic dates without rewriting the DOM.
   */
  @VisibleForTesting
  static Clock clockFor(Environment environment, Config config) {
    String fixedInstant =
        config.hasPath("clock.fixedInstant") ? config.getString("clock.fixedInstant").trim() : "";
    if (fixedInstant.isEmpty()) {
      return Clock.systemUTC();
    }
    if (environment.isProd()) {
      throw new IllegalStateException(
          "clock.fixedInstant (FIXED_CLOCK_INSTANT) must not be set in production: a frozen clock"
              + " stops sandboxes from expiring.");
    }
    try {
      Instant instant = Instant.parse(fixedInstant);
      log.warn("Application clock is FROZEN at {} (clock.fixedInstant)", instant);
      return Clock.fixed(instant, ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      throw new IllegalStateException(
          "clock.fixedInstant '"
              + fixedInstant
              + "' is not an ISO-8601 instant (expected e.g. 2026-10-01T12:00:00Z).",
          e);
    }
  }

  /**
   * Selects the sandbox runtime from {@code sandbox.runtime}.
   *
   * <p>Both implementations satisfy the same interface, so nothing above this line — controllers,
   * views, the repository — is aware of which one is bound.
   *
   * <ul>
   *   <li>{@code docker} — launches containers through the local Docker socket. The only runtime
   *       that works without AWS, so it remains what local development and the test suite use.
   *   <li>{@code terraform} — runs the {@code terraform/sandbox} root module per sandbox against
   *       shared AWS infrastructure.
   * </ul>
   */
  private Class<? extends SandboxService> sandboxServiceClass() {
    String runtime = config.getString("sandbox.runtime");

    return switch (runtime) {
      case "docker" -> {
        log.info("Sandbox runtime: docker (local Docker socket)");
        yield DockerSandboxService.class;
      }
      case "terraform" -> {
        log.info("Sandbox runtime: terraform (AWS)");
        yield TerraformSandboxService.class;
      }
      // Fail at startup rather than defaulting. A typo in SANDBOX_RUNTIME that
      // silently fell back to docker would be discovered only when a sales rep
      // created a sandbox that came up on localhost and was unreachable.
      default ->
          throw new IllegalStateException(
              "Unknown sandbox.runtime '" + runtime + "'. Expected \"docker\" or \"terraform\".");
    };
  }
}
