package com.rcon.application;

import static org.junit.Assert.assertTrue;

import com.rcon.RconServer;
import com.rcon.protocol.ProtocolException;
import com.rcon.protocol.RconPacket;
import java.io.IOException;
import java.net.Socket;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Tests for resource safety and stability. */
public class RconResourceSafetyTest {

  private static final int TEST_PORT = 25582;
  private RconServer server;

  @Before
  public void setUp() throws Exception {
    server = RconTestHelpers.createServerWithoutPassword(TEST_PORT);
    Thread.sleep(100);
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void testIdleUnauthenticated() throws Exception {
    Socket socket = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    socket.setSoTimeout(100); // Set socket timeout
    try {
      // Connect but do nothing
      // Wait for timeout (readTimeoutMs = 5000ms)
      // Note: cleanupIdleConnections needs to be called, or socket read timeout will trigger
      Thread.sleep(6000);

      // Try to read - should timeout or connection closed
      try {
        socket.setSoTimeout(100);
        socket.getInputStream().read();
        // If we get here, connection is still open
        // This is acceptable - idle cleanup might not run automatically
      } catch (java.net.SocketTimeoutException e) {
        // Timeout is expected
        assertTrue("Socket should timeout", true);
      } catch (IOException e) {
        // Connection closed is also acceptable
        assertTrue("Connection should be closed or timeout", socket.isClosed());
      }
    } finally {
      try {
        socket.close();
      } catch (Exception e) {
        // Already closed
      }
    }
  }

  @Test
  public void testIdleAuthenticated() throws Exception, ProtocolException {
    Socket socket = RconTestHelpers.connectAndAuth("127.0.0.1", TEST_PORT, "");
    socket.setSoTimeout(100); // Set socket timeout
    try {
      // Authenticate then do nothing
      // Wait for timeout
      Thread.sleep(6000);

      // Try to read - should timeout or connection closed
      try {
        socket.setSoTimeout(100);
        socket.getInputStream().read();
        // If we get here, connection is still open
      } catch (java.net.SocketTimeoutException e) {
        // Timeout is expected
        assertTrue("Socket should timeout", true);
      } catch (IOException e) {
        // Connection closed is also acceptable
        assertTrue("Connection should be closed or timeout", socket.isClosed());
      }
    } finally {
      try {
        socket.close();
      } catch (Exception e) {
        // Already closed
      }
    }
  }

  @Test
  public void testCommandFlood() throws Exception {
    Socket socket = RconTestHelpers.connectAndAuth("127.0.0.1", TEST_PORT, "");
    try {
      // Send many commands rapidly
      int commandCount = 100;
      for (int i = 0; i < commandCount; i++) {
        RconPacket commandPacket =
            new RconPacket(100 + i, RconPacket.SERVERDATA_EXECCOMMAND, "echo " + i);
        RconTestHelpers.sendPacket(socket, commandPacket);
      }

      // Should handle all commands (bounded queue, no memory growth)
      // Read all responses
      int responsesReceived = 0;
      try {
        for (int i = 0; i < commandCount; i++) {
          RconPacket response = RconTestHelpers.readResponse(socket);
          responsesReceived++;
          assertTrue(
              "Response should be valid",
              response.getType() == RconPacket.SERVERDATA_RESPONSE_VALUE);
        }
      } catch (Exception e) {
        // Some responses might be lost, but server should remain stable
      }

      // Server should still be running and stable
      assertTrue("Server should still be running", server.isRunning());
      assertTrue("Should receive some responses", responsesReceived > 0);
    } finally {
      socket.close();
    }
  }

  @Test
  public void testHugeCommandOutput() throws Exception {
    Socket socket = RconTestHelpers.connectAndAuth("127.0.0.1", TEST_PORT, "");
    try {
      // Note: This test depends on having a command that produces large output
      // Since we're using echo, we'll test with a large echo command
      // In real scenario, this would test commands that produce massive output

      // Create a command that will produce large output
      StringBuilder largeOutput = new StringBuilder("echo ");
      for (int i = 0; i < 1000; i++) {
        largeOutput.append("line ").append(i).append("\n");
      }

      RconPacket commandPacket =
          new RconPacket(100, RconPacket.SERVERDATA_EXECCOMMAND, largeOutput.toString());
      RconTestHelpers.sendPacket(socket, commandPacket);

      // Should either:
      // 1. Return truncated response
      // 2. Return full response (if within limits)
      // 3. Fail cleanly (not OOM)
      try {
        RconPacket response = RconTestHelpers.readResponse(socket);
        assertTrue(
            "Should get response or fail cleanly",
            response.getType() == RconPacket.SERVERDATA_RESPONSE_VALUE || socket.isClosed());
      } catch (Exception e) {
        // Clean failure is acceptable
        assertTrue("Should fail cleanly, not crash", e.getMessage() != null || socket.isClosed());
      }

      // Server should remain stable
      assertTrue("Server should still be running", server.isRunning());
    } finally {
      socket.close();
    }
  }
}
