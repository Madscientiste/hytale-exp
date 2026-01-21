package com.madscientiste.rcon.transport;

import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.infrastructure.RconConstants;
import com.madscientiste.rcon.logging.LogEvent;
import com.madscientiste.rcon.logging.RconLogger;
import com.madscientiste.rcon.protocol.RconPacket;
import com.madscientiste.rcon.protocol.RconProtocol;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class RconTransport {

  private final RconConfig config;
  private final RconLogger logger = RconLogger.createPluginLogger(RconConstants.LOGGER_TRANSPORT);
  private TransportCallbacks callbacks;
  private RconProtocol protocol;

  private ServerSocket serverSocket;
  private final ConcurrentHashMap<String, RconConnection> connections = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Long> connectionStartTimes = new ConcurrentHashMap<>();
  private final AtomicInteger connectionCounter = new AtomicInteger(0);
  private final AtomicBoolean running = new AtomicBoolean(false);

  private Thread acceptThread;

  public RconTransport(RconConfig config, TransportCallbacks callbacks) {
    this.config = config;
    this.callbacks = callbacks;
    this.protocol = new RconProtocol();
  }

  public void start() throws Exception {
    if (running.compareAndSet(false, true)) {
      serverSocket =
          new ServerSocket(config.getPort(), 50, InetAddress.getByName(config.getHost()));

      serverSocket.setSoTimeout(config.getConnectionTimeoutMs());
      serverSocket.setReuseAddress(true);

      acceptThread = new Thread(this::acceptLoop, "RconAccept");
      acceptThread.setDaemon(true);
      acceptThread.start();

      logger.atInfo().log("RCON server started on %s:%d", config.getHost(), config.getPort());
    }
  }

  public void stop() {
    if (running.compareAndSet(true, false)) {
      try {
        if (serverSocket != null) {
          serverSocket.close();
        }

        connections.forEach((id, conn) -> conn.close("Server shutdown"));
        connections.clear();

        if (acceptThread != null) {
          acceptThread.interrupt();
          acceptThread.join(1000);
        }

        logger.atInfo().log("RCON server stopped");

      } catch (Exception e) {
        logger
            .event(LogEvent.TRANSPORT_ERROR)
            .withParam("error_type", "stop_failure")
            .withParam("message", "Error stopping transport")
            .withCause(e)
            .log();
      }
    }
  }

  public void send(String connectionId, byte[] data) throws Exception {
    RconConnection connection = connections.get(connectionId);
    if (connection == null) {
      throw new Exception("Connection not found: " + connectionId);
    }
    connection.send(data);
  }

  public void closeConnection(String connectionId, String reason) {
    RconConnection connection = connections.remove(connectionId);
    if (connection != null) {
      connection.close(reason);
    }
  }

  public void onConnectionClosed(String connectionId, String reason) {
    connections.remove(connectionId);
    Long startTime = connectionStartTimes.remove(connectionId);
    long sessionDuration = startTime != null ? System.currentTimeMillis() - startTime : 0;

    logger
        .event(LogEvent.TRANSPORT_DISCONNECT)
        .withParam("connection_id", connectionId)
        .withParam("reason", reason)
        .withOptionalParam("session_duration_ms", sessionDuration > 0 ? sessionDuration : null)
        .log();

    if (callbacks != null) {
      callbacks.onConnectionClosed(connectionId, reason);
    }
  }

  public void setCallbacks(TransportCallbacks callbacks) {
    this.callbacks = callbacks;
  }

  public int getConnectionCount() {
    return connections.size();
  }

  public boolean canAcceptConnection() {
    return connections.size() < config.getMaxConnections();
  }

  private void acceptLoop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        Socket clientSocket = serverSocket.accept();

        if (!canAcceptConnection()) {
          clientSocket.close();
          String remoteIp = clientSocket.getInetAddress().getHostAddress();
          logger
              .event(LogEvent.TRANSPORT_RATE_LIMIT)
              .withParam("remote_ip", remoteIp)
              .withParam("action", "connection_rejected")
              .withParam("count", connections.size())
              .withParam("time_window_ms", 0)
              .log();
          continue;
        }

        clientSocket.setSoTimeout(config.getReadTimeoutMs());
        clientSocket.setTcpNoDelay(true);

        String connectionId = generateConnectionId();
        RconConnection connection = new RconConnection(connectionId, clientSocket, callbacks, this);

        connections.put(connectionId, connection);
        connectionStartTimes.put(connectionId, System.currentTimeMillis());
        connection.start();

        String remoteIp = clientSocket.getInetAddress().getHostAddress();
        int remotePort = clientSocket.getPort();
        logger
            .event(LogEvent.TRANSPORT_CONNECT)
            .withParam("connection_id", connectionId)
            .withParam("remote_ip", remoteIp)
            .withParam("remote_port", remotePort)
            .log();

      } catch (java.net.SocketTimeoutException e) {
        continue;
      } catch (Exception e) {
        if (running.get()) {
          logger
              .event(LogEvent.TRANSPORT_ERROR)
              .withParam("error_type", "accept_failure")
              .withParam("message", "Error accepting connection")
              .withCause(e)
              .log();
        }
      }
    }
  }

  private String generateConnectionId() {
    return "conn-" + connectionCounter.incrementAndGet() + "-" + System.currentTimeMillis();
  }

  public void onBytesReceived(String connectionId, byte[] data) {
    RconProtocol.ProtocolResult result = protocol.parseBytes(connectionId, data);

    if (result instanceof RconProtocol.ProtocolSuccess success) {
      handlePackets(connectionId, success.getPackets());
    } else if (result instanceof RconProtocol.ProtocolError error) {
      handleProtocolError(connectionId, error.getErrorMessage());
    }
  }

  private void handlePackets(String connectionId, List<RconPacket> packets) {
    if (callbacks != null) {
      callbacks.onBytesReceived(connectionId, serializePackets(packets));
    }
  }

  private void handleProtocolError(String connectionId, String error) {
    // Protocol errors should be logged at protocol layer, not transport
    // This is just a pass-through, so we don't log here
    closeConnection(connectionId, "Protocol error");
  }

  private byte[] serializePackets(List<RconPacket> packets) {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      for (RconPacket packet : packets) {
        baos.write(packet.toBytes());
      }
      return baos.toByteArray();
    } catch (Exception e) {
      logger
          .event(LogEvent.TRANSPORT_ERROR)
          .withParam("error_type", "serialization_failure")
          .withParam("message", "Failed to serialize packets")
          .withCause(e)
          .log();
      return new byte[0];
    }
  }

  public void cleanupIdleConnections() {
    long now = System.currentTimeMillis();
    long idleTimeout = config.getReadTimeoutMs();

    connections.forEach(
        (id, conn) -> {
          if (!conn.isClosed() && (now - conn.getLastActivity()) > idleTimeout) {
            conn.close("Idle timeout");
          }
        });
  }
}
