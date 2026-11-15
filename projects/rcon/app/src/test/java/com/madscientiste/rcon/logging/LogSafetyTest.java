package com.madscientiste.rcon.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** Log values from the network are made harmless, and their number is capped. */
public class LogSafetyTest {

  @Test
  public void controlCharactersBecomeVisibleEscapes() {
    assertEquals("a\\nb\\rc\\td", StructuredLogBuilder.neutralize("a\nb\rc\td"));
    assertEquals("\\u001b[31mred", StructuredLogBuilder.neutralize("\u001b[31mred"));
    assertEquals("nul\\u0000del\\u007f", StructuredLogBuilder.neutralize("nul\u0000del\u007f"));
    assertEquals("c1\\u0085ls\\u2028", StructuredLogBuilder.neutralize("c1\u0085ls "));
  }

  @Test
  public void ordinaryTextIsKept() {
    String text = "say Héllo wörld, 你好! [x] {y} key=value";
    assertEquals(text, StructuredLogBuilder.neutralize(text));
  }

  @Test
  public void longValuesAreCut() {
    String neutral = StructuredLogBuilder.neutralize("x".repeat(5000));
    assertTrue(neutral.startsWith("x".repeat(StructuredLogBuilder.MAX_VALUE_LENGTH)));
    assertTrue(neutral.endsWith("...(4488 more chars)"));
    assertFalse(neutral.length() > StructuredLogBuilder.MAX_VALUE_LENGTH + 40);
  }

  @Test
  public void throttleLetsABurstThroughThenCountsWhatItDrops() {
    AtomicLong now = new AtomicLong(0);
    LogThrottle throttle = new LogThrottle(3, 1000, 100, now::get);

    assertEquals(0, throttle.tryAcquire("a"));
    assertEquals(0, throttle.tryAcquire("a"));
    assertEquals(0, throttle.tryAcquire("a"));
    assertEquals(-1, throttle.tryAcquire("a"));
    assertEquals(-1, throttle.tryAcquire("a"));
    assertEquals("Another key has its own budget", 0, throttle.tryAcquire("b"));

    now.set(1000);
    assertEquals("The next line reports the 2 dropped ones", 2, throttle.tryAcquire("a"));
    assertEquals(0, throttle.tryAcquire("a"));
  }

  @Test
  public void throttleMemoryIsBounded() {
    AtomicLong now = new AtomicLong(0);
    LogThrottle throttle = new LogThrottle(1, 1000, 50, now::get);

    for (int i = 0; i < 10_000; i++) {
      throttle.tryAcquire("key-" + i);
    }
    assertTrue(throttle.trackedKeys() <= 51);

    // Past the key limit, new keys share one budget: still capped
    assertEquals(-1, throttle.tryAcquire("one-more"));

    now.set(1000);
    assertTrue("Expired keys make room again", throttle.tryAcquire("fresh") >= 0);
  }
}
