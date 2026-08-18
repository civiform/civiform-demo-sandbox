package modules;

import com.google.inject.AbstractModule;
import services.InMemorySandboxService;
import services.SandboxService;

public class MainModule extends AbstractModule {
  @Override
  protected void configure() {
    bind(SandboxService.class).to(InMemorySandboxService.class).asEagerSingleton();
  }
}
