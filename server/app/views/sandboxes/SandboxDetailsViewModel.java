package views.sandboxes;

import lombok.Builder;
import lombok.Value;
import models.SandboxInstance;
import views.BaseViewModel;

@Value
@Builder
public class SandboxDetailsViewModel implements BaseViewModel {
  SandboxInstance sandbox;
}
