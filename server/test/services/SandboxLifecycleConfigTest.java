package services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import java.util.Map;
import org.junit.Test;

/** Unit tests for {@link SandboxLifecycleConfig}. */
public class SandboxLifecycleConfigTest {

  @Test
  public void defaultsToSevenDaysWhenUnset() {
    assertThat(new SandboxLifecycleConfig(ConfigFactory.empty()).getAutodeletionGraceDays())
        .isEqualTo(7L);
  }

  @Test
  public void readsConfiguredValueIncludingZero() {
    assertThat(SandboxLifecycleConfig.withGraceDays(14).getAutodeletionGraceDays()).isEqualTo(14L);
    assertThat(SandboxLifecycleConfig.withGraceDays(0).getAutodeletionGraceDays()).isEqualTo(0L);
  }

  @Test
  public void bundledApplicationConfDefaultsToSevenDays() {
    assertThat(new SandboxLifecycleConfig(ConfigFactory.load()).getAutodeletionGraceDays())
        .isEqualTo(7L);
  }

  @Test
  public void negativeValueFailsFastInsteadOfBeingClamped() {
    assertThatThrownBy(() -> SandboxLifecycleConfig.withGraceDays(-1))
        .isInstanceOf(ConfigException.BadValue.class)
        .hasMessageContaining(SandboxLifecycleConfig.AUTODELETION_GRACE_DAYS_PATH);
  }

  @Test
  public void nonNumericValueFailsFast() {
    assertThatThrownBy(
            () ->
                new SandboxLifecycleConfig(
                    ConfigFactory.parseMap(
                        Map.of(SandboxLifecycleConfig.AUTODELETION_GRACE_DAYS_PATH, "seven"))))
        .isInstanceOf(ConfigException.WrongType.class);
  }
}
