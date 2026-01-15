package com.rcon.command;

/**
 * Simple echo command for MVP.
 * Returns exactly what was received.
 */
public class EchoCommand implements Command {
    
    @Override
    public String execute(String arguments) throws Exception {
        // Simple echo - return exactly what was received
        return arguments != null ? arguments : "";
    }
    
    @Override
    public boolean requiresAuth() {
        return false; // MVP allows all
    }
    
    @Override
    public String getDescription() {
        return "Echo command - returns input text (MVP)";
    }
}