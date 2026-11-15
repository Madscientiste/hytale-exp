package com.madscientiste.rcon.infrastructure;

/** Centralized constants for RCON plugin configuration and validation. */
public final class RconConstants {

  private RconConstants() {
    // Utility class - prevent instantiation
  }

  // ============================================================================
  // Default Configuration Values
  // ============================================================================

  /** Default RCON server host. */
  public static final String DEFAULT_HOST = "127.0.0.1";

  /** Default RCON server port. */
  public static final int DEFAULT_PORT = 25575;

  /** Default maximum number of concurrent connections. */
  public static final int DEFAULT_MAX_CONNECTIONS = 10;

  /** Default maximum frame size in bytes. */
  public static final int DEFAULT_MAX_FRAME_SIZE = 4096;

  /** Default read timeout in milliseconds. */
  public static final int DEFAULT_READ_TIMEOUT_MS = 30000;

  /** Default connection timeout in milliseconds. */
  public static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5000;

  /** Default time a new connection has to log in before it is closed, in milliseconds. */
  public static final int DEFAULT_AUTH_TIMEOUT_MS = 5000;

  /** Default maximum number of concurrent connections from one address. */
  public static final int DEFAULT_MAX_CONNECTIONS_PER_IP = 4;

  /** Default number of wrong passwords from one address that triggers a ban. */
  public static final int DEFAULT_AUTH_MAX_FAILURES = 5;

  /** Default window in which wrong passwords are counted, in milliseconds (10 minutes). */
  public static final int DEFAULT_AUTH_FAILURE_WINDOW_MS = 600000;

  /** Default ban length after too many wrong passwords, in milliseconds (5 minutes). */
  public static final int DEFAULT_AUTH_BAN_MS = 300000;

  // ============================================================================
  // Validation Limits
  // ============================================================================

  /** Maximum host string length. */
  public static final int MAX_HOST_LENGTH = 255;

  /** Minimum valid port number. */
  public static final int MIN_PORT = 1;

  /** Maximum valid port number. */
  public static final int MAX_PORT = 65535;

  /** Minimum number of connections. */
  public static final int MIN_CONNECTIONS = 1;

  /** Maximum number of connections. */
  public static final int MAX_CONNECTIONS = 1000;

  /**
   * Minimum frame size in bytes. The smallest RCON packet is 14 bytes; anything below 64 cannot
   * carry a login with a normal password.
   */
  public static final int MIN_FRAME_SIZE = 64;

  /** Maximum frame size in bytes (1MB). */
  public static final int MAX_FRAME_SIZE = 1048576;

  /** Minimum timeout value in milliseconds. */
  public static final int MIN_TIMEOUT_MS = 1;

  /** Maximum read timeout in milliseconds (5 minutes). */
  public static final int MAX_READ_TIMEOUT_MS = 300000;

  /** Maximum connection timeout in milliseconds (1 minute). */
  public static final int MAX_CONNECTION_TIMEOUT_MS = 60000;

  /** Maximum login deadline in milliseconds (1 minute). */
  public static final int MAX_AUTH_TIMEOUT_MS = 60000;

  /** Maximum number of wrong passwords before a ban. */
  public static final int MAX_AUTH_FAILURES = 1000;

  /** Maximum failure window and ban length in milliseconds (24 hours). */
  public static final int MAX_AUTH_PERIOD_MS = 86400000;

  // ============================================================================
  // Protocol Limits
  // ============================================================================

  /**
   * Largest packet (size prefix included) the server ever sends. Standard RCON clients do not
   * accept more, so longer replies are split over several packets.
   */
  public static final int MAX_OUTBOUND_PACKET_SIZE = 4096;

  // ============================================================================
  // File Paths
  // ============================================================================

  /** Config directory name. */
  public static final String CONFIG_DIR = "configs";

  /** Config filename (group ID only, without mod name). */
  public static final String CONFIG_FILENAME = "com.madscientiste.rcon.json";

  // ============================================================================
  // Logger Names (for configuration)
  // ============================================================================

  /** [RCON|P] logger name for RCON plugin */
  public static final String LOGGER_ROOT = "Rcon";

  /** [RCON|P] [TRANSPORT] Logger name for transport layer */
  public static final String LOGGER_TRANSPORT = "Transport";

  /** [RCON|P] [PROTOCOL] Logger name for protocol layer */
  public static final String LOGGER_PROTOCOL = "Protocol";

  /** [RCON|P] [APPLICATION] Logger name for application layer */
  public static final String LOGGER_APPLICATION = "Application";

  /** [RCON|P] [COMMAND] Logger name for command layer */
  public static final String LOGGER_COMMAND = "Command";

  // ============================================================================
  // Test Constants
  // ============================================================================

  /** Default test host. */
  public static final String TEST_HOST = "127.0.0.1";

  /** Default test port (for integration tests). */
  public static final int TEST_PORT = 25576;
}
