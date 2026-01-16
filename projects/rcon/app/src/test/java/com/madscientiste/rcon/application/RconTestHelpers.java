package com.madscientiste.rcon.application;

import com.madscientiste.rcon.RconServer;
import com.madscientiste.rcon.infrastructure.AuthenticationService;
import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.protocol.ProtocolException;
import com.madscientiste.rcon.protocol.RconPacket;
import java.io.IOException;
import java.net.Socket;

/** Shared test utilities for RCON integration tests. */
public class RconTestHelpers {

  /** Create a test server with password authentication. */
  public static RconServer createServerWithPassword(String password, int port) throws Exception {
    String passwordHash = AuthenticationService.hashPassword(password);
    RconConfig config = new RconConfig("127.0.0.1", port, 10, 4096, 5000, 5000, passwordHash);
    RconServer server = new RconServer(config);
    server.start();
    return server;
  }

  /** Create a test server without password (insecure mode for tests). */
  public static RconServer createServerWithoutPassword(int port) throws Exception {
    RconConfig config = new RconConfig("127.0.0.1", port, 10, 4096, 5000, 5000, null);
    RconServer server = new RconServer(config);
    server.start();
    return server;
  }

  /** Send a packet over the socket. */
  public static void sendPacket(Socket socket, RconPacket packet) throws IOException {
    socket.getOutputStream().write(packet.toBytes());
    socket.getOutputStream().flush();
  }

  /** Read a response packet from the socket. */
  public static RconPacket readResponse(Socket socket) throws IOException, ProtocolException {
    // Ensure socket has timeout to prevent hanging
    if (socket.getSoTimeout() == 0) {
      socket.setSoTimeout(5000); // 5 second timeout
    }
    byte[] packetData = readPacketBytes(socket);
    return RconPacket.fromBytes(packetData);
  }

  /** Read raw packet bytes from socket. */
  public static byte[] readPacketBytes(Socket socket) throws IOException {
    // Ensure socket has timeout to prevent hanging
    if (socket.getSoTimeout() == 0) {
      socket.setSoTimeout(5000); // 5 second timeout
    }
    // Read size field (4 bytes, little endian)
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

    // Read the rest of the packet
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

  /** Connect to server and authenticate with password. */
  public static Socket connectAndAuth(String host, int port, String password) throws Exception {
    Socket socket = new Socket(host, port);
    RconPacket authPacket = new RconPacket(100, RconPacket.SERVERDATA_AUTH, password);
    sendPacket(socket, authPacket);

    RconPacket response = readResponse(socket);
    if (response.getType() != RconPacket.SERVERDATA_AUTH_RESPONSE
        || !response.getBody().equals("1")) {
      socket.close();
      throw new IOException("Authentication failed");
    }

    return socket;
  }

  /** Connect to server without authentication (for testing). */
  public static Socket connect(String host, int port) throws IOException {
    return new Socket(host, port);
  }

  /** Assert authentication success. */
  public static void assertAuthSuccess(Socket socket, int requestId)
      throws IOException, ProtocolException {
    RconPacket response = readResponse(socket);
    if (response.getId() != requestId) {
      throw new AssertionError(
          "Response ID mismatch: expected " + requestId + ", got " + response.getId());
    }
    if (response.getType() != RconPacket.SERVERDATA_AUTH_RESPONSE) {
      throw new AssertionError("Expected AUTH_RESPONSE, got type " + response.getType());
    }
    if (!response.getBody().equals("1")) {
      throw new AssertionError("Authentication failed, body: " + response.getBody());
    }
  }

  /** Assert authentication failure. */
  public static void assertAuthFailure(Socket socket, int requestId)
      throws IOException, ProtocolException {
    RconPacket response = readResponse(socket);
    if (response.getId() != requestId) {
      throw new AssertionError(
          "Response ID mismatch: expected " + requestId + ", got " + response.getId());
    }
    if (response.getType() != RconPacket.SERVERDATA_AUTH_RESPONSE) {
      throw new AssertionError("Expected AUTH_RESPONSE, got type " + response.getType());
    }
    if (!response.getBody().equals("-1")) {
      throw new AssertionError("Expected auth failure (-1), got: " + response.getBody());
    }
  }

  /** Assert connection is closed (read should fail). */
  public static void assertConnectionClosed(Socket socket) {
    try {
      socket.getInputStream().read();
      throw new AssertionError("Expected connection to be closed");
    } catch (IOException e) {
      // Expected - connection is closed
    }
  }

  /** Send command and assert it's rejected (connection should close or return error). */
  public static void assertCommandRejected(Socket socket, String command) throws IOException {
    RconPacket commandPacket = new RconPacket(200, RconPacket.SERVERDATA_EXECCOMMAND, command);
    sendPacket(socket, commandPacket);

    // Command should either:
    // 1. Close connection (read will fail)
    // 2. Return error response
    // Either is acceptable for rejection
    try {
      RconPacket response = readResponse(socket);
      // If we get a response, it should be an error or the connection should close soon
      // For now, just verify we don't get a successful command response
      if (response.getType() == RconPacket.SERVERDATA_RESPONSE_VALUE
          && !response.getBody().contains("Error")
          && !response.getBody().contains("rejected")) {
        throw new AssertionError("Command was not rejected, got response: " + response.getBody());
      }
    } catch (IOException | ProtocolException e) {
      // Connection closed is also acceptable rejection
    }
  }
}
