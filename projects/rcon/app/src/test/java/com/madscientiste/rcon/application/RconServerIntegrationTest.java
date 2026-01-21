package com.madscientiste.rcon.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.madscientiste.rcon.RconServer;
import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.infrastructure.RconConstants;
import com.madscientiste.rcon.protocol.RconPacket;
import java.io.IOException;
import java.net.Socket;
import org.junit.Before;
import org.junit.Test;

public class RconServerIntegrationTest {

  private RconConfig config;
  private RconServer server;

  @Before
  public void setUp() throws Exception {
    config =
        RconConfig.builder()
            .host(RconConstants.TEST_HOST)
            .port(RconConstants.TEST_PORT)
            .maxConnections(RconConstants.DEFAULT_MAX_CONNECTIONS)
            .maxFrameSize(RconConstants.DEFAULT_MAX_FRAME_SIZE)
            .readTimeoutMs(RconConstants.DEFAULT_CONNECTION_TIMEOUT_MS)
            .connectionTimeoutMs(RconConstants.DEFAULT_CONNECTION_TIMEOUT_MS)
            .passwordHash(null)
            .build();
    server = new RconServer(config);
    server.start();
  }

  public void tearDown() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void testBasicConnectionLifecycle() throws Exception {
    int port = getServerPort();

    Socket client = new Socket(RconConstants.TEST_HOST, port);
    assertTrue("Client should connect", client.isConnected());

    client.close();
    Thread.sleep(100);

    assertTrue("Server should still be running", server.isRunning());

    tearDown();
  }

  @Test
  public void testEchoFlow() throws Exception {
    int port = getServerPort();
    Socket client = new Socket(RconConstants.TEST_HOST, port);

    try {
      RconPacket authPacket = new RconPacket(100, RconPacket.SERVERDATA_AUTH, "");
      client.getOutputStream().write(authPacket.toBytes());

      byte[] authResponse = readPacket(client);
      RconPacket parsedAuth = RconPacket.fromBytes(authResponse);
      assertEquals("Auth response ID should match", 100, parsedAuth.getId());
      assertEquals("Auth response type", RconPacket.SERVERDATA_AUTH_RESPONSE, parsedAuth.getType());

      RconPacket commandPacket =
          new RconPacket(101, RconPacket.SERVERDATA_EXECCOMMAND, "echo hello world");
      client.getOutputStream().write(commandPacket.toBytes());

      byte[] commandResponse = readPacket(client);
      RconPacket parsedCommand = RconPacket.fromBytes(commandResponse);
      assertEquals("Command response ID should match", 101, parsedCommand.getId());
      assertEquals(
          "Command response type", RconPacket.SERVERDATA_RESPONSE_VALUE, parsedCommand.getType());
      assertEquals("Command should echo", "hello world", parsedCommand.getBody());

    } finally {
      client.close();
      tearDown();
    }
  }

  @Test
  public void testMultipleCommandsOnSameConnection() throws Exception {
    int port = getServerPort();
    Socket client = new Socket(RconConstants.TEST_HOST, port);

    try {
      RconPacket authPacket = new RconPacket(100, RconPacket.SERVERDATA_AUTH, "");
      client.getOutputStream().write(authPacket.toBytes());
      readPacket(client);

      String[] commands = {"first", "second", "third"};
      int requestId = 200;

      for (String command : commands) {
        RconPacket commandPacket =
            new RconPacket(requestId, RconPacket.SERVERDATA_EXECCOMMAND, "echo " + command);
        client.getOutputStream().write(commandPacket.toBytes());

        byte[] response = readPacket(client);
        RconPacket parsed = RconPacket.fromBytes(response);
        assertEquals("Response should match request", requestId, parsed.getId());
        assertEquals("Response should echo command", command, parsed.getBody());

        requestId++;
      }

    } finally {
      client.close();
      tearDown();
    }
  }

  @Test
  public void testServerShutdownWithActiveConnections() throws Exception {
    int port = getServerPort();
    Socket client1 = new Socket(RconConstants.TEST_HOST, port);
    Socket client2 = new Socket(RconConstants.TEST_HOST, port);

    Thread.sleep(100);

    try {
      assertTrue("Both clients should connect", client1.isConnected() && client2.isConnected());

      RconApplication.ApplicationStats stats = server.getStats();
      assertEquals("Should have 2 connections", 2, stats.connectionCount);

    } finally {
      server.stop();

      assertFalse("Server should not be running", server.isRunning());

      client1.close();
      client2.close();
    }
  }

  private byte[] readPacket(Socket socket) throws IOException {
    byte[] sizeBytes = new byte[4];
    int bytesRead = 0;
    while (bytesRead < 4) {
      int read = socket.getInputStream().read(sizeBytes, bytesRead, 4 - bytesRead);
      if (read == -1) throw new IOException("Connection closed");
      bytesRead += read;
    }

    int size =
        (sizeBytes[0] & 0xFF)
            | ((sizeBytes[1] & 0xFF) << 8)
            | ((sizeBytes[2] & 0xFF) << 16)
            | ((sizeBytes[3] & 0xFF) << 24);

    byte[] packetData = new byte[4 + size];
    System.arraycopy(sizeBytes, 0, packetData, 0, 4);

    bytesRead = 0;
    while (bytesRead < size) {
      int read = socket.getInputStream().read(packetData, 4 + bytesRead, size - bytesRead);
      if (read == -1) throw new IOException("Connection closed");
      bytesRead += read;
    }

    return packetData;
  }

  private int getServerPort() {
    return config.getPort();
  }
}
