package views.home;

import com.google.common.collect.ImmutableList;
import lombok.Builder;
import lombok.Value;
import models.SandboxInstance;
import views.BaseViewModel;

@Value
@Builder
public class IndexViewModel implements BaseViewModel {
  ImmutableList<SandboxInstance> activeSandboxes;
  int totalSandboxes;
  int runningSandboxes;
  String appVersion;
}
