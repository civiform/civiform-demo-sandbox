package modules;

import com.google.inject.AbstractModule;
import com.typesafe.config.Config;
import javax.inject.Inject;
import play.Environment;
import services.DockerSandboxService;
import services.EcsFargateSandboxService;
import services.SandboxRepository;
import services.SandboxService;

public class MainModule extends AbstractModule {

  private final Config config;

  @Inject
  public MainModule(Environment environment, Config config) {
    this.config = config;
  }

  @Override
  protected void configure() {
    bind(SandboxRepository.class).asEagerSingleton();

    String runtime = config.hasPath("sandbox.runtime")
        ? config.getString("sandbox.runtime")
        : "docker";

    if ("fargate".equalsIgnoreCase(runtime)) {
      // Sprint 2+: AWS ECS Fargate — set SANDBOX_RUNTIME=fargate
      bind(SandboxService.class).to(EcsFargateSandboxService.class).asEagerSingleton();
    } else {
      // Sprint 1 default: Docker socket — works locally with docker-compose
      bind(SandboxService.class).to(DockerSandboxService.class).asEagerSingleton();
    }
  }
}
