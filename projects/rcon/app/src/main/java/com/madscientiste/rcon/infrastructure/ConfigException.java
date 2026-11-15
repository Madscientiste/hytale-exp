package com.madscientiste.rcon.infrastructure;

/**
 * The config file exists but cannot be used: unreadable, not valid JSON, an unknown key or a value
 * out of range. The file is never replaced when this is thrown.
 */
public class ConfigException extends Exception {

  public ConfigException(String message) {
    super(message);
  }

  public ConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}
