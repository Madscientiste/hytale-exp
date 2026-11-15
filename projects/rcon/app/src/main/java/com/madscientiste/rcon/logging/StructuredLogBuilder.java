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
  private LogThrottle throttle;
  private String throttleKey;

  /** Longest parameter value written to the log; the rest is cut and counted. */
  static final int MAX_VALUE_LENGTH = 512;

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

  /**
   * Count this line against a per-key budget (see {@link LogThrottle}). Use it for every line a
   * peer can cause before it has logged in. A null throttle or key means no limit.
   *
   * @param throttle Budget to draw from, or null
   * @param key Usually the remote address, or null
   * @return this builder for chaining
   */
  @Nonnull
  public StructuredLogBuilder throttledBy(LogThrottle throttle, String key) {
    this.throttle = throttle;
    this.throttleKey = key;
    return this;
  }

  /** Execute the log statement. Validates required parameters and formats the message. */
  public void log() {
    validateRequiredParams();

    long suppressed = 0;
    if (throttle != null && throttleKey != null) {
      suppressed = throttle.tryAcquire(throttleKey);
      if (suppressed < 0) {
        return;
      }
    }

    String message = formatMessage();
    if (suppressed > 0) {
      message += " (" + suppressed + " earlier lines from this address not logged)";
    }
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
      return neutralize((String) value);
    } else if (value instanceof Number) {
      return value.toString();
    } else if (value instanceof Boolean) {
      return value.toString();
    } else {
      return neutralize(String.valueOf(value));
    }
  }

  /**
   * Make a value safe to write on one log line. Much of what is logged comes from the network
   * (command text, error messages quoting packet content), so control characters are written as
   * visible escapes: a peer cannot start a forged log line or send terminal escape codes. Long
   * values are cut.
   */
  @Nonnull
  static String neutralize(@Nonnull String value) {
    int length = Math.min(value.length(), MAX_VALUE_LENGTH);
    StringBuilder sb = new StringBuilder(length + 16);

    for (int i = 0; i < length; i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }

    if (value.length() > MAX_VALUE_LENGTH) {
      sb.append("...(").append(value.length() - MAX_VALUE_LENGTH).append(" more chars)");
    }
    return sb.toString();
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
