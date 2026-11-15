package com.madscientiste.rcon.infrastructure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.Inet4Address;
import java.net.InetAddress;
import org.junit.Test;

public class AddressRangeTest {

  private static InetAddress ip(String literal) throws Exception {
    return InetAddress.getByName(literal);
  }

  /** ::ffff:a.b.c.d as a real 16-byte IPv6 address (Java turns the text form into IPv4). */
  private static InetAddress mapped(int a, int b, int c, int d) throws Exception {
    byte[] raw = new byte[16];
    raw[10] = (byte) 0xFF;
    raw[11] = (byte) 0xFF;
    raw[12] = (byte) a;
    raw[13] = (byte) b;
    raw[14] = (byte) c;
    raw[15] = (byte) d;
    return java.net.Inet6Address.getByAddress(null, raw, null);
  }

  @Test
  public void singleAddressMatchesOnlyItself() throws Exception {
    AddressRange range = AddressRange.parse("192.0.2.7");
    assertTrue(range.contains(ip("192.0.2.7")));
    assertFalse(range.contains(ip("192.0.2.8")));
    assertFalse(range.contains(ip("::1")));
  }

  @Test
  public void ipv4CidrMatchesTheWholeNetwork() throws Exception {
    AddressRange loopback = AddressRange.parse("127.0.0.0/8");
    assertTrue(loopback.contains(ip("127.0.0.1")));
    assertTrue(loopback.contains(ip("127.255.255.254")));
    assertFalse(loopback.contains(ip("128.0.0.1")));

    AddressRange odd = AddressRange.parse("10.1.2.128/25");
    assertTrue(odd.contains(ip("10.1.2.129")));
    assertTrue(odd.contains(ip("10.1.2.255")));
    assertFalse(odd.contains(ip("10.1.2.127")));
  }

  @Test
  public void ipv6CidrMatchesTheWholeNetwork() throws Exception {
    AddressRange range = AddressRange.parse("2001:db8:1::/48");
    assertTrue(range.contains(ip("2001:db8:1:ffff::9")));
    assertFalse(range.contains(ip("2001:db8:2::1")));
    assertFalse("An IPv6 range never matches IPv4", range.contains(ip("32.1.13.184")));

    assertTrue(AddressRange.parse("::1").contains(ip("::1")));
    assertFalse(AddressRange.parse("::1").contains(ip("::2")));
  }

  @Test
  public void slashZeroMatchesEveryAddressOfThatFamily() throws Exception {
    assertTrue(AddressRange.parse("0.0.0.0/0").contains(ip("203.0.113.9")));
    assertFalse(AddressRange.parse("0.0.0.0/0").contains(ip("2001:db8::1")));
    assertTrue(AddressRange.parse("::/0").contains(ip("2001:db8::1")));
  }

  @Test
  public void ipv4MappedPeersAreSeenAsIpv4() throws Exception {
    assertTrue(AddressRange.parse("10.0.0.0/8").contains(mapped(10, 2, 3, 4)));
    assertFalse(AddressRange.parse("10.0.0.0/8").contains(mapped(11, 2, 3, 4)));
    assertTrue(AddressRange.normalize(mapped(10, 2, 3, 4)) instanceof Inet4Address);
  }

  @Test
  public void loopbackMeansAllOf127AndColonColonOne() throws Exception {
    assertTrue(AddressRange.isLoopback(ip("127.0.0.1")));
    assertTrue(AddressRange.isLoopback(ip("127.200.3.4")));
    assertTrue(AddressRange.isLoopback(ip("::1")));
    assertTrue(AddressRange.isLoopback(mapped(127, 0, 0, 1)));
    assertFalse(AddressRange.isLoopback(ip("128.0.0.1")));
    assertFalse(AddressRange.isLoopback(ip("10.0.0.1")));
    assertFalse(AddressRange.isLoopback(ip("::2")));
  }

  @Test
  public void textIsKeptAsWritten() {
    assertEquals("127.0.0.0/8", AddressRange.parse(" 127.0.0.0/8 ").toString());
  }

  @Test
  public void anythingElseIsRefusedWithoutANameLookup() {
    String[] bad = {
      "",
      "localhost",
      "example.com",
      "10",
      "10.0.0",
      "10.0.0.256",
      "10.0.0.0/33",
      "10.0.0.0/-1",
      "10.0.0.0/",
      "10.0.0.0/8/8",
      "::1/129",
      "::ffff:10.0.0.0/104",
      "1.2.3.4 or 5.6.7.8",
    };
    for (String text : bad) {
      try {
        AddressRange.parse(text);
        fail("Should be refused: \"" + text + "\"");
      } catch (IllegalArgumentException expected) {
        // Refused
      }
    }
  }
}
