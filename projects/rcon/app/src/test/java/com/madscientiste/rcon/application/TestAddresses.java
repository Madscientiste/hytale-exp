package com.madscientiste.rcon.application;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import org.junit.Assume;

/**
 * Source addresses for tests. The server counts every loopback address (127.0.0.0/8, ::1) as one
 * and the same peer, so a test that needs a second, different peer must come from an address of
 * this machine that is not loopback: the address of a network card.
 */
public final class TestAddresses {

  public static final String LOOPBACK = "127.0.0.1";

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
    Assume.assumeTrue("This machine has no non-loopback IPv4 address", found != null);
    return found;
  }

  /**
   * Connect to a server on this machine from the given source address. A loopback source reaches
   * the server on 127.0.0.1; an outside source reaches it on that same outside address, so the
   * server must listen on 0.0.0.0.
   */
  public static Socket connectFrom(String sourceIp, int port) throws IOException {
    String target = sourceIp.startsWith("127.") ? LOOPBACK : sourceIp;
    Socket socket = new Socket();
    try {
      socket.bind(new InetSocketAddress(sourceIp, 0));
      socket.connect(new InetSocketAddress(target, port), 3000);
      socket.setSoTimeout(5000);
      socket.setTcpNoDelay(true);
      return socket;
    } catch (IOException e) {
      socket.close();
      throw e;
    }
  }
}
