package com.rcon.infrastructure;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple logging infrastructure for RCON server.
 * Provides structured logging without external dependencies for MVP.
 */
public class RconLogger {
    private static final java.util.logging.Logger logger = 
        java.util.logging.Logger.getLogger("RCON");
    
    // Rate limiting for violation logs (simple approach)
    private final Map<String, Long> lastViolationLog = new ConcurrentHashMap<>();
    private static final long VIOLATION_LOG_COOLDOWN_MS = 5000; // 5 seconds
    
    public void logServerStarted(String host, int port) {
        logger.info("RCON server started on " + host + ":" + port + " at " + Instant.now());
    }
    
    public void logServerStopped() {
        logger.info("RCON server stopped at " + Instant.now());
    }
    
    public void logConnectionAccepted(String connectionId, InetAddress address) {
        logger.info("[" + connectionId + "] Connection accepted from " + 
                   address.getHostAddress() + " at " + Instant.now());
    }
    
    public void logConnectionClosed(String connectionId, String reason) {
        logger.info("[" + connectionId + "] Connection closed: " + 
                   reason + " at " + Instant.now());
    }
    
    public void logProtocolViolation(String connectionId, String violation) {
        // Rate limit violation logs
        String key = connectionId + ":" + violation;
        Long lastLogged = lastViolationLog.get(key);
        
        if (lastLogged == null || 
            (System.currentTimeMillis() - lastLogged) > VIOLATION_LOG_COOLDOWN_MS) {
            
            logger.warning("[" + connectionId + "] Protocol violation: " + 
                          violation + " at " + Instant.now());
            lastViolationLog.put(key, System.currentTimeMillis());
        } else {
            logger.fine("[" + connectionId + "] Protocol violation (rate-limited): " + violation);
        }
    }
    
    public void logPacketReceived(String connectionId, int packetId, int packetType, int bodyLength) {
        logger.fine("[" + connectionId + "] Packet received: id=" + packetId + 
                   ", type=" + packetType + ", bodyLength=" + bodyLength);
    }
    
    public void logPacketSent(String connectionId, int packetId, int packetType, int bodyLength) {
        logger.fine("[" + connectionId + "] Packet sent: id=" + packetId + 
                   ", type=" + packetType + ", bodyLength=" + bodyLength);
    }
    
    public void logError(String message, Throwable cause) {
        logger.severe(message + ": " + cause.getMessage());
    }
    
    public void logError(String message) {
        logger.severe(message);
    }
    
    public void logDebug(String connectionId, String message) {
        logger.fine("[" + connectionId + "] " + message);
    }
    
    // Generic structured logging for future extensibility
    public void logEvent(String eventType, String connectionId, Map<String, Object> details) {
        StringBuilder sb = new StringBuilder();
        if (connectionId != null && !connectionId.isEmpty()) {
            sb.append("[").append(connectionId).append("] ");
        }
        sb.append("Event: ").append(eventType);
        
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            sb.append(", ").append(entry.getKey()).append("=").append(entry.getValue());
        }
        
        logger.info(sb.toString() + " at " + Instant.now());
    }
}