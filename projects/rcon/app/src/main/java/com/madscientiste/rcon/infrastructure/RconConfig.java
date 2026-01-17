package com.madscientiste.rcon.infrastructure;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable configuration value object for RCON server. Use Builder pattern to create instances.
 */
public class RconConfig {

  private final String host;
  private final int port;
  private final int maxConnections;
  private final int maxFrameSize;
  private final int readTimeoutMs;
  private final int connectionTimeoutMs;
  @Nullable private final String passwordHash;

  // Private constructor - use Builder
  private RconConfig(
      String host,
      int port,
      int maxConnections,
      int maxFrameSize,
      int readTimeoutMs,
      int connectionTimeoutMs,
      @Nullable String passwordHash) {
    // Validation
    if (host == null || host.isEmpty()) {
      throw new IllegalArgumentException("host must be non-null and non-empty");
    }
    if (host.length() > RconConstants.MAX_HOST_LENGTH) {
      throw new IllegalArgumentException("host length must be <= " + RconConstants.MAX_HOST_LENGTH);
    }
    if (port < RconConstants.MIN_PORT || port > RconConstants.MAX_PORT) {
      throw new IllegalArgumentException(
          "port must be between " + RconConstants.MIN_PORT + " and " + RconConstants.MAX_PORT);
    }
    if (maxConnections < RconConstants.MIN_CONNECTIONS
        || maxConnections > RconConstants.MAX_CONNECTIONS) {
      throw new IllegalArgumentException(
          "maxConnections must be between "
              + RconConstants.MIN_CONNECTIONS
              + " and "
              + RconConstants.MAX_CONNECTIONS);
    }
    if (maxFrameSize < RconConstants.MIN_FRAME_SIZE
        || maxFrameSize > RconConstants.MAX_FRAME_SIZE) {
      throw new IllegalArgumentException(
          "maxFrameSize must be between "
              + RconConstants.MIN_FRAME_SIZE
              + " and "
              + RconConstants.MAX_FRAME_SIZE);
    }
    if (readTimeoutMs < RconConstants.MIN_TIMEOUT_MS
        || readTimeoutMs > RconConstants.MAX_READ_TIMEOUT_MS) {
      throw new IllegalArgumentException(
          "readTimeoutMs must be between "
              + RconConstants.MIN_TIMEOUT_MS
              + " and "
              + RconConstants.MAX_READ_TIMEOUT_MS);
    }
    if (connectionTimeoutMs < RconConstants.MIN_TIMEOUT_MS
        || connectionTimeoutMs > RconConstants.MAX_CONNECTION_TIMEOUT_MS) {
      throw new IllegalArgumentException(
          "connectionTimeoutMs must be between "
              + RconConstants.MIN_TIMEOUT_MS
              + " and "
              + RconConstants.MAX_CONNECTION_TIMEOUT_MS);
    }
    if (passwordHash != null) {
      if (passwordHash.isEmpty()) {
        throw new IllegalArgumentException("passwordHash cannot be empty if non-null");
      }
      if (!passwordHash.contains(":")) {
        throw new IllegalArgumentException("passwordHash must be in format salt:hash");
      }
    }

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

  /**
   * Create a new RconConfig with updated password hash (copy-with pattern).
   *
   * @param newHash New password hash, or null to remove password requirement
   * @return New immutable RconConfig instance
   */
  @Nonnull
  public RconConfig withPasswordHash(@Nullable String newHash) {
    return new RconConfig(
        host, port, maxConnections, maxFrameSize, readTimeoutMs, connectionTimeoutMs, newHash);
  }

  /**
   * Create a new Builder instance with default values.
   *
   * @return Builder instance
   */
  @Nonnull
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for creating RconConfig instances. */
  public static class Builder {
    private String host = RconConstants.DEFAULT_HOST;
    private int port = RconConstants.DEFAULT_PORT;
    private int maxConnections = RconConstants.DEFAULT_MAX_CONNECTIONS;
    private int maxFrameSize = RconConstants.DEFAULT_MAX_FRAME_SIZE;
    private int readTimeoutMs = RconConstants.DEFAULT_READ_TIMEOUT_MS;
    private int connectionTimeoutMs = RconConstants.DEFAULT_CONNECTION_TIMEOUT_MS;
    @Nullable private String passwordHash = null;

    private Builder() {}

    @Nonnull
    public Builder host(@Nonnull String host) {
      this.host = host;
      return this;
    }

    @Nonnull
    public Builder port(int port) {
      this.port = port;
      return this;
    }

    @Nonnull
    public Builder maxConnections(int maxConnections) {
      this.maxConnections = maxConnections;
      return this;
    }

    @Nonnull
    public Builder maxFrameSize(int maxFrameSize) {
      this.maxFrameSize = maxFrameSize;
      return this;
    }

    @Nonnull
    public Builder readTimeoutMs(int readTimeoutMs) {
      this.readTimeoutMs = readTimeoutMs;
      return this;
    }

    @Nonnull
    public Builder connectionTimeoutMs(int connectionTimeoutMs) {
      this.connectionTimeoutMs = connectionTimeoutMs;
      return this;
    }

    @Nonnull
    public Builder passwordHash(@Nullable String passwordHash) {
      this.passwordHash = passwordHash;
      return this;
    }

    /**
     * Build an immutable RconConfig instance. Validates all fields.
     *
     * @return New RconConfig instance
     * @throws IllegalArgumentException if validation fails
     */
    @Nonnull
    public RconConfig build() {
      return new RconConfig(
          host,
          port,
          maxConnections,
          maxFrameSize,
          readTimeoutMs,
          connectionTimeoutMs,
          passwordHash);
    }
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
