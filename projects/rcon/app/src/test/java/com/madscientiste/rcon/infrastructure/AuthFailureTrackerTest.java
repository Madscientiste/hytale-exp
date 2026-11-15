package com.madscientiste.rcon.infrastructure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class AuthFailureTrackerTest {

  private static final String A = "10.0.0.1";
  private static final String B = "10.0.0.2";

  private final AtomicLong now = new AtomicLong(1_000_000);

  /** 3 failures in 60 s ban for 30 s. */
  private AuthFailureTracker tracker() {
    return new AuthFailureTracker(3, 60_000, 30_000, now::get);
  }

  private static boolean fail(AuthFailureTracker tracker, String key) {
    assertTrue(tracker.tryBeginAttempt(key));
    return tracker.endAttempt(key, false);
  }

  @Test
  public void bansAfterTheConfiguredNumberOfFailures() {
    AuthFailureTracker tracker = tracker();

    assertFalse(fail(tracker, A));
    assertFalse(fail(tracker, A));
    assertFalse(tracker.isBanned(A));
    assertTrue("The third failure starts the ban", fail(tracker, A));

    assertTrue(tracker.isBanned(A));
    assertFalse("A banned address gets no attempt", tracker.tryBeginAttempt(A));
    assertFalse("Other addresses are not affected", tracker.isBanned(B));
    assertTrue(tracker.tryBeginAttempt(B));
  }

  @Test
  public void banEndsAfterTheBanTimeAndTheCountStartsFresh() {
    AuthFailureTracker tracker = tracker();
    fail(tracker, A);
    fail(tracker, A);
    fail(tracker, A);

    now.addAndGet(29_999);
    assertTrue(tracker.isBanned(A));

    now.addAndGet(1);
    assertFalse(tracker.isBanned(A));
    assertFalse("One failure after the ban does not ban again", fail(tracker, A));
    assertFalse(fail(tracker, A));
    assertTrue(fail(tracker, A));
  }

  @Test
  public void failuresOutsideTheWindowAreForgotten() {
    AuthFailureTracker tracker = tracker();
    fail(tracker, A);
    fail(tracker, A);

    now.addAndGet(60_000);
    assertFalse(fail(tracker, A));
    assertFalse(fail(tracker, A));
    assertFalse(tracker.isBanned(A));
  }

  @Test
  public void aCorrectPasswordDoesNotWipeEarlierFailures() {
    AuthFailureTracker tracker = tracker();
    fail(tracker, A);
    fail(tracker, A);

    assertTrue(tracker.tryBeginAttempt(A));
    assertFalse(tracker.endAttempt(A, true));

    assertTrue("Still the third failure", fail(tracker, A));
  }

  @Test
  public void attemptsInFlightCountAgainstTheLimit() {
    AuthFailureTracker tracker = tracker();

    // Three connections start checking a password at the same moment
    assertTrue(tracker.tryBeginAttempt(A));
    assertTrue(tracker.tryBeginAttempt(A));
    assertTrue(tracker.tryBeginAttempt(A));
    assertFalse("A fourth guess must wait: no budget left", tracker.tryBeginAttempt(A));

    assertFalse(tracker.endAttempt(A, false));
    assertFalse(tracker.endAttempt(A, false));
    assertTrue(tracker.endAttempt(A, false));
    assertTrue(tracker.isBanned(A));
  }

  @Test
  public void successfulLoginsLeaveNothingBehind() {
    AuthFailureTracker tracker = tracker();
    for (int i = 0; i < 100; i++) {
      assertTrue(tracker.tryBeginAttempt("10.1.0." + i));
      tracker.endAttempt("10.1.0." + i, true);
    }
    assertEquals(0, tracker.trackedAddresses());
  }

  @Test
  public void memoryIsBoundedHoweverManyAddressesFail() {
    AuthFailureTracker tracker = tracker();
    for (int i = 0; i < AuthFailureTracker.MAX_TRACKED + 500; i++) {
      fail(tracker, "key-" + i);
    }
    assertTrue(tracker.trackedAddresses() <= AuthFailureTracker.MAX_TRACKED);
  }

  @Test
  public void ipv4IsCountedPerAddress() throws Exception {
    assertEquals("192.0.2.7", AuthFailureTracker.keyFor(InetAddress.getByName("192.0.2.7")));
    assertNotEquals(
        AuthFailureTracker.keyFor(InetAddress.getByName("192.0.2.7")),
        AuthFailureTracker.keyFor(InetAddress.getByName("192.0.2.8")));
  }

  /** A local program can use any 127.x.y.z as its source address, so they must all count as one. */
  @Test
  public void allOfLoopbackIsOneAddress() throws Exception {
    String key = AuthFailureTracker.keyFor(InetAddress.getByName("127.0.0.1"));

    assertEquals(AuthFailureTracker.LOOPBACK_KEY, key);
    assertEquals(key, AuthFailureTracker.keyFor(InetAddress.getByName("127.0.0.2")));
    assertEquals(key, AuthFailureTracker.keyFor(InetAddress.getByName("127.200.30.4")));
    assertEquals(key, AuthFailureTracker.keyFor(InetAddress.getByName("::1")));

    byte[] mapped = new byte[16];
    mapped[10] = (byte) 0xFF;
    mapped[11] = (byte) 0xFF;
    mapped[12] = 127;
    mapped[15] = 9;
    assertEquals(
        "::ffff:127.0.0.9 is loopback too",
        key,
        AuthFailureTracker.keyFor(java.net.Inet6Address.getByAddress(null, mapped, null)));

    assertNotEquals(key, AuthFailureTracker.keyFor(InetAddress.getByName("128.0.0.1")));
    assertNotEquals(key, AuthFailureTracker.keyFor(InetAddress.getByName("::2")));
  }

  @Test
  public void ipv4MappedAddressesCountAsTheirIpv4Address() throws Exception {
    byte[] mapped = new byte[16];
    mapped[10] = (byte) 0xFF;
    mapped[11] = (byte) 0xFF;
    mapped[12] = (byte) 192;
    mapped[13] = 0;
    mapped[14] = 2;
    mapped[15] = 7;
    assertEquals(
        "192.0.2.7",
        AuthFailureTracker.keyFor(java.net.Inet6Address.getByAddress(null, mapped, null)));
  }

  @Test
  public void ipv6IsCountedPerSlash64() throws Exception {
    String first = AuthFailureTracker.keyFor(InetAddress.getByName("2001:db8:1:2::1"));
    String sameNetwork = AuthFailureTracker.keyFor(InetAddress.getByName("2001:db8:1:2:ffff::9"));
    String otherNetwork = AuthFailureTracker.keyFor(InetAddress.getByName("2001:db8:1:3::1"));

    assertEquals("2001:db8:1:2::/64", first);
    assertEquals(first, sameNetwork);
    assertNotEquals(first, otherNetwork);
  }
}
