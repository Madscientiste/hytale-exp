package com.rcon.transport;

import com.rcon.infrastructure.RconConfig;
import com.rcon.infrastructure.RconLogger;
import com.rcon.protocol.RconPacket;
import com.rcon.protocol.RconProtocol;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main transport layer - handles TCP server and connection management.
 * 1 main thread accepts connections + 1 thread per connection handles that client.
 */
public class RconTransport {
    
    private final RconConfig config;
    private final RconLogger logger;
    private TransportCallbacks callbacks;
    private RconProtocol protocol;
    
    private ServerSocket serverSocket;
    private final ConcurrentHashMap<String, RconConnection> connections = new ConcurrentHashMap<>();
    private final AtomicInteger connectionCounter = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    private Thread acceptThread;
    
    public RconTransport(RconConfig config, RconLogger logger, TransportCallbacks callbacks) {
        this.config = config;
        this.logger = logger;
        this.callbacks = callbacks;
        this.protocol = new RconProtocol(logger);
    }
    
    /**
     * Start the TCP server and begin accepting connections.
     */
    public void start() throws Exception {
        if (running.compareAndSet(false, true)) {
            serverSocket = new ServerSocket(config.getPort(), 50, InetAddress.getByName(config.getHost()));
            
            // Configure server socket
            serverSocket.setSoTimeout(config.getConnectionTimeoutMs());
            serverSocket.setReuseAddress(true);
            
            acceptThread = new Thread(this::acceptLoop, "RconAccept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            
            logger.logServerStarted(config.getHost(), config.getPort());
        }
    }
    
    /**
     * Stop the TCP server and close all connections.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                // Stop accepting new connections
                if (serverSocket != null) {
                    serverSocket.close();
                }
                
                // Close all existing connections
                connections.forEach((id, conn) -> conn.close("Server shutdown"));
                connections.clear();
                
                // Wait for accept thread to finish
                if (acceptThread != null) {
                    acceptThread.interrupt();
                    acceptThread.join(1000);
                }
                
                logger.logServerStopped();
                
            } catch (Exception e) {
                logger.logError("Error stopping transport", e);
            }
        }
    }
    
    /**
     * Send data to a specific connection.
     */
    public void send(String connectionId, byte[] data) throws Exception {
        RconConnection connection = connections.get(connectionId);
        if (connection == null) {
            throw new Exception("Connection not found: " + connectionId);
        }
        
        connection.send(data);
    }
    
    /**
     * Close a specific connection.
     */
    public void closeConnection(String connectionId, String reason) {
        RconConnection connection = connections.get(connectionId);
        if (connection != null) {
            connection.close(reason);
        }
    }
    
    /**
     * Set callbacks (for delayed initialization).
     */
    public void setCallbacks(TransportCallbacks callbacks) {
        this.callbacks = callbacks;
    }
    
    /**
     * Get number of active connections.
     */
    public int getConnectionCount() {
        return connections.size();
    }
    
    /**
     * Check if a new connection can be accepted (connection limit).
     */
    public boolean canAcceptConnection() {
        return connections.size() < config.getMaxConnections();
    }
    
    /**
     * Main accept loop - runs in dedicated thread.
     */
    private void acceptLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Socket clientSocket = serverSocket.accept();
                
                if (!canAcceptConnection()) {
                    clientSocket.close();
                    logger.logError("Connection rejected: maximum connections reached");
                    continue;
                }
                
                // Configure client socket
                clientSocket.setSoTimeout(config.getReadTimeoutMs());
                clientSocket.setTcpNoDelay(true);
                
                // Create connection
                String connectionId = generateConnectionId();
                RconConnection connection = new RconConnection(connectionId, clientSocket, callbacks);
                
                // Track connection
                connections.put(connectionId, connection);
                connection.start();
                
                logger.logConnectionAccepted(connectionId, clientSocket.getInetAddress());
                
            } catch (java.net.SocketTimeoutException e) {
                // Timeout is expected - allows checking running flag periodically
                // Don't log as error, just continue the loop
                continue;
            } catch (Exception e) {
                if (running.get()) {
                    // Log actual errors, not timeouts
                    logger.logError("Error accepting connection", e);
                }
            }
        }
    }
    

    
    /**
     * Generate unique connection ID.
     */
    private String generateConnectionId() {
        return "conn-" + connectionCounter.incrementAndGet() + "-" + System.currentTimeMillis();
    }
    
    /**
     * Handle bytes received from connection.
     */
    public void onBytesReceived(String connectionId, byte[] data) {
        // Parse bytes using protocol layer
        RconProtocol.ProtocolResult result = protocol.parseBytes(connectionId, data);
        
        if (result instanceof RconProtocol.ProtocolSuccess success) {
            handlePackets(connectionId, success.getPackets());
        } else if (result instanceof RconProtocol.ProtocolError error) {
            handleProtocolError(connectionId, error.getErrorMessage());
        }
    }
    
    /**
     * Handle packets successfully parsed - delegate to application layer.
     */
    private void handlePackets(String connectionId, List<RconPacket> packets) {
        if (callbacks != null) {
            callbacks.onBytesReceived(connectionId, serializePackets(packets));
        }
    }
    
    /**
     * Handle protocol errors.
     */
    private void handleProtocolError(String connectionId, String error) {
        logger.logProtocolViolation(connectionId, error);
        closeConnection(connectionId, "Protocol error");
    }
    
    /**
     * Serialize packets back to bytes for application layer.
     */
    private byte[] serializePackets(List<RconPacket> packets) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (RconPacket packet : packets) {
                baos.write(packet.toBytes());
            }
            return baos.toByteArray();
        } catch (Exception e) {
            logger.logError("Failed to serialize packets", e);
            return new byte[0];
        }
    }
    
    
    
    /**
     * Check for idle connections and close them.
     */
    public void cleanupIdleConnections() {
        long now = System.currentTimeMillis();
        long idleTimeout = config.getReadTimeoutMs();
        
        connections.forEach((id, conn) -> {
            if (!conn.isClosed() && (now - conn.getLastActivity()) > idleTimeout) {
                conn.close("Idle timeout");
            }
        });
    }
}