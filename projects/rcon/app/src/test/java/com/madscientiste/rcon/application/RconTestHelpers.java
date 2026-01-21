package com.madscientiste.rcon.application;

import com.madscientiste.rcon.RconServer;
import com.madscientiste.rcon.infrastructure.AuthenticationService;
import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.infrastructure.RconConstants;
import com.madscientiste.rcon.logging.RconLogger;
import com.madscientiste.rcon.protocol.ProtocolException;
import com.madscientiste.rcon.protocol.RconPacket;
import java.io.IOException;
import java.net.Socket;

public class RconTestHelpers {

  public static RconLogger createTestLogger() {
    return RconLogger.createPluginLogger(RconConstants.LOGGER_APPLICATION);
  }

  public static RconServer createServerWithPassword(String password, int port) throws Exception {
    String passwordHash = AuthenticationService.hashPassword(password);
    RconConfig config =
        RconConfig.builder()
            .host(RconConstants.TEST_HOST)
            .port(port)
            .maxConnections(RconConstants.DEFAULT_MAX_CONNECTIONS)
            .maxFrameSize(RconConstants.DEFAULT_MAX_FRAME_SIZE)
            .readTimeoutMs(RconConstants.DEFAULT_CONNECTION_TIMEOUT_MS)
            .connectionTimeoutMs(RconConstants.DEFAULT_CONNECTION_TIMEOUT_MS)
            .passwordHash(passwordHash)
            .build();
    RconServer server = new RconServer(config);
    server.start();
    return server;
  }

  public static RconServer createServerWithoutPassword(int port) throws Exception {
    RconConfig config =
        RconConfig.builder()
            .host(RconConstants.TEST_HOST)
            .port(port)
            .maxConnections(RconConstants.DEFAULT_MAX_CONNECTIONS)
            .maxFrameSize(RconConstants.DEFAULT_MAX_FRAME_SIZE)
            .readTimeoutMs(RconConstants.DEFAULT_CONNECTION_TIMEOUT_MS)
            .connectionTimeoutMs(RconConstants.DEFAULT_CONNECTION_TIMEOUT_MS)
            .passwordHash(null)
            .build();
    RconServer server = new RconServer(config);
    server.start();
    return server;
  }

  public static void sendPacket(Socket socket, RconPacket packet) throws IOException {
    socket.getOutputStream().write(packet.toBytes());
    socket.getOutputStream().flush();
  }

  public static RconPacket readResponse(Socket socket) throws IOException, ProtocolException {
    if (socket.getSoTimeout() == 0) {
      socket.setSoTimeout(5000);
    }
    byte[] packetData = readPacketBytes(socket);
    return RconPacket.fromBytes(packetData);
  }

  public static byte[] readPacketBytes(Socket socket) throws IOException {
    if (socket.getSoTimeout() == 0) {
      socket.setSoTimeout(5000);
    }
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

  public static Socket connect(String host, int port) throws IOException {
    return new Socket(host, port);
  }

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

  public static void assertConnectionClosed(Socket socket) {
    try {
      socket.getInputStream().read();
      throw new AssertionError("Expected connection to be closed");
    } catch (IOException e) {
    }
  }

  public static void assertCommandRejected(Socket socket, String command) throws IOException {
    RconPacket commandPacket = new RconPacket(200, RconPacket.SERVERDATA_EXECCOMMAND, command);
    sendPacket(socket, commandPacket);

    try {
      RconPacket response = readResponse(socket);
      if (response.getType() == RconPacket.SERVERDATA_RESPONSE_VALUE
          && !response.getBody().contains("Error")
          && !response.getBody().contains("rejected")) {
        throw new AssertionError("Command was not rejected, got response: " + response.getBody());
      }
    } catch (IOException | ProtocolException e) {
    }
  }
}
