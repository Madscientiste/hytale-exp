package com.madscientiste.rcon.transport;

import com.madscientiste.rcon.infrastructure.AddressRange;
import com.madscientiste.rcon.infrastructure.AuthFailureTracker;
import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.infrastructure.RconConstants;
import com.madscientiste.rcon.logging.LogEvent;
import com.madscientiste.rcon.logging.LogThrottle;
import com.madscientiste.rcon.logging.RconLogger;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class RconTransport {

  private final RconConfig config;
  private final AuthFailureTracker authFailures;
  private final LogThrottle preAuthLogs;
  private final RconLogger logger = RconLogger.createPluginLogger(RconConstants.LOGGER_TRANSPORT);
  private TransportCallbacks callbacks;

  private ServerSocket serverSocket;
  private final ConcurrentHashMap<String, RconConnection> connections = new ConcurrentHashMap<>();
  private final AtomicInteger connectionCounter = new AtomicInteger(0);
  private final AtomicBoolean running = new AtomicBoolean(false);

  private Thread acceptThread;

  /**
   * @param authFailures Shared with the application layer: a banned address is refused on accept
   * @param preAuthLogs Log budget for peers that have not logged in
   */
  public RconTransport(
      RconConfig config,
      TransportCallbacks callbacks,
      AuthFailureTracker authFailures,
      LogThrottle preAuthLogs) {
    this.config = config;
    this.callbacks = callbacks;
    this.authFailures = authFailures;
    this.preAuthLogs = preAuthLogs;
  }

  public void start() throws Exception {
    if (running.compareAndSet(false, true)) {
      InetAddress bindAddress = InetAddress.getByName(config.getHost());
      serverSocket = new ServerSocket(config.getPort(), 50, bindAddress);

      serverSocket.setSoTimeout(config.getConnectionTimeoutMs());
      serverSocket.setReuseAddress(true);

      acceptThread = new Thread(this::acceptLoop, "RconAccept");
      acceptThread.setDaemon(true);
      acceptThread.start();

      logger.atInfo().log("RCON server started on %s:%d", config.getHost(), config.getPort());

      if (!bindAddress.isLoopbackAddress()) {
        logger.atWarning().log(
            "RCON is listening on %s, which is not a loopback address. RCON has no"
                + " encryption: the password and every command cross the network in clear"
                + " text. Bind to 127.0.0.1 and reach it through an SSH tunnel or a VPN such"
                + " as WireGuard, and never open this port to the internet.",
            config.getHost());
      }
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

  /**
   * Send bytes to a connection.
   *
   * @throws IOException if the connection is closed or the peer is gone
   */
  public void send(String connectionId, byte[] data) throws IOException {
    RconConnection connection = connections.get(connectionId);
    if (connection == null) {
      throw new IOException("Connection is closed");
    }
    connection.send(data);
  }

  public void closeConnection(String connectionId, String reason) {
    RconConnection connection = connections.get(connectionId);
    if (connection != null) {
      connection.close(reason);
    }
  }

  /** The peer has logged in: the idle timeout now applies instead of the login deadline. */
  public void markAuthenticated(String connectionId) {
    RconConnection connection = connections.get(connectionId);
    if (connection != null) {
      connection.markAuthenticated();
    }
  }

  /** Called by a connection exactly once, when it closes for any reason. */
  void onConnectionClosed(RconConnection connection, String reason) {
    String connectionId = connection.getId();
    connections.remove(connectionId, connection);
    long sessionDuration = System.currentTimeMillis() - connection.getConnectedAt();

    logger
        .event(LogEvent.TRANSPORT_DISCONNECT)
        .withParam("connection_id", connectionId)
        .withParam("reason", reason)
        .withParam("remote_ip", connection.getRemoteIp())
        .withOptionalParam("session_duration_ms", sessionDuration > 0 ? sessionDuration : null)
        .throttledBy(connection.preAuthLogThrottle(), connection.getAddressKey())
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
        admit(clientSocket);
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

  /** Decide whether a freshly accepted socket gets a connection slot, and set it up if so. */
  private void admit(Socket clientSocket) {
    InetAddress remoteAddress = clientSocket.getInetAddress();
    String remoteIp = remoteAddress.getHostAddress();
    String addressKey = AuthFailureTracker.keyFor(remoteAddress);

    int fromThisAddress = 0;
    for (RconConnection connection : connections.values()) {
      if (connection.getAddressKey().equals(addressKey)) {
        fromThisAddress++;
      }
    }

    // Only the accept thread adds connections, so the counts cannot be overshot
    String refusal =
        refusalReason(
            authFailures.isBanned(addressKey),
            AddressRange.isLoopback(remoteAddress),
            connections.size(),
            fromThisAddress,
            config.getMaxConnections(),
            config.getMaxConnectionsPerIp());
    if (refusal != null) {
      closeQuietly(clientSocket);
      logger
          .event(LogEvent.TRANSPORT_RATE_LIMIT)
          .withParam("remote_ip", remoteIp)
          .withParam("action", "connection_rejected")
          .withParam("reason", refusal)
          .withParam("count", connections.size())
          .withParam("time_window_ms", 0)
          .throttledBy(preAuthLogs, addressKey)
          .log();
      return;
    }

    String connectionId = generateConnectionId();
    RconConnection connection;
    try {
      clientSocket.setTcpNoDelay(true);
      connection =
          new RconConnection(connectionId, clientSocket, callbacks, this, config, preAuthLogs);
    } catch (IOException e) {
      // The peer was gone before we could set the socket up
      closeQuietly(clientSocket);
      return;
    }

    connections.put(connectionId, connection);

    logger
        .event(LogEvent.TRANSPORT_CONNECT)
        .withParam("connection_id", connectionId)
        .withParam("remote_ip", remoteIp)
        .withParam("remote_port", clientSocket.getPort())
        .throttledBy(preAuthLogs, addressKey)
        .log();

    // The session state must exist before the first byte is read
    if (callbacks != null) {
      callbacks.onConnectionOpened(connectionId, remoteAddress);
    }
    connection.start();

    // stop() may have closed everything while this connection was being set up
    if (!running.get()) {
      connection.close("Server shutdown");
    }
  }

  /**
   * Why a new connection must be refused, or null to accept it.
   *
   * <p>The last free slot is kept for this machine (loopback): however many outside addresses fill
   * the server, a local client such as a health check can still get in. With a single slot in total
   * there is nothing to keep back.
   *
   * @param banned The address is banned for wrong passwords
   * @param loopback The peer is on this machine
   * @param open Connections open right now
   * @param openFromThisAddress Of those, how many count under the same address key
   */
  static String refusalReason(
      boolean banned,
      boolean loopback,
      int open,
      int openFromThisAddress,
      int maxConnections,
      int maxConnectionsPerIp) {
    if (banned) {
      return "address_banned";
    }
    if (open >= maxConnections) {
      return "max_connections";
    }
    if (!loopback && maxConnections > 1 && open >= maxConnections - 1) {
      return "last_slot_reserved_for_loopback";
    }
    if (openFromThisAddress >= maxConnectionsPerIp) {
      return "max_connections_per_ip";
    }
    return null;
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException e) {
      // Nothing useful to do
    }
  }

  private String generateConnectionId() {
    return "conn-" + connectionCounter.incrementAndGet() + "-" + System.currentTimeMillis();
  }
}
