package com.rcon.infrastructure;

/**
 * Configuration class for RCON server.
 * This is a dummy config for MVP - will be replaced with external config later.
 */
public class RconConfig {
    private final String host;
    private final int port;
    private final int maxConnections;
    private final int maxFrameSize;
    private final int readTimeoutMs;
    private final int connectionTimeoutMs;
    
    // Default constructor with MVP defaults
    public RconConfig() {
        this("127.0.0.1", 25575, 10, 4096, 30000, 5000);
    }
    
    // Constructor for testing/future external config
    public RconConfig(String host, int port, int maxConnections, 
                     int maxFrameSize, int readTimeoutMs, int connectionTimeoutMs) {
        this.host = host;
        this.port = port;
        this.maxConnections = maxConnections;
        this.maxFrameSize = maxFrameSize;
        this.readTimeoutMs = readTimeoutMs;
        this.connectionTimeoutMs = connectionTimeoutMs;
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
    
    @Override
    public String toString() {
        return "RconConfig{" +
               "host='" + host + '\'' +
               ", port=" + port +
               ", maxConnections=" + maxConnections +
               ", maxFrameSize=" + maxFrameSize +
               ", readTimeoutMs=" + readTimeoutMs +
               ", connectionTimeoutMs=" + connectionTimeoutMs +
               '}';
    }
}