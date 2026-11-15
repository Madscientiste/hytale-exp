package com.madscientiste.rcon.application;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import org.junit.Assume;

/**
 * Addresses for tests. The server counts every loopback address (127.0.0.0/8, ::1) as one and the
 * same peer, so a test that needs a second, different peer must use an address of this machine that
 * is not loopback: the address of a network card.
 *
 * <p>Test servers listen on exactly one address, never on 0.0.0.0: 127.0.0.1 by default, or the
 * outside address when a test needs outside peers. A test whose address is missing is skipped with
 * a message that says which one.
 */
public final class TestAddresses {

  public static final String LOOPBACK = "127.0.0.1";
  public static final String LOOPBACK_V6 = "::1";

  private TestAddresses() {}

  /**
   * An IPv4 address of this machine that is not loopback. Skips the calling test when the machine
   * has none (no network card that is up).
   */
  public static String outside() {
    String found = null;
    try {
      for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!nic.isUp() || nic.isLoopback()) {
          continue;
        }
        for (InetAddress address : Collections.list(nic.getInetAddresses())) {
          if (address instanceof Inet4Address
              && !address.isLoopbackAddress()
              && !address.isLinkLocalAddress()) {
            found = address.getHostAddress();
          }
        }
      }
    } catch (IOException e) {
      // Treated as "none"
    }
    Assume.assumeTrue(
        "SKIPPED: this test needs a second peer, and this machine has no non-loopback IPv4"
            + " address (no network card that is up)",
        found != null);
    return found;
  }

  /** Skips the calling test when this machine has no usable IPv6 loopback (::1). */
  public static void assumeIpv6Loopback() {
    boolean usable;
    try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_V6))) {
      usable = true;
    } catch (IOException e) {
      usable = false;
    }
    Assume.assumeTrue(
        "SKIPPED: this test needs IPv6 loopback, and ::1 cannot be used on this machine"
            + " (IPv6 is off, as in a default Docker container)",
        usable);
  }

  /** Connect from a loopback source address to a server listening on 127.0.0.1. */
  public static Socket connectFrom(String sourceIp, int port) throws IOException {
    return connect(sourceIp, LOOPBACK, port);
  }

  /** Connect from the given source address of this machine to a server on this machine. */
  public static Socket connect(String sourceIp, String serverIp, int port) throws IOException {
    Socket socket = new Socket();
    try {
      socket.bind(new InetSocketAddress(sourceIp, 0));
      socket.connect(new InetSocketAddress(serverIp, port), 3000);
      socket.setSoTimeout(5000);
      socket.setTcpNoDelay(true);
      return socket;
    } catch (IOException e) {
      socket.close();
      throw e;
    }
  }
}
