package views.sandboxes;

import lombok.Builder;
import lombok.Value;
import views.BaseViewModel;

/** View model for the prospect PIN entry page. */
@Value
@Builder
public class PinGateViewModel implements BaseViewModel {
  String sandboxId;
  String cityName;
  /** Non-null when the previously submitted PIN was incorrect. */
  String error;
}
