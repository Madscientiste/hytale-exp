package com.madscientiste.rcon.infrastructure;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Stops password guessing: counts wrong passwords per remote address and bans the address for a
 * while once it reaches the limit.
 *
 * <p>Every login attempt must be reserved with {@link #tryBeginAttempt} before the password is
 * checked and reported with {@link #endAttempt} after. Attempts in flight count against the limit,
 * so several connections guessing at the same moment cannot get more guesses than one connection
 * would. A correct password does not wipe earlier failures: on a shared address (everything on
 * 127.0.0.1) a legitimate client logging in must not hand an attacker a fresh budget.
 *
 * <p>Memory is bounded: at most {@link #MAX_TRACKED} addresses are remembered.
 */
public final class AuthFailureTracker {

  static final int MAX_TRACKED = 10_000;

  /** The one key shared by every loopback address. */
  public static final String LOOPBACK_KEY = "loopback";

  private static final class Entry {
    final ArrayDeque<Long> failures = new ArrayDeque<>();
    int inFlight;
    long bannedUntil;
  }

  private final int maxFailures;
  private final long windowMs;
  private final long banMs;
  private final LongSupplier clock;

  // Insertion order, so the oldest entry can be dropped when the table is full
  private final Map<String, Entry> entries = new LinkedHashMap<>();

  public AuthFailureTracker(int maxFailures, long windowMs, long banMs) {
    this(maxFailures, windowMs, banMs, System::currentTimeMillis);
  }

  public AuthFailureTracker(
      int maxFailures, long windowMs, long banMs, @Nonnull LongSupplier clock) {
    this.maxFailures = maxFailures;
    this.windowMs = windowMs;
    this.banMs = banMs;
    this.clock = clock;
  }

  /**
   * The key failures and connections are counted under.
   *
   * <ul>
   *   <li>Loopback (all of 127.0.0.0/8, and ::1): one key. Every program on the machine can pick
   *       any 127.x.y.z as its source address, so counting them apart would be no limit at all.
   *   <li>Other IPv4: the address.
   *   <li>Other IPv6: the /64 network, because one machine normally owns a whole /64 and could
   *       otherwise switch address for every guess.
   * </ul>
   */
  @Nonnull
  public static String keyFor(@Nonnull InetAddress remoteAddress) {
    InetAddress address = AddressRange.normalize(remoteAddress);
    if (address.isLoopbackAddress()) {
      return LOOPBACK_KEY;
    }
    if (address instanceof Inet6Address) {
      byte[] raw = address.getAddress();
      StringBuilder sb = new StringBuilder(24);
      for (int i = 0; i < 8; i += 2) {
        if (i > 0) {
          sb.append(':');
        }
        sb.append(Integer.toHexString(((raw[i] & 0xFF) << 8) | (raw[i + 1] & 0xFF)));
      }
      return sb.append("::/64").toString();
    }
    return address.getHostAddress();
  }

  /** True while the address is banned. */
  public synchronized boolean isBanned(@Nonnull String key) {
    Entry entry = entries.get(key);
    return entry != null && entry.bannedUntil > clock.getAsLong();
  }

  /**
   * Reserve one login attempt. Must be followed by {@link #endAttempt} if it returns true.
   *
   * @return false if the address is banned or has no attempts left; the password must then not be
   *     checked
   */
  public synchronized boolean tryBeginAttempt(@Nonnull String key) {
    long now = clock.getAsLong();
    Entry entry = entries.get(key);

    if (entry == null) {
      makeRoom(now);
      entry = new Entry();
      entries.put(key, entry);
    }

    expire(entry, now);
    if (entry.bannedUntil > now || entry.failures.size() + entry.inFlight >= maxFailures) {
      return false;
    }

    entry.inFlight++;
    return true;
  }

  /**
   * Report the result of an attempt reserved with {@link #tryBeginAttempt}.
   *
   * @return true if this failure got the address banned
   */
  public synchronized boolean endAttempt(@Nonnull String key, boolean success) {
    long now = clock.getAsLong();
    Entry entry = entries.get(key);

    if (entry == null) {
      // Dropped while in flight because the table was full; start counting again
      entry = new Entry();
      entries.put(key, entry);
    } else if (entry.inFlight > 0) {
      entry.inFlight--;
    }

    expire(entry, now);

    if (success) {
      removeIfIdle(key, entry, now);
      return false;
    }

    entry.failures.addLast(now);
    if (entry.failures.size() >= maxFailures) {
      entry.bannedUntil = now + banMs;
      // The ban is the penalty; afterwards the address starts with a clean count
      entry.failures.clear();
      return true;
    }
    return false;
  }

  /** Number of addresses currently remembered (for tests). */
  public synchronized int trackedAddresses() {
    return entries.size();
  }

  private void expire(Entry entry, long now) {
    while (!entry.failures.isEmpty() && now - entry.failures.peekFirst() >= windowMs) {
      entry.failures.removeFirst();
    }
  }

  private boolean isIdle(Entry entry, long now) {
    return entry.inFlight == 0 && entry.failures.isEmpty() && entry.bannedUntil <= now;
  }

  private void removeIfIdle(String key, Entry entry, long now) {
    if (isIdle(entry, now)) {
      entries.remove(key);
    }
  }

  /** Keep the table bounded: forget idle addresses, then the oldest one if still full. */
  private void makeRoom(long now) {
    if (entries.size() < MAX_TRACKED) {
      return;
    }

    for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
      Entry entry = it.next();
      expire(entry, now);
      if (isIdle(entry, now)) {
        it.remove();
      }
    }

    if (entries.size() >= MAX_TRACKED) {
      Iterator<Entry> it = entries.values().iterator();
      it.next();
      it.remove();
    }
  }
}
