package views.dev;

import com.google.common.collect.ImmutableList;
import lombok.Builder;
import lombok.Data;
import views.BaseViewModel;

/** View model for the {@code GET /dev} developer tools page. */
@Data
@Builder
public class DevToolsViewModel implements BaseViewModel {
  /** Names of all durable jobs registered in {@link durablejobs.DurableJobRegistry}. */
  @Builder.Default private final ImmutableList<String> durableJobNames = ImmutableList.of();

  /** Action URL for {@code POST /dev/runDurableJob}. */
  private final String runDurableJobUrl;
}
