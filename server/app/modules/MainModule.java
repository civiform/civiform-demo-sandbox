package modules;

import com.google.inject.AbstractModule;
import com.typesafe.config.Config;
import javax.inject.Inject;
import play.Environment;
import play.Logger;
import play.Logger.ALogger;
import services.DockerSandboxService;
import services.SandboxRepository;
import services.SandboxService;
import services.TerraformSandboxService;

public class MainModule extends AbstractModule {

  private static final ALogger log = Logger.of(MainModule.class);

  private final Config config;

  @Inject
  public MainModule(Environment environment, Config config) {
    this.config = config;
  }

  @Override
  protected void configure() {
    bind(SandboxRepository.class).asEagerSingleton();
    bind(SandboxService.class).to(sandboxServiceClass()).asEagerSingleton();
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
