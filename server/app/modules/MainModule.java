package modules;

import com.google.inject.AbstractModule;
import services.DockerSandboxService;
import services.SandboxRepository;
import services.SandboxService;

public class MainModule extends AbstractModule {
  @Override
  protected void configure() {
    // Sprint 1: Docker socket runtime.
    // Sprint 2: Replace DockerSandboxService with EcsFargateSandboxService here.
    bind(SandboxService.class).to(DockerSandboxService.class).asEagerSingleton();
    bind(SandboxRepository.class).asEagerSingleton();
  }
}

