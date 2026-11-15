package com.madscientiste.rcon.application;

import com.madscientiste.rcon.command.CommandDispatcher;
import com.madscientiste.rcon.infrastructure.AuthFailureTracker;
import com.madscientiste.rcon.infrastructure.AuthenticationService;
import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.infrastructure.RconConstants;
import com.madscientiste.rcon.logging.LogEvent;
import com.madscientiste.rcon.logging.LogThrottle;
import com.madscientiste.rcon.logging.RconLogger;
import com.madscientiste.rcon.protocol.ConnectionState;
import com.madscientiste.rcon.protocol.RconPacket;
import com.madscientiste.rcon.protocol.RconProtocol;
import com.madscientiste.rcon.transport.RconTransport;
import com.madscientiste.rcon.transport.TransportCallbacks;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RconApplication implements TransportCallbacks {

  /** Everything the application keeps about one connection. Created on connect, freed on close. */
  private static final class Session {
    final ConnectionState state = new ConnectionState();
    final AtomicInteger commandCount = new AtomicInteger(0);
    final String remoteIp;
    final String addressKey;

    Session(InetAddress remoteAddress) {
      this.remoteIp = remoteAddress.getHostAddress();
      this.addressKey = AuthFailureTracker.keyFor(remoteAddress);
    }
  }

  private final RconTransport transport;
  private final RconProtocol protocol;
  private final CommandDispatcher commandDispatcher;
  private final AuthFailureTracker authFailures;
  private final LogThrottle preAuthLogs;

  private final RconLogger logger = RconLogger.createPluginLogger(RconConstants.LOGGER_APPLICATION);
  private final RconLogger commandLogger =
      RconLogger.createPluginLogger(RconConstants.LOGGER_COMMAND);

  private final RconConfig config;

  private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

  public RconApplication(
      RconTransport transport,
      RconProtocol protocol,
      CommandDispatcher commandDispatcher,
      RconConfig config,
      AuthFailureTracker authFailures,
      LogThrottle preAuthLogs) {
    this.transport = transport;
    this.protocol = protocol;
    this.commandDispatcher = commandDispatcher;
    this.config = config;
    this.authFailures = authFailures;
    this.preAuthLogs = preAuthLogs;
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
  public void onConnectionOpened(String connectionId, InetAddress remoteAddress) {
    sessions.put(connectionId, new Session(remoteAddress));
  }

  @Override
  public void onBytesReceived(String connectionId, byte[] data) {
    // State is created on connect and removed on close, never here: a packet that arrives for a
    // connection that is already closed is dropped and leaves nothing behind.
    Session session = sessions.get(connectionId);
    if (session == null) {
      return;
    }

    try {
      RconProtocol.ProtocolResult result = protocol.parseBytes(connectionId, data);

      if (result instanceof RconProtocol.ProtocolSuccess success) {
        handlePackets(connectionId, session, success.getPackets());
      } else if (result instanceof RconProtocol.ProtocolError error) {
        handleProtocolError(connectionId, session, error.getErrorMessage());
      }

    } catch (Exception e) {
      logger
          .event(LogEvent.APPLICATION_ERROR)
          .withParam("error_code", "byte_processing_failure")
          .withParam("message", "Error processing bytes")
          .withOptionalParam("connection_id", connectionId)
          .withOptionalParam("remote_ip", session.remoteIp)
          .withCause(e)
          .throttledBy(preAuthThrottle(session), session.addressKey)
          .log();
      closeConnection(connectionId, "Processing error");
    }
  }

  @Override
  public void onConnectionClosed(String connectionId, String reason) {
    Session session = sessions.remove(connectionId);
    if (session == null) {
      return;
    }

    boolean hadSession = session.state.isReadyForCommand();
    session.state.close();

    // A connection that never logged in had no session; its disconnect line says enough
    if (hadSession) {
      int commandsExecuted = session.commandCount.get();
      logger
          .event(LogEvent.APPLICATION_SESSION_END)
          .withParam("connection_id", connectionId)
          .withParam("reason", reason)
          .withOptionalParam("commands_executed", commandsExecuted > 0 ? commandsExecuted : null)
          .log();
    }
  }

  private void handlePackets(
      String connectionId, Session session, java.util.List<RconPacket> packets) throws Exception {
    ConnectionState state = session.state;

    for (RconPacket packet : packets) {
      // Closed while handling an earlier packet (failed login, violation): handle nothing more
      if (state.isClosed()) {
        return;
      }

      ConnectionState.TransitionResult transition = state.processPacket(packet);

      if (!transition.isSuccess()) {
        logger
            .event(LogEvent.PROTOCOL_PACKET_INVALID)
            .withParam("violation_type", "state_machine_violation")
            .withParam("connection_id", connectionId)
            .withParam("remote_ip", session.remoteIp)
            .withParam("message", transition.getMessage())
            .throttledBy(preAuthThrottle(session), session.addressKey)
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
              .withParam("remote_ip", session.remoteIp)
              .log();
          closeConnection(connectionId, "Re-authentication not allowed");
          return;
        }
        handleAuthPacket(connectionId, packet, session);
      } else if (packet.getType() == RconPacket.SERVERDATA_EXECCOMMAND
          && state.isReadyForCommand()) {
        handleCommandPacket(connectionId, packet, session);
      } else if (packet.getType() == RconPacket.SERVERDATA_RESPONSE_VALUE) {
        // Fine detail logging - not a structured event
        logger.atFine().log("Response received for connection: %s", connectionId);
      }
    }
  }

  private void handleAuthPacket(String connectionId, RconPacket packet, Session session)
      throws Exception {
    int responseId = packet.getId();
    String providedPassword = packet.getBody();
    boolean authSuccess = false;
    String failureReason = "invalid_password";

    if (!config.requiresPassword()) {
      authSuccess = true;
      logger
          .event(LogEvent.PROTOCOL_AUTH)
          .withParam("connection_id", connectionId)
          .withParam("remote_ip", session.remoteIp)
          .withParam("result", "success")
          .withOptionalParam("failure_reason", "no_password_configured")
          .atLevel(java.util.logging.Level.WARNING)
          .log();
    } else if (!authFailures.tryBeginAttempt(session.addressKey)) {
      // Banned, or the address has used up its guesses: the password is not even looked at
      failureReason = "too_many_failures";
    } else {
      boolean banned = false;
      try {
        authSuccess =
            AuthenticationService.verifyPassword(providedPassword, config.getPasswordHash());
      } finally {
        banned = authFailures.endAttempt(session.addressKey, authSuccess);
      }

      if (banned) {
        logger
            .event(LogEvent.TRANSPORT_RATE_LIMIT)
            .withParam("remote_ip", session.remoteIp)
            .withParam("action", "address_banned")
            .withParam("reason", "too_many_wrong_passwords")
            .withParam("count", config.getAuthMaxFailures())
            .withParam("time_window_ms", config.getAuthBanMs())
            .log();
      }
    }

    if (authSuccess) {
      // Logged in before the reply goes out: the login deadline stops counting here
      session.state.markAuthenticated();
      transport.markAuthenticated(connectionId);
    }

    RconPacket response = protocol.createAuthResponse(responseId, authSuccess);
    byte[] responseData = protocol.formatPacket(response);
    transport.send(connectionId, responseData);

    if (authSuccess) {
      logger
          .event(LogEvent.APPLICATION_SESSION_START)
          .withParam("connection_id", connectionId)
          .withParam("remote_ip", session.remoteIp)
          .withParam("authenticated", true)
          .log();

      logger
          .event(LogEvent.PROTOCOL_AUTH)
          .withParam("connection_id", connectionId)
          .withParam("remote_ip", session.remoteIp)
          .withParam("result", "success")
          .log();
    } else {
      // A counted wrong password is always logged (the ban caps how many there can be). Refused
      // attempts from a banned address can come in any number, so they share the log budget.
      boolean counted = "invalid_password".equals(failureReason);
      logger
          .event(LogEvent.PROTOCOL_AUTH)
          .withParam("connection_id", connectionId)
          .withParam("remote_ip", session.remoteIp)
          .withParam("result", "failure")
          .withOptionalParam("failure_reason", failureReason)
          .atLevel(java.util.logging.Level.WARNING)
          .throttledBy(counted ? null : preAuthLogs, session.addressKey)
          .log();
      closeConnection(connectionId, "Authentication failed");
    }
  }

  private void handleCommandPacket(String connectionId, RconPacket packet, Session session)
      throws Exception {
    String commandLine = packet.getBody();
    String commandName = extractCommandName(commandLine);

    long startTime = System.currentTimeMillis();
    String response;
    String errorCode = null;

    try {
      response = commandDispatcher.execute(commandLine);
    } catch (Exception e) {
      errorCode = e.getClass().getSimpleName();
      commandLogger
          .event(LogEvent.COMMAND_ERROR)
          .withParam("connection_id", connectionId)
          .withParam("command_name", commandName)
          .withParam("error_code", errorCode)
          .withCause(e)
          .log();
      throw e;
    }

    long executionTime = System.currentTimeMillis() - startTime;

    // Audit line first: the command has run, so it is recorded even if the reply cannot be sent
    session.commandCount.incrementAndGet();
    commandLogger
        .event(LogEvent.COMMAND_EXECUTE)
        .withParam("connection_id", connectionId)
        .withParam("remote_ip", session.remoteIp)
        .withParam("command_name", commandName)
        .withParam("command", redactSecrets(commandLine))
        .withParam("result", "success")
        .withOptionalParam("execution_time_ms", executionTime)
        .log();

    // A long reply goes out as several packets with the same id
    for (RconPacket responsePacket : protocol.createCommandResponses(packet.getId(), response)) {
      transport.send(connectionId, protocol.formatPacket(responsePacket));
    }
  }

  private String extractCommandName(String commandLine) {
    if (commandLine == null || commandLine.trim().isEmpty()) {
      return "";
    }
    String[] parts = commandLine.trim().split("\\s+", 2);
    return parts[0].toLowerCase();
  }

  /**
   * The command line as it goes in the log: complete, except that arguments which look like they
   * carry a secret are hidden. Control characters are neutralised by the log builder.
   */
  private String redactSecrets(String commandLine) {
    if (commandLine == null || commandLine.trim().isEmpty()) {
      return "";
    }
    String[] parts = commandLine.trim().split("\\s+", 2);
    if (parts.length > 1) {
      String args = parts[1].toLowerCase();
      if (args.contains("password") || args.contains("secret") || args.contains("token")) {
        return parts[0] + " [REDACTED]";
      }
    }
    return commandLine.trim();
  }

  private void handleProtocolError(String connectionId, Session session, String error) {
    logger
        .event(LogEvent.PROTOCOL_PACKET_INVALID)
        .withParam("violation_type", "parse_error")
        .withParam("connection_id", connectionId)
        .withParam("remote_ip", session.remoteIp)
        .withParam("message", error)
        .throttledBy(preAuthThrottle(session), session.addressKey)
        .log();
    closeConnection(connectionId, "Protocol error");
  }

  /** Peers that have not logged in share a per-address log budget; logged-in ones do not. */
  private LogThrottle preAuthThrottle(Session session) {
    return session.state.isReadyForCommand() ? null : preAuthLogs;
  }

  private void closeConnection(String connectionId, String reason) {
    transport.closeConnection(connectionId, reason);
  }

  public ApplicationStats getStats() {
    return new ApplicationStats(transport.getConnectionCount(), sessions.size());
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
