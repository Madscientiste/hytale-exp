package com.madscientiste.rcon.command;

import java.util.HashMap;
import java.util.Map;

/**
 * Command dispatcher - routes command strings to appropriate Command implementations. Phase 2:
 * Integrates with Hytale server commands.
 */
public class CommandDispatcher {

  private final Map<String, Command> commands = new HashMap<>();
  private volatile Command defaultCommand;

  public CommandDispatcher() {
    // Register echo command for testing/debugging
    Command echoCommand = new EchoCommand();
    commands.put("echo", echoCommand);
    // Default command is lazily initialized to avoid loading Hytale classes in
    // tests
  }

  /**
   * Get the default command (HytaleCommand), creating it lazily if needed. This avoids loading
   * Hytale classes during construction, which allows tests to run without HytaleServer.jar in the
   * classpath.
   */
  private Command getDefaultCommand() {
    if (defaultCommand == null) {
      synchronized (this) {
        if (defaultCommand == null) {
          defaultCommand = new HytaleCommand();
        }
      }
    }
    return defaultCommand;
  }

  /**
   * Execute a command by name.
   *
   * @param commandLine Full command line (e.g., "echo hello world")
   * @return Command result
   * @throws Exception If execution fails
   */
  public String execute(String commandLine) throws Exception {
    if (commandLine == null || commandLine.trim().isEmpty()) {
      return getDefaultCommand().execute("");
    }

    String trimmed = commandLine.trim();
    String[] parts = trimmed.split("\\s+", 2);
    String commandName = parts[0].toLowerCase();
    String arguments = parts.length > 1 ? parts[1] : "";

    Command command = commands.get(commandName);
    if (command == null) {
      // Unknown commands are passed through to Hytale server
      // Pass full command line (including command name) to HytaleCommand
      return getDefaultCommand().execute(trimmed);
    }

    // Known RCON commands (like echo) get just the arguments
    return command.execute(arguments);
  }

  /**
   * Check if a command exists.
   *
   * @param commandName Command name to check
   * @return true if command is registered
   */
  public boolean hasCommand(String commandName) {
    return commands.containsKey(commandName.toLowerCase());
  }

  /**
   * Get command by name.
   *
   * @param commandLine Full command line
   * @return Command instance or default command if not found
   */
  public Command getCommand(String commandLine) {
    if (commandLine == null || commandLine.trim().isEmpty()) {
      return getDefaultCommand();
    }

    String commandName = commandLine.trim().split("\\s+", 2)[0].toLowerCase();
    return commands.getOrDefault(commandName, getDefaultCommand());
  }

  /**
   * Register a new command.
   *
   * @param name Command name
   * @param command Command implementation
   */
  public void registerCommand(String name, Command command) {
    commands.put(name.toLowerCase(), command);
  }
}
