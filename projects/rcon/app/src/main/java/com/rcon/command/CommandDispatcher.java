package com.rcon.command;

import java.util.HashMap;
import java.util.Map;

/**
 * Command dispatcher - routes command strings to appropriate Command implementations.
 * MVP: Only supports echo command.
 */
public class CommandDispatcher {
    
    private final Map<String, Command> commands = new HashMap<>();
    private final Command defaultCommand;
    
    public CommandDispatcher() {
        // MVP: Register only echo command
        Command echoCommand = new EchoCommand();
        commands.put("echo", echoCommand);
        commands.put("help", echoCommand); // Alias echo as help for testing
        
        // Default command for MVP - just echo everything
        this.defaultCommand = echoCommand;
    }
    
    /**
     * Execute a command by name.
     * @param commandLine Full command line (e.g., "echo hello world")
     * @return Command result
     * @throws Exception If execution fails
     */
    public String execute(String commandLine) throws Exception {
        if (commandLine == null || commandLine.trim().isEmpty()) {
            return defaultCommand.execute("");
        }
        
        String[] parts = commandLine.trim().split("\\s+", 2);
        String commandName = parts[0].toLowerCase();
        String arguments = parts.length > 1 ? parts[1] : "";
        
        Command command = commands.get(commandName);
        if (command == null) {
            // MVP: Unknown commands get echoed back
            command = defaultCommand;
        }
        
        return command.execute(arguments);
    }
    
    /**
     * Check if a command exists.
     */
    public boolean hasCommand(String commandName) {
        return commands.containsKey(commandName.toLowerCase());
    }
    
    /**
     * Get command by name.
     */
    public Command getCommand(String commandLine) {
        if (commandLine == null || commandLine.trim().isEmpty()) {
            return defaultCommand;
        }
        
        String commandName = commandLine.trim().split("\\s+", 2)[0].toLowerCase();
        return commands.getOrDefault(commandName, defaultCommand);
    }
    
    /**
     * Register a new command (for future extensibility).
     */
    public void registerCommand(String name, Command command) {
        commands.put(name.toLowerCase(), command);
    }
}