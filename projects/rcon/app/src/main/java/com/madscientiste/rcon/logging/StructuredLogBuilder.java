package com.madscientiste.rcon.logging;

import com.hypixel.hytale.logger.HytaleLogger;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Builder for structured log events. Validates required parameters and formats messages with
 * structured data.
 */
public class StructuredLogBuilder {
  private final HytaleLogger logger;
  private final LogEvent event;
  private final Map<String, Object> params = new HashMap<>();
  private Throwable cause;
  private Level overrideLevel;

  StructuredLogBuilder(@Nonnull HytaleLogger logger, @Nonnull LogEvent event) {
    this.logger = logger;
    this.event = event;
  }

  /**
   * Add a parameter to the log event.
   *
   * @param key Parameter name
   * @param value Parameter value
   * @return this builder for chaining
   */
  @Nonnull
  public StructuredLogBuilder withParam(@Nonnull String key, @Nonnull Object value) {
    if (!event.isValidParam(key)) {
      throw new IllegalArgumentException(
          "Parameter '" + key + "' is not valid for event " + event.getEventName());
    }
    params.put(key, sanitizeValue(key, value));
    return this;
  }

  /**
   * Add an optional parameter (for convenience, same as withParam but documents intent).
   *
   * @param key Parameter name
   * @param value Parameter value
   * @return this builder for chaining
   */
  @Nonnull
  public StructuredLogBuilder withOptionalParam(@Nonnull String key, Object value) {
    if (value != null) {
      withParam(key, value);
    }
    return this;
  }

  /**
   * Attach an exception/throwable to the log event.
   *
   * @param throwable Exception to attach
   * @return this builder for chaining
   */
  @Nonnull
  public StructuredLogBuilder withCause(@Nonnull Throwable throwable) {
    this.cause = throwable;
    return this;
  }

  /**
   * Override the default log level for this event.
   *
   * @param level Log level to use
   * @return this builder for chaining
   */
  @Nonnull
  public StructuredLogBuilder atLevel(@Nonnull Level level) {
    this.overrideLevel = level;
    return this;
  }

  /** Execute the log statement. Validates required parameters and formats the message. */
  public void log() {
    validateRequiredParams();

    String message = formatMessage();
    Level level = overrideLevel != null ? overrideLevel : event.getDefaultLevel();

    HytaleLogger.Api api = logger.at(level);
    if (cause != null) {
      api = api.withCause(cause);
    }

    api.log(message);
  }

  private void validateRequiredParams() {
    Set<String> required = event.getRequiredParams();
    Set<String> provided = params.keySet();

    for (String requiredParam : required) {
      if (!provided.contains(requiredParam)) {
        throw new IllegalStateException(
            "Required parameter '" + requiredParam + "' missing for event " + event.getEventName());
      }
    }
  }

  @Nonnull
  private String formatMessage() {
    StringBuilder sb = new StringBuilder();
    sb.append(event.getEventName());

    if (!params.isEmpty()) {
      sb.append(": ");
      boolean first = true;
      for (Map.Entry<String, Object> entry : params.entrySet()) {
        if (!first) {
          sb.append(", ");
        }
        sb.append(entry.getKey()).append("=").append(formatValue(entry.getValue()));
        first = false;
      }
    }

    return sb.toString();
  }

  @Nonnull
  private String formatValue(@Nonnull Object value) {
    if (value instanceof String) {
      return (String) value;
    } else if (value instanceof Number) {
      return value.toString();
    } else if (value instanceof Boolean) {
      return value.toString();
    } else {
      return String.valueOf(value);
    }
  }

  /**
   * Sanitize parameter values to prevent logging sensitive data. Enforces cross-layer rules: -
   * Transport layer cannot log command names - Protocol layer cannot log raw passwords or payloads
   * - Command layer cannot log auth secrets
   *
   * @param key Parameter key
   * @param value Parameter value
   * @return Sanitized value
   */
  @Nonnull
  private Object sanitizeValue(@Nonnull String key, @Nonnull Object value) {
    // Determine layer from event enum name (TRANSPORT_*, PROTOCOL_*, etc.)
    String eventEnumName = event.name();

    // Protocol layer: never log passwords or raw payloads
    if (eventEnumName.startsWith("PROTOCOL_")) {
      if (key.contains("password") || key.contains("payload") || key.contains("body")) {
        return "[REDACTED]";
      }
    }

    // Transport layer: never log command names
    if (eventEnumName.startsWith("TRANSPORT_")) {
      if (key.equals("command_name")) {
        return "[REDACTED]";
      }
    }

    // Command layer: never log auth secrets
    if (eventEnumName.startsWith("COMMAND_")) {
      if (key.contains("password") || key.contains("secret") || key.contains("auth")) {
        return "[REDACTED]";
      }
    }

    // Sanitize args to prevent logging sensitive data
    if (key.equals("sanitized_args") && value instanceof String) {
      String args = (String) value;
      // Remove any potential secrets from args
      if (args.toLowerCase().contains("password")
          || args.toLowerCase().contains("secret")
          || args.toLowerCase().contains("token")) {
        return "[REDACTED]";
      }
    }

    return value;
  }
}
