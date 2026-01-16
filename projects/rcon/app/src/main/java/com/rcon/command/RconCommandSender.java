package com.rcon.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.Nonnull;

/**
 * CommandSender implementation for RCON command execution. Captures all messages sent during
 * command execution for RCON response. Grants all permissions (like console).
 */
public class RconCommandSender implements CommandSender {

  private final UUID uuid;
  private final ConcurrentLinkedQueue<String> capturedMessages;

  public RconCommandSender() {
    // Generate unique UUID for this sender
    this.uuid = UUID.randomUUID();
    this.capturedMessages = new ConcurrentLinkedQueue<>();
  }

  @Override
  public void sendMessage(@Nonnull Message message) {
    // Convert message to plain text and capture it
    String plainText = MessageConverter.toPlainText(message);
    if (plainText != null && !plainText.trim().isEmpty()) {
      capturedMessages.offer(plainText);
    }
  }

  @Override
  @Nonnull
  public String getDisplayName() {
    return "RCON";
  }

  @Override
  @Nonnull
  public UUID getUuid() {
    return uuid;
  }

  @Override
  public boolean hasPermission(@Nonnull String id) {
    // RCON has all permissions, like console
    return true;
  }

  @Override
  public boolean hasPermission(@Nonnull String id, boolean def) {
    // RCON has all permissions, like console
    return true;
  }

  /**
   * Get all captured messages as a single string. Messages are joined with newlines.
   *
   * @return Combined message output
   */
  public String getCapturedOutput() {
    if (capturedMessages.isEmpty()) {
      return "";
    }

    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (String message : capturedMessages) {
      if (!first) {
        sb.append("\n");
      }
      sb.append(message);
      first = false;
    }
    return sb.toString();
  }

  /** Clear all captured messages. */
  public void clear() {
    capturedMessages.clear();
  }
}
