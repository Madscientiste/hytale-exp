package com.madscientiste.rcon.infrastructure;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import javax.annotation.Nonnull;

/**
 * A range of IP addresses written as one address ({@code 192.0.2.7}, {@code ::1}) or in CIDR form
 * ({@code 127.0.0.0/8}, {@code 2001:db8::/32}). Never does a name lookup.
 */
public final class AddressRange {

  private final String text;
  private final byte[] network;
  private final int prefixLength;

  private AddressRange(String text, byte[] network, int prefixLength) {
    this.text = text;
    this.network = network;
    this.prefixLength = prefixLength;
  }

  /**
   * Parse a single address or a CIDR range.
   *
   * @throws IllegalArgumentException if the text is not an IP address or CIDR range
   */
  @Nonnull
  public static AddressRange parse(@Nonnull String text) {
    String trimmed = text.trim();
    int slash = trimmed.indexOf('/');
    String addressPart = slash < 0 ? trimmed : trimmed.substring(0, slash);

    InetAddress address;
    try {
      // IPv4 must be written in full: the parser would also take short forms such as "10"
      if (!addressPart.contains(":") && !addressPart.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
        throw new IllegalArgumentException("not a full IPv4 address");
      }
      address = InetAddress.ofLiteral(addressPart);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("\"" + text + "\" is not an IP address or CIDR range");
    }
    if (address instanceof Inet4Address && addressPart.contains(":")) {
      throw new IllegalArgumentException(
          "\"" + text + "\": write an IPv4-mapped address as plain IPv4 (for example 10.0.0.0/8)");
    }

    byte[] raw = address.getAddress();
    int maxPrefix = raw.length * 8;
    int prefix = maxPrefix;
    if (slash >= 0) {
      String prefixPart = trimmed.substring(slash + 1);
      if (!prefixPart.matches("\\d{1,3}") || Integer.parseInt(prefixPart) > maxPrefix) {
        throw new IllegalArgumentException(
            "\"" + text + "\": the part after '/' must be a number from 0 to " + maxPrefix);
      }
      prefix = Integer.parseInt(prefixPart);
    }

    return new AddressRange(trimmed, raw, prefix);
  }

  /** True if the address lies in this range. An IPv4 range never matches an IPv6 address. */
  public boolean contains(@Nonnull InetAddress address) {
    byte[] raw = normalize(address).getAddress();
    if (raw.length != network.length) {
      return false;
    }

    int fullBytes = prefixLength / 8;
    for (int i = 0; i < fullBytes; i++) {
      if (raw[i] != network[i]) {
        return false;
      }
    }

    int remainingBits = prefixLength % 8;
    if (remainingBits == 0) {
      return true;
    }
    int mask = (0xFF << (8 - remainingBits)) & 0xFF;
    return (raw[fullBytes] & mask) == (network[fullBytes] & mask);
  }

  /**
   * The address with an IPv4-mapped IPv6 form ({@code ::ffff:a.b.c.d}) turned into plain IPv4, so
   * the same peer is always seen as the same address.
   */
  @Nonnull
  public static InetAddress normalize(@Nonnull InetAddress address) {
    if (address instanceof Inet6Address) {
      byte[] raw = address.getAddress();
      boolean mapped = (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF;
      for (int i = 0; i < 10 && mapped; i++) {
        mapped = raw[i] == 0;
      }
      if (mapped) {
        try {
          return InetAddress.getByAddress(new byte[] {raw[12], raw[13], raw[14], raw[15]});
        } catch (UnknownHostException e) {
          // Cannot happen for a 4-byte address
          return address;
        }
      }
    }
    return address;
  }

  /** True for all of 127.0.0.0/8, for ::1 and for their IPv4-mapped forms. */
  public static boolean isLoopback(@Nonnull InetAddress address) {
    return normalize(address).isLoopbackAddress();
  }

  /** The range as it was written in the config. */
  @Override
  public String toString() {
    return text;
  }
}
