package com.rcon.application;

import com.rcon.command.CommandDispatcher;
import com.rcon.infrastructure.RconLogger;
import com.rcon.protocol.ConnectionState;
import com.rcon.protocol.RconPacket;
import com.rcon.protocol.RconProtocol;
import com.rcon.transport.RconTransport;
import com.rcon.transport.TransportCallbacks;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Application layer - thin coordinator between transport, protocol, and command layers.
 * Glue protocol ↔ execution, connection-scoped state, routing messages to handlers.
 */
public class RconApplication implements TransportCallbacks {
    
    private final RconTransport transport;
    private final RconProtocol protocol;
    private final CommandDispatcher commandDispatcher;
    private final RconLogger logger;
    
    // Per-connection state
    private final ConcurrentHashMap<String, ConnectionState> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger authRequestId = new AtomicInteger(1000);
    private final AtomicInteger commandRequestId = new AtomicInteger(2000);
    
    public RconApplication(RconTransport transport, RconProtocol protocol, 
                         CommandDispatcher commandDispatcher, RconLogger logger) {
        this.transport = transport;
        this.protocol = protocol;
        this.commandDispatcher = commandDispatcher;
        this.logger = logger;
    }
    
    /**
     * Start the application layer.
     */
    public void start() throws Exception {
        transport.start();
        logger.logEvent("APP_STARTED", "system", java.util.Map.of("transport", "started"));
    }
    
    /**
     * Stop the application layer.
     */
    public void stop() {
        transport.stop();
        logger.logEvent("APP_STOPPED", "system", java.util.Map.of("transport", "stopped"));
    }
    
    // TransportCallbacks implementation
    
    @Override
    public void onBytesReceived(String connectionId, byte[] data) {
        try {
            // Parse bytes using protocol layer
            RconProtocol.ProtocolResult result = protocol.parseBytes(connectionId, data);
            
            if (result instanceof RconProtocol.ProtocolSuccess success) {
                handlePackets(connectionId, success.getPackets());
            } else if (result instanceof RconProtocol.ProtocolError error) {
                handleProtocolError(connectionId, error.getErrorMessage());
            }
            
        } catch (Exception e) {
            // Never crash - isolate to connection
            logger.logError("Error processing bytes for " + connectionId, e);
            closeConnection(connectionId, "Processing error");
        }
    }
    
    @Override
    public void onConnectionClosed(String connectionId, String reason) {
        // Clean up session state
        ConnectionState state = sessions.remove(connectionId);
        if (state != null) {
            state.close();
        }
        
        logger.logConnectionClosed(connectionId, reason);
    }
    
    // Private methods
    
    /**
     * Handle parsed packets from protocol layer.
     */
    private void handlePackets(String connectionId, java.util.List<RconPacket> packets) throws Exception {
        ConnectionState state = sessions.computeIfAbsent(connectionId, k -> new ConnectionState());
        
        for (RconPacket packet : packets) {
            // Process state transition
            ConnectionState.TransitionResult transition = state.processPacket(packet);
            
            if (!transition.isSuccess()) {
                logger.logProtocolViolation(connectionId, transition.getMessage());
                closeConnection(connectionId, "Protocol violation");
                return;
            }
            
            // Handle packet based on type and state
            if (packet.getType() == RconPacket.SERVERDATA_AUTH) {
                handleAuthPacket(connectionId, packet);
            } else if (packet.getType() == RconPacket.SERVERDATA_EXECCOMMAND && state.isReadyForCommand()) {
                handleCommandPacket(connectionId, packet);
            } else if (packet.getType() == RconPacket.SERVERDATA_RESPONSE_VALUE) {
                // Response to previous command - just acknowledge
                logger.logDebug(connectionId, "Response received");
            }
        }
    }
    
    /**
     * Handle authentication packet (MVP: always succeeds).
     */
    private void handleAuthPacket(String connectionId, RconPacket packet) throws Exception {
        // MVP: Always accept authentication (no password validation)
        int responseId = packet.getId();
        RconPacket response = protocol.createAuthResponse(responseId, true);
        
        byte[] responseData = protocol.formatPacket(response);
        transport.send(connectionId, responseData);
        
        logger.logDebug(connectionId, "Auth processed: " + responseId);
    }
    
    /**
     * Handle command execution packet (MVP: echo).
     */
    private void handleCommandPacket(String connectionId, RconPacket packet) throws Exception {
        String command = packet.getBody();
        String response = commandDispatcher.execute(command);
        
        RconPacket responsePacket = protocol.createCommandResponse(packet.getId(), response);
        byte[] responseData = protocol.formatPacket(responsePacket);
        transport.send(connectionId, responseData);
        
        logger.logDebug(connectionId, "Command executed: " + command);
    }
    
    /**
     * Handle protocol parsing errors.
     */
    private void handleProtocolError(String connectionId, String error) {
        logger.logProtocolViolation(connectionId, error);
        closeConnection(connectionId, "Protocol error");
    }
    
    /**
     * Close a connection.
     */
    private void closeConnection(String connectionId, String reason) {
        transport.closeConnection(connectionId, reason);
    }
    
    /**
     * Get application statistics.
     */
    public ApplicationStats getStats() {
        return new ApplicationStats(
            transport.getConnectionCount(),
            sessions.size()
        );
    }
    
    /**
     * Application statistics.
     */
    public static class ApplicationStats {
        public final int connectionCount;
        public final int sessionCount;
        
        public ApplicationStats(int connectionCount, int sessionCount) {
            this.connectionCount = connectionCount;
            this.sessionCount = sessionCount;
        }
        
        @Override
        public String toString() {
            return "ApplicationStats{connections=" + connectionCount + 
                   ", sessions=" + sessionCount + "}";
        }
    }
}