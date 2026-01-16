package com.madscientiste.rcon.command;

import com.hypixel.hytale.server.core.command.system.CommandManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Command implementation that executes Hytale server commands. Uses CommandManager to execute
 * commands as if from console.
 */
public class HytaleCommand implements Command {

  private static final long COMMAND_TIMEOUT_SECONDS = 30;

  @Override
  public String execute(String arguments) throws Exception {
    CommandManager commandManager = CommandManager.get();
    if (commandManager == null) {
      return "Error: CommandManager not available. Server may not be fully initialized.";
    }

    RconCommandSender sender = new RconCommandSender();

    // Prepare command string (remove leading slash if present, like console does)
    String commandString = arguments != null ? arguments.trim() : "";
    if (commandString.isEmpty()) {
      return "";
    }

    CompletableFuture<Void> future = commandManager.handleCommand(sender, commandString);

    try {
      future.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      return "Error: Command execution timed out after " + COMMAND_TIMEOUT_SECONDS + " seconds.";
    } catch (Exception e) {
      // Command may have failed, but we still want to capture any error messages
      // that were sent via sendMessage before the exception
      String output = sender.getCapturedOutput();
      if (output.isEmpty()) {
        // No output captured, so we will gently
        // with love and kindness return the error message.
        // ... i'm tired ok
        String errorMsg = e.getMessage();
        return errorMsg != null ? "Error: " + errorMsg : "Error: Command execution failed.";
      }
      // Return captured output (may include error messages)
      return output;
    }

    String output = sender.getCapturedOutput();

    // Return output (empty string if no output, which is valid for some commands)
    return output;
  }

  @Override
  public boolean requiresAuth() {
    // Commands still require RCON authentication
    // but that's handled at protocol level so we can ignore it.
    // for now ? i don't know, let's see how it goes.
    return false;
  }

  @Override
  public String getDescription() {
    return "Executes Hytale server commands";
  }
}
