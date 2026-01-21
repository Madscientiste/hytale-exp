package com.madscientiste.rcon.logging;

import java.util.Set;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Structured logging events for RCON system. Each event defines its name, log level, and required
 * parameters.
 */
public enum LogEvent {
  // ============================================================================
  // TRANSPORT Layer Events
  // ============================================================================

  /** Connection opened at transport level */
  TRANSPORT_CONNECT("connect", Level.INFO, Set.of("connection_id", "remote_ip", "remote_port")),

  /** Connection closed at transport level */
  TRANSPORT_DISCONNECT(
      "disconnect", Level.INFO, Set.of("connection_id", "reason"), Set.of("session_duration_ms")),

  /** Socket-level errors and IO failures */
  TRANSPORT_ERROR("error", Level.SEVERE, Set.of("error_type", "message"), Set.of("connection_id")),

  /** Rate limiting triggered */
  TRANSPORT_RATE_LIMIT(
      "rate_limit", Level.WARNING, Set.of("remote_ip", "action", "count", "time_window_ms")),

  // ============================================================================
  // PROTOCOL Layer Events
  // ============================================================================

  /** Protocol handshake/version negotiation */
  PROTOCOL_HANDSHAKE(
      "handshake", Level.INFO, Set.of("connection_id", "protocol_version", "result")),

  /** Invalid or malformed packet detected */
  PROTOCOL_PACKET_INVALID(
      "packet.invalid",
      Level.WARNING,
      Set.of("violation_type"),
      Set.of("connection_id", "remote_ip")),

  /** Authentication at protocol level */
  PROTOCOL_AUTH(
      "auth",
      Level.INFO,
      Set.of("connection_id", "result"),
      Set.of("failure_reason", "attempt_number")),

  /** Protocol parsing or state machine errors */
  PROTOCOL_ERROR("error", Level.SEVERE, Set.of("error_code", "message"), Set.of("connection_id")),

  // ============================================================================
  // APPLICATION Layer Events
  // ============================================================================

  /** Session started (transition from connection to usable session) */
  APPLICATION_SESSION_START("session.start", Level.INFO, Set.of("connection_id", "authenticated")),

  /** Session ended with summary */
  APPLICATION_SESSION_END(
      "session.end", Level.INFO, Set.of("connection_id", "reason"), Set.of("commands_executed")),

  /** Permission denied for command */
  APPLICATION_PERMISSION_DENIED(
      "permission.denied",
      Level.WARNING,
      Set.of("connection_id", "command_name", "required_permission")),

  /** Application-level logic errors */
  APPLICATION_ERROR(
      "error", Level.SEVERE, Set.of("error_code", "message"), Set.of("connection_id")),

  // ============================================================================
  // COMMAND Layer Events
  // ============================================================================

  /** Command executed successfully */
  COMMAND_EXECUTE(
      "execute",
      Level.INFO,
      Set.of("connection_id", "command_name", "result"),
      Set.of("execution_time_ms")),

  /** Debug-level command execution details */
  COMMAND_EXECUTE_DEBUG(
      "execute.debug", Level.FINE, Set.of("connection_id", "command_name", "sanitized_args")),

  /** Invalid command received */
  COMMAND_INVALID("invalid", Level.WARNING, Set.of("connection_id", "command_name")),

  /** Command handler failure */
  COMMAND_ERROR("error", Level.SEVERE, Set.of("connection_id", "command_name", "error_code"));

  private final String eventName;
  private final Level defaultLevel;
  private final Set<String> requiredParams;
  private final Set<String> optionalParams;

  LogEvent(
      @Nonnull String eventName, @Nonnull Level defaultLevel, @Nonnull Set<String> requiredParams) {
    this(eventName, defaultLevel, requiredParams, Set.of());
  }

  LogEvent(
      @Nonnull String eventName,
      @Nonnull Level defaultLevel,
      @Nonnull Set<String> requiredParams,
      @Nonnull Set<String> optionalParams) {
    this.eventName = eventName;
    this.defaultLevel = defaultLevel;
    this.requiredParams = requiredParams;
    this.optionalParams = optionalParams;
  }

  @Nonnull
  public String getEventName() {
    return eventName;
  }

  @Nonnull
  public Level getDefaultLevel() {
    return defaultLevel;
  }

  @Nonnull
  public Set<String> getRequiredParams() {
    return requiredParams;
  }

  @Nonnull
  public Set<String> getOptionalParams() {
    return optionalParams;
  }

  /**
   * Check if a parameter is valid for this event (either required or optional).
   *
   * @param paramName Parameter name to check
   * @return true if parameter is valid for this event
   */
  public boolean isValidParam(@Nonnull String paramName) {
    return requiredParams.contains(paramName) || optionalParams.contains(paramName);
  }
}
