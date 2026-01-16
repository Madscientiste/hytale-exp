package com.rcon.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.util.MessageUtil;
import org.jline.utils.AttributedString;

/**
 * Utility class to convert Hytale Message objects to plain text strings. Strips ANSI color codes
 * for RCON protocol compatibility.
 */
public class MessageConverter {

  /**
   * Convert a Message to plain text string, stripping ANSI codes.
   *
   * @param message The message to convert
   * @return Plain text representation of the message
   */
  public static String toPlainText(Message message) {
    if (message == null) {
      return "";
    }

    try {
      // Convert Message to AttributedString (includes ANSI formatting)
      AttributedString attributedString = MessageUtil.toAnsiString(message);

      // Convert to ANSI string, then strip ANSI codes
      String ansiString = attributedString.toAnsi();

      // Strip ANSI escape sequences
      return stripAnsiCodes(ansiString);
    } catch (Exception e) {
      // Fallback: try to get raw text or use toString
      try {
        String rawText = message.getAnsiMessage();
        if (rawText != null) {
          return stripAnsiCodes(rawText);
        }
      } catch (Exception ex) {
        // Ignore
      }

      // Last resort: use toString and strip ANSI
      return stripAnsiCodes(message.toString());
    }
  }

  /**
   * Strip ANSI escape codes from a string. Removes color codes, formatting codes, and other ANSI
   * sequences.
   *
   * @param text Text that may contain ANSI codes
   * @return Plain text without ANSI codes
   */
  private static String stripAnsiCodes(String text) {
    if (text == null) {
      return "";
    }

    // Remove ANSI escape sequences (ESC[ followed by parameters and command)
    // Pattern: \x1B\[[0-9;]*[a-zA-Z]
    return text.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "");
  }
}
