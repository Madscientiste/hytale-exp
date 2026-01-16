package com.madscientiste.rcon.infrastructure;

import javax.annotation.Nullable;

/**
 * Configuration class for RCON server. This is a dummy config for MVP - will be replaced with
 * external config later.
 */
public class RconConfig {
  private final String host;
  private final int port;
  private final int maxConnections;
  private final int maxFrameSize;
  private final int readTimeoutMs;
  private final int connectionTimeoutMs;
  private final String passwordHash;

  // Default constructor with MVP defaults (no password - insecure, for
  // development only)
  public RconConfig() {
    this("127.0.0.1", 25575, 10, 4096, 30000, 5000, null);
  }

  // Constructor for testing/future external config
  public RconConfig(
      String host,
      int port,
      int maxConnections,
      int maxFrameSize,
      int readTimeoutMs,
      int connectionTimeoutMs,
      @Nullable String passwordHash) {
    this.host = host;
    this.port = port;
    this.maxConnections = maxConnections;
    this.maxFrameSize = maxFrameSize;
    this.readTimeoutMs = readTimeoutMs;
    this.connectionTimeoutMs = connectionTimeoutMs;
    this.passwordHash = passwordHash;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public int getMaxConnections() {
    return maxConnections;
  }

  public int getMaxFrameSize() {
    return maxFrameSize;
  }

  public int getReadTimeoutMs() {
    return readTimeoutMs;
  }

  public int getConnectionTimeoutMs() {
    return connectionTimeoutMs;
  }

  @Nullable
  public String getPasswordHash() {
    return passwordHash;
  }

  public boolean requiresPassword() {
    return passwordHash != null && !passwordHash.isEmpty();
  }

  @Override
  public String toString() {
    return "RconConfig{"
        + "host='"
        + host
        + '\''
        + ", port="
        + port
        + ", maxConnections="
        + maxConnections
        + ", maxFrameSize="
        + maxFrameSize
        + ", readTimeoutMs="
        + readTimeoutMs
        + ", connectionTimeoutMs="
        + connectionTimeoutMs
        + ", hasPassword="
        + requiresPassword()
        + '}';
  }
}
