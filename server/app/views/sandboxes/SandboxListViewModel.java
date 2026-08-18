package views.sandboxes;

import com.google.common.collect.ImmutableList;
import lombok.Builder;
import lombok.Value;
import models.SandboxInstance;
import views.BaseViewModel;

@Value
@Builder
public class SandboxListViewModel implements BaseViewModel {
  ImmutableList<SandboxInstance> sandboxes;
}
