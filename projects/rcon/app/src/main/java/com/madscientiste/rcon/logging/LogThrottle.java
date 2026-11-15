package com.madscientiste.rcon.logging;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Caps how many log lines one key (a remote address) may cause per time window, so a peer that has
 * not logged in cannot fill the disk by connecting or sending junk in a loop. Lines over the cap
 * are counted and the count is reported on the next line that is let through.
 *
 * <p>Memory is bounded: once {@code maxKeys} addresses are tracked, new ones share one bucket.
 */
public final class LogThrottle {

  private static final String OVERFLOW_KEY = "\0overflow";

  private static final class Bucket {
    long windowStart;
    int used;
    long suppressed;
  }

  private final int maxPerWindow;
  private final long windowMs;
  private final int maxKeys;
  private final LongSupplier clock;
  private final Map<String, Bucket> buckets = new HashMap<>();

  public LogThrottle(int maxPerWindow, long windowMs, int maxKeys) {
    this(maxPerWindow, windowMs, maxKeys, System::currentTimeMillis);
  }

  public LogThrottle(int maxPerWindow, long windowMs, int maxKeys, @Nonnull LongSupplier clock) {
    this.maxPerWindow = maxPerWindow;
    this.windowMs = windowMs;
    this.maxKeys = maxKeys;
    this.clock = clock;
  }

  /**
   * Ask to write one log line for this key.
   *
   * @return -1 if the line must be dropped; otherwise the number of lines dropped for this key
   *     since the last one that was let through (0 when none)
   */
  public synchronized long tryAcquire(@Nonnull String key) {
    long now = clock.getAsLong();
    Bucket bucket = buckets.get(key);

    if (bucket == null) {
      if (buckets.size() >= maxKeys) {
        buckets.values().removeIf(b -> now - b.windowStart >= windowMs);
      }
      if (buckets.size() >= maxKeys) {
        key = OVERFLOW_KEY;
      }
      bucket = buckets.computeIfAbsent(key, k -> new Bucket());
      if (bucket.used == 0 && bucket.suppressed == 0) {
        bucket.windowStart = now;
      }
    }

    if (now - bucket.windowStart >= windowMs) {
      bucket.windowStart = now;
      bucket.used = 0;
    }

    if (bucket.used < maxPerWindow) {
      bucket.used++;
      long suppressed = bucket.suppressed;
      bucket.suppressed = 0;
      return suppressed;
    }

    bucket.suppressed++;
    return -1;
  }

  /** Number of keys currently tracked (for tests). */
  public synchronized int trackedKeys() {
    return buckets.size();
  }
}
