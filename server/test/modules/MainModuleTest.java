package modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.Test;
import play.Environment;
import play.Mode;

/** Unit tests for {@link MainModule} clock resolution. */
public class MainModuleTest {

  private static Environment env(Mode mode) {
    return new Environment(new File("."), MainModuleTest.class.getClassLoader(), mode);
  }

  // ── clockFor ──────────────────────────────────────────────────────────────

  @Test
  public void clockFor_unsetOrBlank_returnsSystemUtcClock() {
    assertThat(MainModule.clockFor(env(Mode.DEV), ConfigFactory.empty()))
        .isEqualTo(Clock.systemUTC());
    assertThat(
            MainModule.clockFor(
                env(Mode.DEV), ConfigFactory.parseString("clock.fixedInstant = \"   \"")))
        .isEqualTo(Clock.systemUTC());
  }

  @Test
  public void clockFor_validInstantInDevOrTest_returnsFixedUtcClock() {
    Config config = ConfigFactory.parseString("clock.fixedInstant = \"2026-10-01T12:00:00Z\"");

    Clock devClock = MainModule.clockFor(env(Mode.DEV), config);
    assertThat(devClock).isEqualTo(Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC));

    Clock testClock = MainModule.clockFor(env(Mode.TEST), config);
    assertThat(testClock).isEqualTo(Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  public void clockFor_setInProd_throwsIllegalStateException() {
    Config config = ConfigFactory.parseString("clock.fixedInstant = \"2026-10-01T12:00:00Z\"");

    assertThatThrownBy(() -> MainModule.clockFor(env(Mode.PROD), config))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("clock.fixedInstant")
        .hasMessageContaining("production");
  }

  @Test
  public void clockFor_malformedInstant_throwsIllegalStateException() {
    Config config = ConfigFactory.parseString("clock.fixedInstant = \"not-a-timestamp\"");

    assertThatThrownBy(() -> MainModule.clockFor(env(Mode.DEV), config))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not-a-timestamp")
        .hasMessageContaining("ISO-8601");
  }
}
