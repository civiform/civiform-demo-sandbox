package modules;

import com.google.inject.AbstractModule;
import com.typesafe.config.Config;
import javax.inject.Inject;
import play.Environment;
import services.DockerSandboxService;
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

    // Sprint 1: Docker socket — works locally with docker-compose
    // Sprint 2+: add EcsFargateSandboxService and gate on SANDBOX_RUNTIME=fargate
    bind(SandboxService.class).to(DockerSandboxService.class).asEagerSingleton();
  }
}
