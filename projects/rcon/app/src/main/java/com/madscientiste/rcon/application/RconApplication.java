package com.madscientiste.rcon.application;

import com.madscientiste.rcon.command.CommandDispatcher;
import com.madscientiste.rcon.infrastructure.AuthenticationService;
import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.infrastructure.RconConstants;
import com.madscientiste.rcon.logging.LogEvent;
import com.madscientiste.rcon.logging.RconLogger;
import com.madscientiste.rcon.protocol.ConnectionState;
import com.madscientiste.rcon.protocol.RconPacket;
import com.madscientiste.rcon.protocol.RconProtocol;
import com.madscientiste.rcon.transport.RconTransport;
import com.madscientiste.rcon.transport.TransportCallbacks;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RconApplication implements TransportCallbacks {

  private final RconTransport transport;
  private final RconProtocol protocol;
  private final CommandDispatcher commandDispatcher;

  private final RconLogger logger = RconLogger.createPluginLogger(RconConstants.LOGGER_APPLICATION);

  private final RconConfig config;

  private final ConcurrentHashMap<String, ConnectionState> connectionStates =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Long> sessionStartTimes = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicInteger> sessionCommandCounts =
      new ConcurrentHashMap<>();

  public RconApplication(
      RconTransport transport,
      RconProtocol protocol,
      CommandDispatcher commandDispatcher,
      RconConfig config) {
    this.transport = transport;
    this.protocol = protocol;
    this.commandDispatcher = commandDispatcher;
    this.config = config;
  }

  public void start() throws Exception {
    transport.start();
    logger.atInfo().log("Application started: transport layer ready");
  }

  public void stop() {
    transport.stop();
    logger.atInfo().log("Application stopped");
  }

  @Override
  public void onBytesReceived(String connectionId, byte[] data) {
    try {
      RconProtocol.ProtocolResult result = protocol.parseBytes(connectionId, data);

      if (result instanceof RconProtocol.ProtocolSuccess success) {
        handlePackets(connectionId, success.getPackets());
      } else if (result instanceof RconProtocol.ProtocolError error) {
        handleProtocolError(connectionId, error.getErrorMessage());
      }

    } catch (Exception e) {
      logger
          .event(LogEvent.APPLICATION_ERROR)
          .withParam("error_code", "byte_processing_failure")
          .withParam("message", "Error processing bytes")
          .withOptionalParam("connection_id", connectionId)
          .withCause(e)
          .log();
      closeConnection(connectionId, "Processing error");
    }
  }

  @Override
  public void onConnectionClosed(String connectionId, String reason) {
    ConnectionState state = connectionStates.remove(connectionId);
    sessionStartTimes.remove(connectionId);
    AtomicInteger commandCount = sessionCommandCounts.remove(connectionId);

    if (state != null) {
      state.close();
    }

    int commandsExecuted = commandCount != null ? commandCount.get() : 0;
    logger
        .event(LogEvent.APPLICATION_SESSION_END)
        .withParam("connection_id", connectionId)
        .withParam("reason", reason)
        .withOptionalParam("commands_executed", commandsExecuted > 0 ? commandsExecuted : null)
        .log();
  }

  private void handlePackets(String connectionId, java.util.List<RconPacket> packets)
      throws Exception {
    ConnectionState state =
        connectionStates.computeIfAbsent(connectionId, k -> new ConnectionState());

    for (RconPacket packet : packets) {
      ConnectionState.TransitionResult transition = state.processPacket(packet);

      if (!transition.isSuccess()) {
        logger
            .event(LogEvent.PROTOCOL_PACKET_INVALID)
            .withParam("violation_type", "state_machine_violation")
            .withParam("connection_id", connectionId)
            .withParam("message", transition.getMessage())
            .log();
        closeConnection(connectionId, "Protocol violation");
        return;
      }

      if (packet.getType() == RconPacket.SERVERDATA_AUTH) {
        if (state.isReadyForCommand()) {
          logger
              .event(LogEvent.PROTOCOL_PACKET_INVALID)
              .withParam("violation_type", "re_authentication_not_allowed")
              .withParam("connection_id", connectionId)
              .log();
          closeConnection(connectionId, "Re-authentication not allowed");
          return;
        }
        handleAuthPacket(connectionId, packet, state);
      } else if (packet.getType() == RconPacket.SERVERDATA_EXECCOMMAND
          && state.isReadyForCommand()) {
        handleCommandPacket(connectionId, packet);
      } else if (packet.getType() == RconPacket.SERVERDATA_RESPONSE_VALUE) {
        // Fine detail logging - not a structured event
        logger.atFine().log("Response received for connection: %s", connectionId);
      }
    }
  }

  private void handleAuthPacket(String connectionId, RconPacket packet, ConnectionState state)
      throws Exception {
    int responseId = packet.getId();
    String providedPassword = packet.getBody();
    boolean authSuccess = false;

    if (!config.requiresPassword()) {
      authSuccess = true;
      logger
          .event(LogEvent.PROTOCOL_AUTH)
          .withParam("connection_id", connectionId)
          .withParam("result", "success")
          .withOptionalParam("failure_reason", "no_password_configured")
          .atLevel(java.util.logging.Level.WARNING)
          .log();
    } else {
      String storedHash = config.getPasswordHash();
      if (storedHash != null && !storedHash.isEmpty()) {
        authSuccess = AuthenticationService.verifyPassword(providedPassword, storedHash);
      }
    }

    RconPacket response = protocol.createAuthResponse(responseId, authSuccess);
    byte[] responseData = protocol.formatPacket(response);
    transport.send(connectionId, responseData);

    if (authSuccess) {
      state.markAuthenticated();
      sessionStartTimes.put(connectionId, System.currentTimeMillis());
      sessionCommandCounts.put(connectionId, new AtomicInteger(0));

      logger
          .event(LogEvent.APPLICATION_SESSION_START)
          .withParam("connection_id", connectionId)
          .withParam("authenticated", true)
          .log();

      logger
          .event(LogEvent.PROTOCOL_AUTH)
          .withParam("connection_id", connectionId)
          .withParam("result", "success")
          .log();
    } else {
      logger
          .event(LogEvent.PROTOCOL_AUTH)
          .withParam("connection_id", connectionId)
          .withParam("result", "failure")
          .withOptionalParam("failure_reason", "invalid_password")
          .atLevel(java.util.logging.Level.WARNING)
          .log();
      closeConnection(connectionId, "Authentication failed");
    }
  }

  private void handleCommandPacket(String connectionId, RconPacket packet) throws Exception {
    String commandLine = packet.getBody();
    String commandName = extractCommandName(commandLine);

    long startTime = System.currentTimeMillis();
    String response;
    String errorCode = null;

    try {
      response = commandDispatcher.execute(commandLine);
    } catch (Exception e) {
      errorCode = e.getClass().getSimpleName();
      logger
          .event(LogEvent.COMMAND_ERROR)
          .withParam("connection_id", connectionId)
          .withParam("command_name", commandName)
          .withParam("error_code", errorCode)
          .withCause(e)
          .log();
      throw e;
    }

    long executionTime = System.currentTimeMillis() - startTime;

    RconPacket responsePacket = protocol.createCommandResponse(packet.getId(), response);
    byte[] responseData = protocol.formatPacket(responsePacket);
    transport.send(connectionId, responseData);

    // Increment command count for session
    AtomicInteger commandCount = sessionCommandCounts.get(connectionId);
    if (commandCount != null) {
      commandCount.incrementAndGet();
    }

    // Log command execution
    RconLogger commandLogger = RconLogger.createPluginLogger(RconConstants.LOGGER_COMMAND);
    commandLogger
        .event(LogEvent.COMMAND_EXECUTE)
        .withParam("connection_id", connectionId)
        .withParam("command_name", commandName)
        .withParam("result", "success")
        .withOptionalParam("execution_time_ms", executionTime)
        .log();

    // Debug logging with sanitized args
    String sanitizedArgs = sanitizeCommandArgs(commandLine);
    commandLogger
        .event(LogEvent.COMMAND_EXECUTE_DEBUG)
        .withParam("connection_id", connectionId)
        .withParam("command_name", commandName)
        .withParam("sanitized_args", sanitizedArgs)
        .log();
  }

  private String extractCommandName(String commandLine) {
    if (commandLine == null || commandLine.trim().isEmpty()) {
      return "";
    }
    String[] parts = commandLine.trim().split("\\s+", 2);
    return parts[0].toLowerCase();
  }

  private String sanitizeCommandArgs(String commandLine) {
    if (commandLine == null || commandLine.trim().isEmpty()) {
      return "";
    }
    String[] parts = commandLine.trim().split("\\s+", 2);
    if (parts.length > 1) {
      String args = parts[1];
      // Sanitize any potential secrets
      if (args.toLowerCase().contains("password")
          || args.toLowerCase().contains("secret")
          || args.toLowerCase().contains("token")) {
        return "[REDACTED]";
      }
      return args;
    }
    return "";
  }

  private void handleProtocolError(String connectionId, String error) {
    logger
        .event(LogEvent.PROTOCOL_PACKET_INVALID)
        .withParam("violation_type", "parse_error")
        .withParam("connection_id", connectionId)
        .withParam("message", error)
        .log();
    closeConnection(connectionId, "Protocol error");
  }

  private void closeConnection(String connectionId, String reason) {
    transport.closeConnection(connectionId, reason);
  }

  public ApplicationStats getStats() {
    return new ApplicationStats(transport.getConnectionCount(), connectionStates.size());
  }

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
