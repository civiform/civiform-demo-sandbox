package services;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.Test;

public class PinAttemptLimiterTest {

  private static final Duration WINDOW = Duration.ofMinutes(10);

  private static class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-26T12:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  public void allowsAttemptsUpToTheLimit() {
    PinAttemptLimiter limiter = new PinAttemptLimiter(3, WINDOW, new MutableClock());

    assertThat(limiter.tryAttempt("sb-1")).isEmpty();
    assertThat(limiter.tryAttempt("sb-1")).isEmpty();
    assertThat(limiter.tryAttempt("sb-1")).isEmpty();
  }

  @Test
  public void refusesAttemptsOverTheLimit() {
    PinAttemptLimiter limiter = new PinAttemptLimiter(3, WINDOW, new MutableClock());
    for (int i = 0; i < 3; i++) {
      limiter.tryAttempt("sb-1");
    }

    Optional<Duration> retryAfter = limiter.tryAttempt("sb-1");

    assertThat(retryAfter).contains(WINDOW);
  }

  @Test
  public void refusedAttemptsDoNotExtendTheLockout() {
    MutableClock clock = new MutableClock();
    PinAttemptLimiter limiter = new PinAttemptLimiter(1, WINDOW, clock);
    limiter.tryAttempt("sb-1");

    clock.advance(Duration.ofMinutes(4));
    assertThat(limiter.tryAttempt("sb-1")).contains(Duration.ofMinutes(6));
    clock.advance(Duration.ofMinutes(4));
    assertThat(limiter.tryAttempt("sb-1")).contains(Duration.ofMinutes(2));
    clock.advance(Duration.ofMinutes(2));

    assertThat(limiter.tryAttempt("sb-1")).isEmpty();
  }

  @Test
  public void attemptsAgeOutOfTheWindow() {
    MutableClock clock = new MutableClock();
    PinAttemptLimiter limiter = new PinAttemptLimiter(2, WINDOW, clock);
    limiter.tryAttempt("sb-1");
    clock.advance(Duration.ofMinutes(6));
    limiter.tryAttempt("sb-1");

    // The first attempt is now 10 minutes old and no longer counts.
    clock.advance(Duration.ofMinutes(4));

    assertThat(limiter.tryAttempt("sb-1")).isEmpty();
    assertThat(limiter.tryAttempt("sb-1")).isPresent();
  }

  @Test
  public void sandboxesAreLimitedIndependently() {
    PinAttemptLimiter limiter = new PinAttemptLimiter(1, WINDOW, new MutableClock());
    limiter.tryAttempt("sb-1");

    assertThat(limiter.tryAttempt("sb-1")).isPresent();
    assertThat(limiter.tryAttempt("sb-2")).isEmpty();
  }

  @Test
  public void clearResetsTheCount() {
    PinAttemptLimiter limiter = new PinAttemptLimiter(1, WINDOW, new MutableClock());
    limiter.tryAttempt("sb-1");

    limiter.clear("sb-1");

    assertThat(limiter.tryAttempt("sb-1")).isEmpty();
  }
}
