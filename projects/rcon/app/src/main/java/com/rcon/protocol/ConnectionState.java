package com.rcon.protocol;

/**
 * Connection state machine for RCON protocol.
 * Simple and explicit state transitions as requested.
 */
public class ConnectionState {

    public enum State {
        UNAUTHENTICATED, // Initial state - socket connected, not authenticated
        AUTHENTICATED, // Auth succeeded - ready to accept commands
        CLOSED // Connection terminated
    }

    private State current = State.UNAUTHENTICATED;

    /**
     * Processes a packet and updates state if valid transition.
     * 
     * @return true if transition is valid, false otherwise
     */
    public TransitionResult processPacket(RconPacket packet) {
        switch (current) {
            case UNAUTHENTICATED:
                return handleUnauthenticatedState(packet);

            case AUTHENTICATED:
                return handleAuthenticatedState(packet);

            case CLOSED:
                return TransitionResult.failure("Connection is closed");

            default:
                return TransitionResult.failure("Unknown state: " + current);
        }
    }

    private TransitionResult handleUnauthenticatedState(RconPacket packet) {
        if (packet.getType() == RconPacket.SERVERDATA_AUTH) {
            // Auth packet received - transition to authenticated
            // (Server will send auth response, then state becomes authenticated)
            return TransitionResult.success("Auth packet received");
        } else {
            return TransitionResult.failure("Expected auth packet, got type: " + packet.getType());
        }
    }

    private TransitionResult handleAuthenticatedState(RconPacket packet) {
        if (packet.getType() == RconPacket.SERVERDATA_EXECCOMMAND) {
            // Command packet - stay in authenticated state
            return TransitionResult.success("Command received");
        } else if (packet.getType() == RconPacket.SERVERDATA_RESPONSE_VALUE) {
            // Response to previous command - stay in authenticated state
            return TransitionResult.success("Response received");
        } else {
            return TransitionResult.failure("Invalid packet type for authenticated state: " + packet.getType());
        }
    }

    public State getCurrentState() {
        return current;
    }

    public boolean isClosed() {
        return current == State.CLOSED;
    }

    public boolean isReadyForCommand() {
        return current == State.AUTHENTICATED;
    }

    /**
     * Mark connection as authenticated (called after sending auth response).
     */
    public void markAuthenticated() {
        if (current == State.UNAUTHENTICATED) {
            current = State.AUTHENTICATED;
        }
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