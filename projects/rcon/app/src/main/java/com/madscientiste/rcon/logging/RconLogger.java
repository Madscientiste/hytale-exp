package com.madscientiste.rcon.logging;

import com.hypixel.hytale.logger.HytaleLogger;
import com.madscientiste.rcon.infrastructure.RconConstants;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Structured logger for RCON system. Provides layer-specific loggers and structured event-based
 * logging.
 */
public class RconLogger {

  private final HytaleLogger delegate;

  private RconLogger(@Nonnull HytaleLogger delegate) {
    this.delegate = delegate;
  }

  /**
   * Create a logger for a specific layer (Transport, Protocol, Application, Command).
   *
   * @param layer Layer name (from RconConstants.LOGGER_*)
   * @return Logger instance for the specified layer
   */
  @Nonnull
  public static RconLogger createPluginLogger(String layer) {
    String name = String.format("%s|P", RconConstants.LOGGER_ROOT);
    if (layer != null) {
      name += "." + layer;
    }
    return new RconLogger(HytaleLogger.get(name));
  }

  /**
   * Create a structured log event builder.
   *
   * @param event The log event to create
   * @return Builder for constructing the log statement
   */
  @Nonnull
  public StructuredLogBuilder event(@Nonnull LogEvent event) {
    return new StructuredLogBuilder(delegate, event);
  }

  /**
   * Convenience method for INFO level logging.
   *
   * @return HytaleLogger API for INFO level logging
   */
  @Nonnull
  public HytaleLogger.Api atInfo() {
    return delegate.at(Level.INFO);
  }

  /**
   * Convenience method for WARNING level logging.
   *
   * @return HytaleLogger API for WARNING level logging
   */
  @Nonnull
  public HytaleLogger.Api atWarning() {
    return delegate.at(Level.WARNING);
  }

  /**
   * Convenience method for SEVERE level logging.
   *
   * @return HytaleLogger API for SEVERE level logging
   */
  @Nonnull
  public HytaleLogger.Api atSevere() {
    return delegate.at(Level.SEVERE);
  }

  /**
   * Convenience method for FINE level logging.
   *
   * @return HytaleLogger API for FINE level logging
   */
  @Nonnull
  public HytaleLogger.Api atFine() {
    return delegate.at(Level.FINE);
  }

  /**
   * Direct access to underlying HytaleLogger for cases where structured logging isn't needed (e.g.,
   * plugin lifecycle logs).
   *
   * @param level Log level
   * @return HytaleLogger API for direct logging
   */
  @Nonnull
  public HytaleLogger.Api at(@Nonnull Level level) {
    return delegate.at(level);
  }
}
