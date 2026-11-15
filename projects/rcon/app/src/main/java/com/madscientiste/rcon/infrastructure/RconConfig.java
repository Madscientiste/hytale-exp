package com.madscientiste.rcon.infrastructure;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable configuration value object for RCON server. Use Builder pattern to create instances.
 */
public class RconConfig {

  private final String host;
  private final int port;
  private final int maxConnections;
  private final int maxConnectionsPerIp;
  private final int maxFrameSize;
  private final int readTimeoutMs;
  private final int connectionTimeoutMs;
  private final int authTimeoutMs;
  private final int authMaxFailures;
  private final int authFailureWindowMs;
  private final int authBanMs;
  @Nullable private final String passwordHash;
  private final boolean allowNoPassword;
  private final List<AddressRange> noPasswordAllowFrom;

  // Private constructor - use Builder
  private RconConfig(Builder b) {
    if (b.host == null || b.host.isEmpty()) {
      throw new IllegalArgumentException("host must be non-null and non-empty");
    }
    if (b.host.length() > RconConstants.MAX_HOST_LENGTH) {
      throw new IllegalArgumentException("host length must be <= " + RconConstants.MAX_HOST_LENGTH);
    }
    requireRange("port", b.port, RconConstants.MIN_PORT, RconConstants.MAX_PORT);
    requireRange(
        "maxConnections",
        b.maxConnections,
        RconConstants.MIN_CONNECTIONS,
        RconConstants.MAX_CONNECTIONS);
    requireRange(
        "maxConnectionsPerIp",
        b.maxConnectionsPerIp,
        RconConstants.MIN_CONNECTIONS,
        RconConstants.MAX_CONNECTIONS);
    requireRange(
        "maxFrameSize", b.maxFrameSize, RconConstants.MIN_FRAME_SIZE, RconConstants.MAX_FRAME_SIZE);
    requireRange(
        "readTimeoutMs",
        b.readTimeoutMs,
        RconConstants.MIN_TIMEOUT_MS,
        RconConstants.MAX_READ_TIMEOUT_MS);
    requireRange(
        "connectionTimeoutMs",
        b.connectionTimeoutMs,
        RconConstants.MIN_TIMEOUT_MS,
        RconConstants.MAX_CONNECTION_TIMEOUT_MS);
    requireRange(
        "authTimeoutMs",
        b.authTimeoutMs,
        RconConstants.MIN_TIMEOUT_MS,
        RconConstants.MAX_AUTH_TIMEOUT_MS);
    requireRange("authMaxFailures", b.authMaxFailures, 1, RconConstants.MAX_AUTH_FAILURES);
    requireRange(
        "authFailureWindowMs",
        b.authFailureWindowMs,
        RconConstants.MIN_TIMEOUT_MS,
        RconConstants.MAX_AUTH_PERIOD_MS);
    requireRange(
        "authBanMs", b.authBanMs, RconConstants.MIN_TIMEOUT_MS, RconConstants.MAX_AUTH_PERIOD_MS);
    if (b.passwordHash != null && !AuthenticationService.isValidHash(b.passwordHash)) {
      throw new IllegalArgumentException(
          "passwordHash is not a valid hash (expected "
              + AuthenticationService.HASH_FORMAT_DESCRIPTION
              + ")");
    }

    this.host = b.host;
    this.port = b.port;
    this.maxConnections = b.maxConnections;
    this.maxConnectionsPerIp = b.maxConnectionsPerIp;
    this.maxFrameSize = b.maxFrameSize;
    this.readTimeoutMs = b.readTimeoutMs;
    this.connectionTimeoutMs = b.connectionTimeoutMs;
    this.authTimeoutMs = b.authTimeoutMs;
    this.authMaxFailures = b.authMaxFailures;
    this.authFailureWindowMs = b.authFailureWindowMs;
    if (b.noPasswordAllowFrom == null
        || b.noPasswordAllowFrom.isEmpty()
        || b.noPasswordAllowFrom.size() > RconConstants.MAX_ADDRESS_RANGES) {
      throw new IllegalArgumentException(
          "noPasswordAllowFrom must list 1 to "
              + RconConstants.MAX_ADDRESS_RANGES
              + " addresses or CIDR ranges");
    }
    List<AddressRange> ranges = new ArrayList<>();
    for (String range : b.noPasswordAllowFrom) {
      if (range == null) {
        throw new IllegalArgumentException("noPasswordAllowFrom must not contain null");
      }
      try {
        ranges.add(AddressRange.parse(range));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("noPasswordAllowFrom: " + e.getMessage());
      }
    }

    this.authBanMs = b.authBanMs;
    this.passwordHash = b.passwordHash;
    this.allowNoPassword = b.allowNoPassword;
    this.noPasswordAllowFrom = List.copyOf(ranges);
  }

