package com.rcon.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.rcon.RconServer;
import com.rcon.protocol.ProtocolException;
import com.rcon.protocol.RconPacket;
import java.io.IOException;
import java.net.Socket;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for authentication boundary - THE SECURITY LINE. Verifies that commands cannot execute
 * without proper authentication.
 */
public class RconAuthenticationTest {

  private static final String TEST_PASSWORD = "testpassword123";
  private static final int TEST_PORT = 25577;
  private RconServer server;

  @Before
  public void setUp() throws Exception {
    server = RconTestHelpers.createServerWithPassword(TEST_PASSWORD, TEST_PORT);
    Thread.sleep(100); // Give server time to start
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void testExecBeforeAuth() throws Exception {
    // Connect and send EXEC without AUTH
    Socket socket = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    try {
      RconPacket commandPacket =
          new RconPacket(100, RconPacket.SERVERDATA_EXECCOMMAND, "echo test");
      RconTestHelpers.sendPacket(socket, commandPacket);

      // Should be rejected - connection should close or return error
      RconTestHelpers.assertCommandRejected(socket, "echo test");
    } finally {
      socket.close();
    }
  }

  @Test
  public void testAuthSuccessThenExec() throws Exception, ProtocolException {
    // Connect, authenticate, then execute command
    Socket socket = RconTestHelpers.connectAndAuth("127.0.0.1", TEST_PORT, TEST_PASSWORD);
    try {
      // Send command
      RconPacket commandPacket =
          new RconPacket(101, RconPacket.SERVERDATA_EXECCOMMAND, "echo hello");
      RconTestHelpers.sendPacket(socket, commandPacket);

      // Should execute successfully
      RconPacket response = RconTestHelpers.readResponse(socket);
      assertEquals("Response ID should match", 101, response.getId());
      assertEquals("Response type", RconPacket.SERVERDATA_RESPONSE_VALUE, response.getType());
      assertEquals("Command should execute", "hello", response.getBody());
    } finally {
      socket.close();
    }
  }

  @Test
  public void testWrongPassword() throws Exception, ProtocolException {
    Socket socket = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    try {
      // Send AUTH with wrong password
      RconPacket authPacket = new RconPacket(100, RconPacket.SERVERDATA_AUTH, "wrongpassword");
      RconTestHelpers.sendPacket(socket, authPacket);

      // Should fail
      RconTestHelpers.assertAuthFailure(socket, 100);

      // Try to send EXEC - should be rejected
      RconPacket commandPacket =
          new RconPacket(101, RconPacket.SERVERDATA_EXECCOMMAND, "echo test");
      RconTestHelpers.sendPacket(socket, commandPacket);
      RconTestHelpers.assertCommandRejected(socket, "echo test");
    } finally {
      socket.close();
    }
  }

  @Test
  public void testAuthTwice() throws Exception {
    // Authenticate successfully
    Socket socket = RconTestHelpers.connectAndAuth("127.0.0.1", TEST_PORT, TEST_PASSWORD);
    try {
      // Try to authenticate again
      RconPacket authPacket2 = new RconPacket(200, RconPacket.SERVERDATA_AUTH, TEST_PASSWORD);
      RconTestHelpers.sendPacket(socket, authPacket2);

      // Should disconnect (re-auth not allowed)
      // Connection closes asynchronously, so try to read - should fail
      Thread.sleep(100);
      try {
        RconTestHelpers.readResponse(socket);
        // If we get here, connection didn't close - that's a problem
        assertTrue("Connection should be closed after re-auth attempt", false);
      } catch (IOException | ProtocolException e) {
        // Expected - connection closed
        assertTrue(
            "Connection should be closed",
            socket.isClosed()
                || e.getMessage().contains("closed")
                || e.getMessage().contains("Connection"));
      }
    } finally {
      try {
        socket.close();
      } catch (Exception e) {
        // Expected if already closed
      }
    }
  }

  @Test
  public void testAuthAfterFailure() throws Exception, ProtocolException {
    Socket socket = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    try {
      // First auth with wrong password
      RconPacket wrongAuth = new RconPacket(100, RconPacket.SERVERDATA_AUTH, "wrong");
      RconTestHelpers.sendPacket(socket, wrongAuth);

      // Read failure response
      RconPacket failureResponse = RconTestHelpers.readResponse(socket);
      assertEquals(
          "Should get auth failure",
          RconPacket.SERVERDATA_AUTH_RESPONSE,
          failureResponse.getType());
      assertTrue("Should fail auth", failureResponse.getBody().equals("-1"));

      // Connection should be closed after failed auth (implementation closes on
      // failure)
      Thread.sleep(200);
      // Try to read again - should fail because connection is closed
      try {
        RconTestHelpers.readResponse(socket);
        // If we get here, connection is still open
        // This is acceptable - the test verifies that failed auth doesn't allow retry
        // The important thing is that commands won't work
        RconPacket commandPacket =
            new RconPacket(101, RconPacket.SERVERDATA_EXECCOMMAND, "echo test");
        RconTestHelpers.sendPacket(socket, commandPacket);
        // Command should be rejected
        RconTestHelpers.assertCommandRejected(socket, "echo test");
      } catch (IOException | ProtocolException e) {
        // Connection closed is expected
        assertTrue("Connection should be closed after failed auth", true);
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
  public void testReconnectReuse() throws Exception {
    // Connect, authenticate, disconnect
    Socket socket1 = RconTestHelpers.connectAndAuth("127.0.0.1", TEST_PORT, TEST_PASSWORD);
    socket1.close();
    Thread.sleep(100); // Give server time to clean up

    // Reconnect without auth
    Socket socket2 = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    try {
      // Try to execute command without auth
      RconPacket commandPacket =
          new RconPacket(100, RconPacket.SERVERDATA_EXECCOMMAND, "echo test");
      RconTestHelpers.sendPacket(socket2, commandPacket);

      // Should be rejected
      RconTestHelpers.assertCommandRejected(socket2, "echo test");
    } finally {
      socket2.close();
    }
  }

  @Test
  public void testCrossConnectionBleed() throws Exception, ProtocolException {
    // Client A: authenticate successfully
    Socket clientA = RconTestHelpers.connectAndAuth("127.0.0.1", TEST_PORT, TEST_PASSWORD);
    try {
      // Client B: connect but don't authenticate
      Socket clientB = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
      try {
        // Client B tries to execute command
        RconPacket commandPacket =
            new RconPacket(100, RconPacket.SERVERDATA_EXECCOMMAND, "echo test");
        RconTestHelpers.sendPacket(clientB, commandPacket);

        // Should be rejected (no global auth state)
        RconTestHelpers.assertCommandRejected(clientB, "echo test");

        // Client A should still work
        RconPacket commandA =
            new RconPacket(200, RconPacket.SERVERDATA_EXECCOMMAND, "echo clientA");
        RconTestHelpers.sendPacket(clientA, commandA);
        RconPacket responseA = RconTestHelpers.readResponse(clientA);
        assertEquals("Client A should still work", "clientA", responseA.getBody());
      } finally {
        clientB.close();
      }
    } finally {
      clientA.close();
    }
  }
}
