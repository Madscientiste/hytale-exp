package com.madscientiste.rcon.application;

import com.madscientiste.rcon.command.CommandDispatcher;
import com.madscientiste.rcon.infrastructure.AuthenticationService;
import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.infrastructure.RconLogger;
import com.madscientiste.rcon.protocol.ConnectionState;
import com.madscientiste.rcon.protocol.RconPacket;
import com.madscientiste.rcon.protocol.RconProtocol;
import com.madscientiste.rcon.transport.RconTransport;
import com.madscientiste.rcon.transport.TransportCallbacks;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application layer - thin coordinator between transport, protocol, and command layers. Glue
 * protocol ↔ execution, connection-scoped state, routing messages to handlers.
 */
public class RconApplication implements TransportCallbacks {

  private final RconTransport transport;
  private final RconProtocol protocol;
  private final CommandDispatcher commandDispatcher;
  private final RconLogger logger;
  private final RconConfig config;

  // Per-connection state (connection-scoped, not session-scoped)
  private final ConcurrentHashMap<String, ConnectionState> connectionStates =
      new ConcurrentHashMap<>();

  public RconApplication(
      RconTransport transport,
      RconProtocol protocol,
      CommandDispatcher commandDispatcher,
      RconLogger logger,
      RconConfig config) {
    this.transport = transport;
    this.protocol = protocol;
    this.commandDispatcher = commandDispatcher;
    this.logger = logger;
    this.config = config;
  }

  /** Start the application layer. */
  public void start() throws Exception {
    transport.start();
    logger.logEvent("APP_STARTED", "system", java.util.Map.of("transport", "started"));
  }

  /** Stop the application layer. */
  public void stop() {
    transport.stop();
    logger.logEvent("APP_STOPPED", "system", java.util.Map.of("transport", "stopped"));
  }

  // TransportCallbacks implementation

  @Override
  public void onBytesReceived(String connectionId, byte[] data) {
    try {
      // Parse bytes using protocol layer
      RconProtocol.ProtocolResult result = protocol.parseBytes(connectionId, data);

      if (result instanceof RconProtocol.ProtocolSuccess success) {
        handlePackets(connectionId, success.getPackets());
      } else if (result instanceof RconProtocol.ProtocolError error) {
        handleProtocolError(connectionId, error.getErrorMessage());
      }

    } catch (Exception e) {
      // Never crash - isolate to connection
      logger.logError("Error processing bytes for " + connectionId, e);
      closeConnection(connectionId, "Processing error");
    }
  }

  @Override
  public void onConnectionClosed(String connectionId, String reason) {
    // Clean up connection state (connection-scoped, not session-scoped)
    ConnectionState state = connectionStates.remove(connectionId);
    if (state != null) {
      state.close();
    }

    logger.logConnectionClosed(connectionId, reason);
  }

  // Private methods

  /** Handle parsed packets from protocol layer. */
  private void handlePackets(String connectionId, java.util.List<RconPacket> packets)
      throws Exception {
    ConnectionState state =
        connectionStates.computeIfAbsent(connectionId, k -> new ConnectionState());

    for (RconPacket packet : packets) {
      // Process state transition
      ConnectionState.TransitionResult transition = state.processPacket(packet);

      if (!transition.isSuccess()) {
        logger.logProtocolViolation(connectionId, transition.getMessage());
        closeConnection(connectionId, "Protocol violation");
        return;
      }

      // Handle packet based on type and state
      if (packet.getType() == RconPacket.SERVERDATA_AUTH) {
        // Reject re-authentication attempts
        if (state.isReadyForCommand()) {
          logger.logProtocolViolation(connectionId, "Re-authentication not allowed");
          closeConnection(connectionId, "Re-authentication not allowed");
          return;
        }
        handleAuthPacket(connectionId, packet, state);
      } else if (packet.getType() == RconPacket.SERVERDATA_EXECCOMMAND
          && state.isReadyForCommand()) {
        handleCommandPacket(connectionId, packet);
      } else if (packet.getType() == RconPacket.SERVERDATA_RESPONSE_VALUE) {
        // Response to previous command - just acknowledge
        logger.logDebug(connectionId, "Response received");
      }
    }
  }

  /** Handle authentication packet with password validation. */
  private void handleAuthPacket(String connectionId, RconPacket packet, ConnectionState state)
      throws Exception {
    int responseId = packet.getId();
    String providedPassword = packet.getBody();
    boolean authSuccess = false;

    // If no password is configured, allow authentication (backward compatibility
    // for development/testing)
    if (!config.requiresPassword()) {
      authSuccess = true;
      logger.logDebug(connectionId, "Auth accepted: no password configured (insecure mode)");
    } else {
      // Validate password
      String storedHash = config.getPasswordHash();
      if (storedHash != null && !storedHash.isEmpty()) {
        authSuccess = AuthenticationService.verifyPassword(providedPassword, storedHash);
      }
    }

    // Send auth response
    RconPacket response = protocol.createAuthResponse(responseId, authSuccess);
    byte[] responseData = protocol.formatPacket(response);
    transport.send(connectionId, responseData);

    if (authSuccess) {
      // Mark connection as authenticated after sending response
      state.markAuthenticated();
      logger.logEvent("AUTH_SUCCESS", connectionId, java.util.Map.of("requestId", responseId));
    } else {
      logger.logEvent("AUTH_FAILURE", connectionId, java.util.Map.of("requestId", responseId));
      closeConnection(connectionId, "Authentication failed");
    }
  }

  /** Handle command execution packet (MVP: echo). */
  private void handleCommandPacket(String connectionId, RconPacket packet) throws Exception {
    String command = packet.getBody();
    String response = commandDispatcher.execute(command);

    RconPacket responsePacket = protocol.createCommandResponse(packet.getId(), response);
    byte[] responseData = protocol.formatPacket(responsePacket);
    transport.send(connectionId, responseData);

    logger.logDebug(connectionId, "Command executed: " + command);
  }

  /** Handle protocol parsing errors. */
  private void handleProtocolError(String connectionId, String error) {
    logger.logProtocolViolation(connectionId, error);
    closeConnection(connectionId, "Protocol error");
  }

  /** Close a connection. */
  private void closeConnection(String connectionId, String reason) {
    transport.closeConnection(connectionId, reason);
  }

  /** Get application statistics. */
  public ApplicationStats getStats() {
    return new ApplicationStats(transport.getConnectionCount(), connectionStates.size());
  }

  /** Application statistics. */
  public static class ApplicationStats {
    public final int connectionCount;
    public final int connectionStateCount;

    public ApplicationStats(int connectionCount, int connectionStateCount) {
      this.connectionCount = connectionCount;
      this.connectionStateCount = connectionStateCount;
    }

    @Override
    public String toString() {
      return "ApplicationStats{connections="
          + connectionCount
          + ", connectionStates="
          + connectionStateCount
          + "}";
    }
  }
}
