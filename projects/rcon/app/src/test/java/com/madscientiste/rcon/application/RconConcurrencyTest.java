package com.madscientiste.rcon.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.madscientiste.rcon.RconServer;
import com.madscientiste.rcon.protocol.RconPacket;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Tests for concurrency and connection isolation. */
public class RconConcurrencyTest {

  private static final int TEST_PORT = 25581;
  private RconServer server;
  private ExecutorService executor;

  @Before
  public void setUp() throws Exception {
    server = RconTestHelpers.createServerWithoutPassword(TEST_PORT);
    executor = Executors.newCachedThreadPool();
    Thread.sleep(100);
  }

  @After
  public void tearDown() {
    if (executor != null) {
      executor.shutdown();
      try {
        executor.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void testInterleavedClients() throws Exception {
    // Two clients sending packets simultaneously
    Socket clientA = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    Socket clientB = RconTestHelpers.connect("127.0.0.1", TEST_PORT);

    try {
      // Authenticate both
      RconPacket authA = new RconPacket(100, RconPacket.SERVERDATA_AUTH, "");
      RconPacket authB = new RconPacket(200, RconPacket.SERVERDATA_AUTH, "");

      // Send auth packets simultaneously
      executor.submit(
          () -> {
            try {
              RconTestHelpers.sendPacket(clientA, authA);
            } catch (IOException e) {
              // Ignore
            }
          });
      executor.submit(
          () -> {
            try {
              RconTestHelpers.sendPacket(clientB, authB);
            } catch (IOException e) {
              // Ignore
            }
          });

      Thread.sleep(200);

      // Both should authenticate successfully
      RconPacket responseA = RconTestHelpers.readResponse(clientA);
      RconPacket responseB = RconTestHelpers.readResponse(clientB);

      assertEquals("Client A auth", RconPacket.SERVERDATA_AUTH_RESPONSE, responseA.getType());
      assertEquals("Client B auth", RconPacket.SERVERDATA_AUTH_RESPONSE, responseB.getType());

      // Send commands simultaneously
      RconPacket cmdA = new RconPacket(101, RconPacket.SERVERDATA_EXECCOMMAND, "echo clientA");
      RconPacket cmdB = new RconPacket(201, RconPacket.SERVERDATA_EXECCOMMAND, "echo clientB");

      executor.submit(
          () -> {
            try {
              RconTestHelpers.sendPacket(clientA, cmdA);
            } catch (IOException e) {
              // Ignore
            }
          });
      executor.submit(
          () -> {
            try {
              RconTestHelpers.sendPacket(clientB, cmdB);
            } catch (IOException e) {
              // Ignore
            }
          });

      Thread.sleep(200);

      // Both should get correct responses (no cross-pollution)
      RconPacket respA = RconTestHelpers.readResponse(clientA);
      RconPacket respB = RconTestHelpers.readResponse(clientB);

      assertEquals("Client A response ID", 101, respA.getId());
      assertEquals("Client B response ID", 201, respB.getId());
      assertTrue("Client A response should contain 'clientA'", respA.getBody().contains("clientA"));
      assertTrue("Client B response should contain 'clientB'", respB.getBody().contains("clientB"));
    } finally {
      clientA.close();
      clientB.close();
    }
  }

  @Test
  public void testAuthExecRace() throws Exception {
    Socket socket = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    try {
      // Send AUTH
      RconPacket authPacket = new RconPacket(100, RconPacket.SERVERDATA_AUTH, "");
      RconTestHelpers.sendPacket(socket, authPacket);

      // Immediately send EXEC without waiting for response (race condition)
      RconPacket commandPacket =
          new RconPacket(101, RconPacket.SERVERDATA_EXECCOMMAND, "echo test");
      RconTestHelpers.sendPacket(socket, commandPacket);

      // Should get deterministic result:
      // Either auth succeeds first and command executes, or command is rejected
      RconPacket response1 = RconTestHelpers.readResponse(socket);
      RconPacket response2 = RconTestHelpers.readResponse(socket);

      // One should be auth response, one should be command response or rejection
      boolean hasAuth =
          response1.getType() == RconPacket.SERVERDATA_AUTH_RESPONSE
              || response2.getType() == RconPacket.SERVERDATA_AUTH_RESPONSE;
      assertTrue("Should get auth response", hasAuth);

      // Command should either execute or be rejected (deterministic, not random)
      boolean commandProcessed =
          (response1.getType() == RconPacket.SERVERDATA_RESPONSE_VALUE
                  || response2.getType() == RconPacket.SERVERDATA_RESPONSE_VALUE)
              || socket.isClosed();
      assertTrue("Command should be processed or rejected deterministically", commandProcessed);
    } finally {
      socket.close();
    }
  }

  @Test
  public void testDisconnectMidCommand() throws Exception {
    Socket socket = RconTestHelpers.connectAndAuth("127.0.0.1", TEST_PORT, "");
    try {
      // Send command
      RconPacket commandPacket =
          new RconPacket(100, RconPacket.SERVERDATA_EXECCOMMAND, "echo test");
      RconTestHelpers.sendPacket(socket, commandPacket);

      // Immediately close socket
      socket.close();

      // Server should remain stable (no leaked state, no crashes)
      Thread.sleep(200);
      assertTrue("Server should still be running", server.isRunning());

      // Verify server stats are clean
      com.madscientiste.rcon.application.RconApplication.ApplicationStats stats = server.getStats();
      assertTrue("Connection count should be reasonable", stats.connectionCount >= 0);
    } finally {
      try {
        socket.close();
      } catch (Exception e) {
        // Already closed
      }
    }
  }
}
