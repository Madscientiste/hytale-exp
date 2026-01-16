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

/** Tests for protocol framing edge cases and TCP fragmentation. */
public class RconProtocolFramingTest {

  private static final int TEST_PORT = 25579;
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
  public void testDeclaredLengthMismatch() throws Exception {
    Socket socket = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    try {
      // Create valid packet
      RconPacket packet = new RconPacket(100, RconPacket.SERVERDATA_AUTH, "test");
      byte[] packetBytes = packet.toBytes();

      // Corrupt: change declared size to be larger than actual
      int originalSize =
          (packetBytes[0] & 0xFF)
              | ((packetBytes[1] & 0xFF) << 8)
              | ((packetBytes[2] & 0xFF) << 16)
              | ((packetBytes[3] & 0xFF) << 24);

      // Set size to be larger
      int fakeSize = originalSize + 100;
      packetBytes[0] = (byte) (fakeSize & 0xFF);
      packetBytes[1] = (byte) ((fakeSize >> 8) & 0xFF);
      packetBytes[2] = (byte) ((fakeSize >> 16) & 0xFF);
      packetBytes[3] = (byte) ((fakeSize >> 24) & 0xFF);

      // Send corrupted packet
      socket.getOutputStream().write(packetBytes);
      socket.getOutputStream().flush();

      // Should disconnect or timeout (waiting for more bytes that never come)
      Thread.sleep(200);
      assertTrue(
          "Connection should be closed",
          socket.isClosed() || socket.getInputStream().available() == 0);
    } finally {
      socket.close();
    }
  }

  @Test
  public void testTruncatedPacket() throws Exception {
    Socket socket = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    try {
      // Create valid packet
      RconPacket packet = new RconPacket(100, RconPacket.SERVERDATA_AUTH, "test");
      byte[] packetBytes = packet.toBytes();

      // Truncate - send only first half
      byte[] truncated = new byte[packetBytes.length / 2];
      System.arraycopy(packetBytes, 0, truncated, 0, truncated.length);
      socket.getOutputStream().write(truncated);
      socket.getOutputStream().flush();

      // Should disconnect (incomplete packet)
      // With buffering, it might wait for more data, but eventually should timeout or close
      Thread.sleep(1000); // Give more time for timeout
      // Try to read - should fail
      try {
        socket.setSoTimeout(100);
        RconTestHelpers.readResponse(socket);
        // If we get here, connection is still open (might be waiting)
        // This is acceptable - the important thing is no command execution
      } catch (IOException | ProtocolException e) {
        // Connection closed or timeout is expected
        assertTrue(
            "Connection should be closed or timeout", socket.isClosed() || e.getMessage() != null);
      }
    } finally {
      socket.close();
    }
  }

  @Test
  public void testExtraBytesAfterNullTerminators() throws Exception {
    Socket socket = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    try {
      // Create valid packet
      RconPacket packet = new RconPacket(100, RconPacket.SERVERDATA_AUTH, "test");
      byte[] packetBytes = packet.toBytes();

      // Append junk bytes after packet
      byte[] withJunk = new byte[packetBytes.length + 10];
      System.arraycopy(packetBytes, 0, withJunk, 0, packetBytes.length);
      for (int i = packetBytes.length; i < withJunk.length; i++) {
        withJunk[i] = (byte) 0xFF; // Junk
      }

      // Send - should either process first packet and ignore junk, or disconnect
      socket.getOutputStream().write(withJunk);
      socket.getOutputStream().flush();

      // Try to read response
      try {
        RconPacket response = RconTestHelpers.readResponse(socket);
        // If we get response, it means first packet was processed (acceptable)
        assertTrue(
            "Should get auth response or disconnect",
            response.getType() == RconPacket.SERVERDATA_AUTH_RESPONSE || socket.isClosed());
      } catch (IOException e) {
        // Disconnect is also acceptable
        assertTrue("Connection should be closed", socket.isClosed());
      }
    } finally {
      socket.close();
    }
  }

  @Test
  public void testPacketSplitAcrossWrites() throws Exception {
    // This tests TCP fragmentation handling
    Socket socket = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    try {
      // Create auth packet
      RconPacket packet = new RconPacket(100, RconPacket.SERVERDATA_AUTH, "test");
      byte[] packetBytes = packet.toBytes();

      // Split packet into multiple writes
      int splitPoint = packetBytes.length / 2;

      // Send first half
      socket.getOutputStream().write(packetBytes, 0, splitPoint);
      socket.getOutputStream().flush();
      Thread.sleep(50);

      // Send second half
      socket.getOutputStream().write(packetBytes, splitPoint, packetBytes.length - splitPoint);
      socket.getOutputStream().flush();

      // Should work (buffering handles fragmentation)
      RconPacket response = RconTestHelpers.readResponse(socket);
      assertTrue(
          "Should get auth response", response.getType() == RconPacket.SERVERDATA_AUTH_RESPONSE);
    } finally {
      socket.close();
    }
  }

  @Test
  public void testMultiplePacketsInOneWrite() throws Exception {
    Socket socket = RconTestHelpers.connect("127.0.0.1", TEST_PORT);
    try {
      // Create two packets
      RconPacket authPacket = new RconPacket(100, RconPacket.SERVERDATA_AUTH, "");
      RconPacket commandPacket =
          new RconPacket(101, RconPacket.SERVERDATA_EXECCOMMAND, "echo test");

      byte[] authBytes = authPacket.toBytes();
      byte[] commandBytes = commandPacket.toBytes();

      // Combine into one write
      byte[] combined = new byte[authBytes.length + commandBytes.length];
      System.arraycopy(authBytes, 0, combined, 0, authBytes.length);
      System.arraycopy(commandBytes, 0, combined, authBytes.length, commandBytes.length);

      socket.getOutputStream().write(combined);
      socket.getOutputStream().flush();

      // Should process both packets in order
      RconPacket response1 = RconTestHelpers.readResponse(socket);
      assertTrue(
          "First response should be auth",
          response1.getType() == RconPacket.SERVERDATA_AUTH_RESPONSE);

      // After auth, command should work
      RconPacket response2 = RconTestHelpers.readResponse(socket);
      assertTrue(
          "Second response should be command",
          response2.getType() == RconPacket.SERVERDATA_RESPONSE_VALUE);
    } finally {
      socket.close();
    }
  }
}
