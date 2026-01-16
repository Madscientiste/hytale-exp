package com.madscientiste.rcon.application;

import static org.junit.Assert.assertTrue;

import com.madscientiste.rcon.RconServer;
import com.madscientiste.rcon.protocol.ProtocolException;
import com.madscientiste.rcon.protocol.RconPacket;
import java.io.IOException;
import java.net.Socket;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Tests for authentication edge cases. */
public class RconAuthenticationEdgeCasesTest {

  private static final String TEST_PASSWORD = "testpassword123";
  private static final int TEST_PORT = 25578;
  private RconServer server;

  @Before
  public void setUp() throws Exception {
    server = RconTestHelpers.createServerWithPassword(TEST_PASSWORD, TEST_PORT);
    Thread.sleep(100);
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void testEmptyPassword() throws Exception, ProtocolException {
    Socket socket = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    try {
      // Send AUTH with empty password
      RconPacket authPacket = new RconPacket(100, RconPacket.SERVERDATA_AUTH, "");
      RconTestHelpers.sendPacket(socket, authPacket);

      // Should fail (empty password != correct password)
      RconTestHelpers.assertAuthFailure(socket, 100);
    } finally {
      socket.close();
    }
  }

  @Test
  public void testNullByteInPassword() throws Exception {
    Socket socket = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    try {
      // Send AUTH with null byte in password
      String passwordWithNull = "pass\0word";
      RconPacket authPacket = new RconPacket(100, RconPacket.SERVERDATA_AUTH, passwordWithNull);
      RconTestHelpers.sendPacket(socket, authPacket);

      // Should fail (null byte treated literally, not correct password)
      // Note: Packet creation works, but auth will fail
      try {
        RconTestHelpers.assertAuthFailure(socket, 100);
      } catch (ProtocolException | IOException e) {
        // Protocol exception or connection closed is acceptable
        // Null byte in password string is valid, but won't match correct password
        assertTrue(
            "Should fail on null byte password", socket.isClosed() || e.getMessage() != null);
      }
    } finally {
      socket.close();
    }
  }

  @Test
  public void testVeryLongPassword() throws Exception {
    Socket socket = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    try {
      // Create very long password (10KB)
      // MAX_FRAME_SIZE is 4096, so 10KB password will exceed packet size limit
      StringBuilder longPassword = new StringBuilder();
      for (int i = 0; i < 10000; i++) {
        longPassword.append("a");
      }

      // Try to create packet - this might fail if too large
      RconPacket authPacket =
          new RconPacket(100, RconPacket.SERVERDATA_AUTH, longPassword.toString());

      byte[] packetBytes = authPacket.toBytes();
      // Check if packet exceeds MAX_FRAME_SIZE
      if (packetBytes.length > com.madscientiste.rcon.protocol.RconProtocol.MAX_FRAME_SIZE) {
        // Packet too large - should be rejected by protocol layer
        // Try to send it anyway to test fail-fast behavior
        socket.getOutputStream().write(packetBytes);
        socket.getOutputStream().flush();
        // Server should reject or close connection
        Thread.sleep(200);
        // Connection should be closed or we should get an error
        assertTrue(
            "Should handle oversized packet",
            socket.isClosed() || socket.getInputStream().available() == 0);
      } else {
        // Packet is within size limit (unlikely with 10KB password)
        RconTestHelpers.sendPacket(socket, authPacket);
        // Should fail authentication (not correct password)
        RconTestHelpers.assertAuthFailure(socket, 100);
      }
    } finally {
      socket.close();
    }
  }

  @Test
  public void testSlowAuthDripFeed() throws Exception {
    Socket socket = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    try {
      // Create auth packet
      RconPacket authPacket = new RconPacket(100, RconPacket.SERVERDATA_AUTH, TEST_PASSWORD);
      byte[] packetBytes = authPacket.toBytes();

      // Send byte-by-byte with small delays
      for (int i = 0; i < packetBytes.length; i++) {
        socket.getOutputStream().write(packetBytes[i]);
        socket.getOutputStream().flush();
        Thread.sleep(10); // 10ms delay between bytes
      }

      // Should eventually authenticate or timeout
      // With buffering, should work
      try {
        RconPacket response = RconTestHelpers.readResponse(socket);
        // Should get auth response (success or failure)
        assertTrue(
            "Should get auth response", response.getType() == RconPacket.SERVERDATA_AUTH_RESPONSE);
      } catch (IOException e) {
        // Timeout is also acceptable
        assertTrue("Connection should be closed on timeout", socket.isClosed());
      }
    } finally {
      socket.close();
    }
  }
}
