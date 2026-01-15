package com.rcon.protocol;

/**
 * Exception thrown for protocol-related errors.
 * This is used to distinguish protocol parsing errors from other errors.
 */
public class ProtocolException extends Exception {
    
    public ProtocolException(String message) {
        super(message);
    }
    
    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}