  private static void requireRange(String name, int value, int min, int max) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          name + " must be between " + min + " and " + max + " (got " + value + ")");
    }
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

  /** Maximum concurrent connections from one address. */
  public int getMaxConnectionsPerIp() {
    return maxConnectionsPerIp;
  }

  /** Largest packet (size prefix included) a client may send, in bytes. */
  public int getMaxFrameSize() {
    return maxFrameSize;
  }

  /** How long an authenticated connection may stay silent before it is closed. */
  public int getReadTimeoutMs() {
    return readTimeoutMs;
  }

  public int getConnectionTimeoutMs() {
    return connectionTimeoutMs;
  }

  /** How long a new connection has to log in before it is closed. */
  public int getAuthTimeoutMs() {
    return authTimeoutMs;
  }

  /** Wrong passwords from one address, within the failure window, that trigger a ban. */
  public int getAuthMaxFailures() {
    return authMaxFailures;
  }

  public int getAuthFailureWindowMs() {
    return authFailureWindowMs;
  }

  public int getAuthBanMs() {
    return authBanMs;
  }

  @Nullable
  public String getPasswordHash() {
    return passwordHash;
  }

  public boolean requiresPassword() {
    return passwordHash != null && !passwordHash.isEmpty();
  }

  /** The owner's explicit choice to run without a password. Only matters when no hash is set. */
  public boolean isAllowNoPassword() {
    return allowNoPassword;
  }

  /**
   * True when logins are accepted without a password check: no hash is set and the owner opted in.
   * Even then only peers in {@link #getNoPasswordAllowFrom()} get in.
   */
  public boolean isNoPasswordMode() {
    return !requiresPassword() && allowNoPassword;
  }

  /** Addresses that may log in without a password while the no-password mode is on. */
  @Nonnull
  public List<AddressRange> getNoPasswordAllowFrom() {
    return noPasswordAllowFrom;
  }

  /** True if the peer may log in without a password while the no-password mode is on. */
  public boolean mayLogInWithoutPassword(@Nonnull InetAddress address) {
    for (AddressRange range : noPasswordAllowFrom) {
      if (range.contains(address)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Create a new RconConfig with updated password hash (copy-with pattern).
   *
   * @param newHash New password hash, or null to remove password requirement
   * @return New immutable RconConfig instance
   */
  @Nonnull
  public RconConfig withPasswordHash(@Nullable String newHash) {
    return toBuilder().passwordHash(newHash).build();
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

  @Nonnull
  private Builder toBuilder() {
    return new Builder()
        .host(host)
        .port(port)
        .maxConnections(maxConnections)
        .maxConnectionsPerIp(maxConnectionsPerIp)
        .maxFrameSize(maxFrameSize)
        .readTimeoutMs(readTimeoutMs)
        .connectionTimeoutMs(connectionTimeoutMs)
        .authTimeoutMs(authTimeoutMs)
        .authMaxFailures(authMaxFailures)
        .authFailureWindowMs(authFailureWindowMs)
        .authBanMs(authBanMs)
        .passwordHash(passwordHash)
        .allowNoPassword(allowNoPassword)
        .noPasswordAllowFrom(noPasswordAllowFrom.stream().map(AddressRange::toString).toList());
  }

  /** Builder for creating RconConfig instances. */
  public static class Builder {
    private String host = RconConstants.DEFAULT_HOST;
    private int port = RconConstants.DEFAULT_PORT;
    private int maxConnections = RconConstants.DEFAULT_MAX_CONNECTIONS;
    private int maxConnectionsPerIp = RconConstants.DEFAULT_MAX_CONNECTIONS_PER_IP;
    private int maxFrameSize = RconConstants.DEFAULT_MAX_FRAME_SIZE;
    private int readTimeoutMs = RconConstants.DEFAULT_READ_TIMEOUT_MS;
    private int connectionTimeoutMs = RconConstants.DEFAULT_CONNECTION_TIMEOUT_MS;
    private int authTimeoutMs = RconConstants.DEFAULT_AUTH_TIMEOUT_MS;
    private int authMaxFailures = RconConstants.DEFAULT_AUTH_MAX_FAILURES;
    private int authFailureWindowMs = RconConstants.DEFAULT_AUTH_FAILURE_WINDOW_MS;
    private int authBanMs = RconConstants.DEFAULT_AUTH_BAN_MS;
    @Nullable private String passwordHash = null;
    private boolean allowNoPassword = false;
    private List<String> noPasswordAllowFrom = RconConstants.DEFAULT_NO_PASSWORD_ALLOW_FROM;

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
    public Builder maxConnectionsPerIp(int maxConnectionsPerIp) {
      this.maxConnectionsPerIp = maxConnectionsPerIp;
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
    public Builder authTimeoutMs(int authTimeoutMs) {
      this.authTimeoutMs = authTimeoutMs;
      return this;
    }

    @Nonnull
    public Builder authMaxFailures(int authMaxFailures) {
      this.authMaxFailures = authMaxFailures;
      return this;
    }

    @Nonnull
    public Builder authFailureWindowMs(int authFailureWindowMs) {
      this.authFailureWindowMs = authFailureWindowMs;
      return this;
    }

    @Nonnull
    public Builder authBanMs(int authBanMs) {
      this.authBanMs = authBanMs;
      return this;
    }

    @Nonnull
    public Builder passwordHash(@Nullable String passwordHash) {
      this.passwordHash = passwordHash;
      return this;
    }

    /** Opt in to running without a password (only has an effect when no hash is set). */
    @Nonnull
    public Builder allowNoPassword(boolean allowNoPassword) {
      this.allowNoPassword = allowNoPassword;
      return this;
    }

    /** Addresses or CIDR ranges that may log in without a password in no-password mode. */
    @Nonnull
    public Builder noPasswordAllowFrom(@Nonnull List<String> noPasswordAllowFrom) {
      this.noPasswordAllowFrom = noPasswordAllowFrom;
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
      return new RconConfig(this);
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
        + ", maxConnectionsPerIp="
        + maxConnectionsPerIp
        + ", maxFrameSize="
        + maxFrameSize
        + ", readTimeoutMs="
        + readTimeoutMs
        + ", connectionTimeoutMs="
        + connectionTimeoutMs
        + ", authTimeoutMs="
        + authTimeoutMs
        + ", authMaxFailures="
        + authMaxFailures
        + ", authFailureWindowMs="
        + authFailureWindowMs
        + ", authBanMs="
        + authBanMs
        + ", allowNoPassword="
        + allowNoPassword
        + ", noPasswordAllowFrom="
        + noPasswordAllowFrom
        + ", hasPassword="
        + requiresPassword()
        + '}';
  }
}
