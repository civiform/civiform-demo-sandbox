package services;

import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import models.SandboxInstance;

public interface SandboxService {
  CompletionStage<ImmutableList<SandboxInstance>> listSandboxes();
  CompletionStage<Optional<SandboxInstance>> getSandbox(String id);
  CompletionStage<SandboxInstance> createSandbox(String name, String version, String adminEmail, String notes);
  CompletionStage<Boolean> deleteSandbox(String id);
}
