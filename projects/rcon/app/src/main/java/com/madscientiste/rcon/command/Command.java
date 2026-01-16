package com.madscientiste.rcon.command;

/**
 * Command interface for RCON command execution. Simple and extensible - MVP uses echo, future can
 * add real commands.
 */
public interface Command {

  /**
   * Execute the command with given arguments.
   *
   * @param arguments Command arguments (may be empty)
   * @return Command result/output
   * @throws Exception If execution fails
   */
  String execute(String arguments) throws Exception;

  /** Check if this command requires authentication. MVP: false for all commands */
  boolean requiresAuth();

  /** Get command description. */
  String getDescription();
}
