package com.rcon.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.rcon.RconServer;
import com.rcon.protocol.RconPacket;
import java.net.Socket;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Tests for replay attacks and state weirdness. */
public class RconReplayTest {

  private static final String TEST_PASSWORD = "testpassword123";
  private static final int TEST_PORT = 25583;
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
  public void testReplayAuthPacket() throws Exception {
    // Capture AUTH packet bytes
    RconPacket authPacket = new RconPacket(100, RconPacket.SERVERDATA_AUTH, TEST_PASSWORD);
    byte[] authBytes = authPacket.toBytes();

    // First connection - authenticate successfully
    Socket socket1 = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    socket1.getOutputStream().write(authBytes);
    socket1.getOutputStream().flush();
    RconPacket response1 = RconTestHelpers.readResponse(socket1);
    assertEquals(
        "First auth should succeed", RconPacket.SERVERDATA_AUTH_RESPONSE, response1.getType());
    socket1.close();
    Thread.sleep(100);

    // Second connection - replay the same AUTH packet
    Socket socket2 = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    socket2.getOutputStream().write(authBytes);
    socket2.getOutputStream().flush();
    RconPacket response2 = RconTestHelpers.readResponse(socket2);

    // Should work as fresh auth (no session carryover)
    assertEquals(
        "Replayed auth should work", RconPacket.SERVERDATA_AUTH_RESPONSE, response2.getType());
    assertTrue("Replayed auth should succeed", response2.getBody().equals("1"));

    // Verify we can execute commands
    RconPacket commandPacket = new RconPacket(200, RconPacket.SERVERDATA_EXECCOMMAND, "echo test");
    RconTestHelpers.sendPacket(socket2, commandPacket);
    RconPacket cmdResponse = RconTestHelpers.readResponse(socket2);
    assertEquals(
        "Command should execute", RconPacket.SERVERDATA_RESPONSE_VALUE, cmdResponse.getType());

    socket2.close();
  }

  @Test
  public void testReplayExecWithoutAuth() throws Exception {
    // Authenticate and capture EXEC packet
    Socket socket1 = RconTestHelpers.connectAndAuth("127.0.0.1", TEST_PORT, TEST_PASSWORD);
    RconPacket commandPacket = new RconPacket(100, RconPacket.SERVERDATA_EXECCOMMAND, "echo test");
    byte[] execBytes = commandPacket.toBytes();
    socket1.close();
    Thread.sleep(100);

    // New connection - send EXEC without auth
    Socket socket2 = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    socket2.getOutputStream().write(execBytes);
    socket2.getOutputStream().flush();

    // Should be rejected (no session carryover)
    RconTestHelpers.assertCommandRejected(socket2, "echo test");
    socket2.close();
  }
}
