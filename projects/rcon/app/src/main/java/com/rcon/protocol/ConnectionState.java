package com.rcon.protocol;

/**
 * Connection state machine for RCON protocol.
 * Simple and explicit state transitions as requested.
 */
public class ConnectionState {
    
    public enum State {
        CONNECTED,    // Initial state - socket connected
        AUTHENTICATED, // Auth packet received and valid
        READY,        // Ready to accept commands
        CLOSED        // Connection terminated
    }
    
    private State current = State.CONNECTED;
    
    /**
     * Processes a packet and updates state if valid transition.
     * @return true if transition is valid, false otherwise
     */
    public TransitionResult processPacket(RconPacket packet) {
        switch (current) {
            case CONNECTED:
                return handleConnectedState(packet);
                
            case AUTHENTICATED:
                return handleAuthenticatedState(packet);
                
            case READY:
                return handleReadyState(packet);
                
            case CLOSED:
                return TransitionResult.failure("Connection is closed");
                
            default:
                return TransitionResult.failure("Unknown state: " + current);
        }
    }
    
    private TransitionResult handleConnectedState(RconPacket packet) {
        if (packet.getType() == RconPacket.SERVERDATA_AUTH) {
            current = State.AUTHENTICATED;
            return TransitionResult.success("Authentication processed");
        } else {
            return TransitionResult.failure("Expected auth packet, got type: " + packet.getType());
        }
    }
    
    private TransitionResult handleAuthenticatedState(RconPacket packet) {
        if (packet.getType() == RconPacket.SERVERDATA_AUTH_RESPONSE) {
            current = State.READY;
            return TransitionResult.success("Ready for commands");
        } else {
            return TransitionResult.failure("Expected auth response, got type: " + packet.getType());
        }
    }
    
    private TransitionResult handleReadyState(RconPacket packet) {
        if (packet.getType() == RconPacket.SERVERDATA_EXECCOMMAND) {
            // Stay in READY state for command processing
            return TransitionResult.success("Command received");
        } else if (packet.getType() == RconPacket.SERVERDATA_RESPONSE_VALUE) {
            // Response to previous command
            return TransitionResult.success("Response received");
        } else {
            return TransitionResult.failure("Invalid packet type for ready state: " + packet.getType());
        }
    }
    
    public State getCurrentState() {
        return current;
    }
    
    public boolean isClosed() {
        return current == State.CLOSED;
    }
    
    public boolean isReadyForCommand() {
        return current == State.READY;
    }
    
    public void close() {
        current = State.CLOSED;
    }
    
    /**
     * Result of a state transition attempt.
     */
    public static class TransitionResult {
        private final boolean success;
        private final String message;
        
        private TransitionResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
        
        public static TransitionResult success(String message) {
            return new TransitionResult(true, message);
        }
        
        public static TransitionResult failure(String message) {
            return new TransitionResult(false, message);
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public String getMessage() {
            return message;
        }
    }
}