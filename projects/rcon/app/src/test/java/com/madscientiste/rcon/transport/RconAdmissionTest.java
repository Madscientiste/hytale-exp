package com.madscientiste.rcon.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Who gets a connection slot. Black-box tests cover this with the one outside address a test
 * machine has; the cases that need several outside addresses are checked here on the rule itself.
 */
public class RconAdmissionTest {

  private static final int MAX = 10;
  private static final int PER_IP = 4;

  private static String outside(int open, int fromSameAddress) {
    return RconTransport.refusalReason(false, false, open, fromSameAddress, MAX, PER_IP);
  }

  private static String loopback(int open, int fromSameAddress) {
    return RconTransport.refusalReason(false, true, open, fromSameAddress, MAX, PER_IP);
  }

  @Test
  public void anEmptyServerAcceptsEveryone() {
    assertNull(outside(0, 0));
    assertNull(loopback(0, 0));
  }

  @Test
  public void oneAddressHoldsFourConnectionsAtMost() {
    assertNull(outside(3, 3));
    assertEquals("max_connections_per_ip", outside(4, 4));
    assertEquals("max_connections_per_ip", loopback(4, 4));
    assertNull("Another address is not affected", outside(4, 0));
  }

  /** The review's attack: three outside addresses holding 4 + 4 + 2 connections. */
  @Test
  public void outsideAddressesCannotTakeTheLastSlot() {
    assertNull("The third address gets its first connection (9 open after it)", outside(8, 0));
    assertEquals(
        "Its second connection would be the last slot: refused",
        "last_slot_reserved_for_loopback",
        outside(9, 1));
    assertNull("A local client still gets in", loopback(9, 0));
    assertEquals("Once truly full, nobody gets in", "max_connections", loopback(10, 0));
    assertEquals("max_connections", outside(10, 0));
  }

  @Test
  public void aServerWithOneSlotKeepsNothingBack() {
    assertNull(RconTransport.refusalReason(false, false, 0, 0, 1, 1));
    assertEquals("max_connections", RconTransport.refusalReason(false, false, 1, 0, 1, 1));
  }

  @Test
  public void aBannedAddressIsRefusedFirst() {
    assertEquals("address_banned", RconTransport.refusalReason(true, false, 0, 0, MAX, PER_IP));
    assertEquals("address_banned", RconTransport.refusalReason(true, true, 0, 0, MAX, PER_IP));
  }
}
