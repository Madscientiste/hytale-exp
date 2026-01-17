package com.madscientiste.rcon.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Stateful service for loading and saving RCON configuration from/to disk. Uses a dedicated config
 * file at configs/com.madscientiste.rcon.json instead of storing in config.json.
 */
public class ConfigLoader {
  private final Path configPath;

  /**
   * Create a ConfigLoader for the specified config file path.
   *
   * @param configPath Path to the config JSON file
   */
  public ConfigLoader(@Nonnull Path configPath) {
    this.configPath = configPath;
  }

  /**
   * Get the config file path.
   *
   * @return Path to config file
   */
  @Nonnull
  public Path getConfigPath() {
    return configPath;
  }

  /**
   * Check if the config file exists.
   *
   * @return true if file exists, false otherwise
   */
  public boolean exists() {
    return Files.exists(configPath);
  }

  /**
   * Load configuration from disk. Returns defaults if file doesn't exist.
   *
   * @return RconConfig loaded from file or defaults
   */
  @Nonnull
  public RconConfig load() {
    if (!exists()) {
      return RconConfig.builder().build();
    }

    try {
      String jsonContent = Files.readString(configPath);
      Map<String, Object> configMap = parseJsonObject(jsonContent);
      return deserialize(configMap);
    } catch (Exception e) {
      System.err.println("Failed to load RCON config from " + configPath + ": " + e.getMessage());
      return RconConfig.builder().build();
    }
  }

  /**
   * Reload configuration from disk (fresh read, ignores any cached state).
   *
   * @return RconConfig loaded from file or defaults
   */
  @Nonnull
  public RconConfig reload() {
    return load();
  }

  /**
   * Save configuration to disk.
   *
   * @param config Config to save
   * @return true if save was successful, false otherwise
   */
  public boolean save(@Nonnull RconConfig config) {
    try {
      // Create parent directory if it doesn't exist
      Path parent = configPath.getParent();
      if (parent != null && !Files.exists(parent)) {
        Files.createDirectories(parent);
      }

      Map<String, Object> configMap = serialize(config);
      String jsonContent = formatJsonObject(configMap);
      Files.writeString(configPath, jsonContent);
      return true;
    } catch (Exception e) {
      System.err.println("Failed to save RCON config to " + configPath + ": " + e.getMessage());
      return false;
    }
  }

  /**
   * Update password hash in config file. Loads current config, updates password hash, and saves.
   *
   * @param passwordHash New password hash
   * @return true if update was successful, false otherwise
   */
  public boolean updatePassword(@Nonnull String passwordHash) {
    RconConfig config = load();
    RconConfig updated = config.withPasswordHash(passwordHash);
    return save(updated);
  }

  /**
   * Deserialize a Map (from JSON) into an immutable RconConfig instance.
   *
   * @param map Map containing config values
   * @return RconConfig instance with values from map, using defaults for missing values
   */
  @Nonnull
  private RconConfig deserialize(@Nullable Map<String, Object> map) {
    RconConfig.Builder builder = RconConfig.builder();

    if (map == null || map.isEmpty()) {
      return builder.build();
    }

    // Use defaults that match Builder defaults
    if (map.containsKey("host")) {
      builder.host(getString(map, "host", RconConstants.DEFAULT_HOST));
    }
    if (map.containsKey("port")) {
      builder.port(getInt(map, "port", RconConstants.DEFAULT_PORT));
    }
    if (map.containsKey("maxConnections")) {
      builder.maxConnections(getInt(map, "maxConnections", RconConstants.DEFAULT_MAX_CONNECTIONS));
    }
    if (map.containsKey("maxFrameSize")) {
      builder.maxFrameSize(getInt(map, "maxFrameSize", RconConstants.DEFAULT_MAX_FRAME_SIZE));
    }
    if (map.containsKey("readTimeoutMs")) {
      builder.readTimeoutMs(getInt(map, "readTimeoutMs", RconConstants.DEFAULT_READ_TIMEOUT_MS));
    }
    if (map.containsKey("connectionTimeoutMs")) {
      builder.connectionTimeoutMs(
          getInt(map, "connectionTimeoutMs", RconConstants.DEFAULT_CONNECTION_TIMEOUT_MS));
    }
    if (map.containsKey("passwordHash")) {
      builder.passwordHash(getString(map, "passwordHash", null));
    }

    return builder.build();
  }

  /**
   * Serialize an immutable RconConfig to a Map (for JSON writing).
   *
   * @param config Config to serialize
   * @return Map representation of config
   */
  @Nonnull
  private Map<String, Object> serialize(@Nonnull RconConfig config) {
    Map<String, Object> map = new HashMap<>();
    map.put("host", config.getHost());
    map.put("port", config.getPort());
    map.put("maxConnections", config.getMaxConnections());
    map.put("maxFrameSize", config.getMaxFrameSize());
    map.put("readTimeoutMs", config.getReadTimeoutMs());
    map.put("connectionTimeoutMs", config.getConnectionTimeoutMs());
    if (config.getPasswordHash() != null) {
      map.put("passwordHash", config.getPasswordHash());
    }
    return map;
  }

  /** Simple JSON object parser for key-value pairs. Handles: "key": "value" and "key": number. */
  @Nonnull
  private Map<String, Object> parseJsonObject(@Nonnull String json) {
    Map<String, Object> result = new HashMap<>();

    // Pattern to match: "key": value (where value can be string or number)
    java.util.regex.Pattern pattern =
        java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*([^,}\\]]+)");
    java.util.regex.Matcher matcher = pattern.matcher(json);

    while (matcher.find()) {
      String key = matcher.group(1);
      String value = matcher.group(2).trim();

      // Remove quotes if present
      if (value.startsWith("\"") && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1);
        result.put(key, value);
      } else {
        // Try to parse as number
        try {
          if (value.contains(".")) {
            result.put(key, Double.parseDouble(value));
          } else {
            result.put(key, Integer.parseInt(value));
          }
        } catch (NumberFormatException e) {
          // Keep as string
          result.put(key, value);
        }
      }
    }

    return result;
  }

  /** Format a Map as a JSON object string. */
  @Nonnull
  private String formatJsonObject(@Nonnull Map<String, Object> map) {
    StringBuilder json = new StringBuilder("{\n");
    boolean first = true;

    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (!first) {
        json.append(",\n");
      }
      first = false;

      json.append("  \"").append(escapeJsonString(entry.getKey())).append("\": ");
      Object value = entry.getValue();
      if (value instanceof String) {
        json.append("\"").append(escapeJsonString((String) value)).append("\"");
      } else {
        json.append(value);
      }
    }

    json.append("\n}");
    return json.toString();
  }

  /** Escape special characters in JSON string values. */
  @Nonnull
  private String escapeJsonString(@Nonnull String str) {
    return str.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private static String getString(Map<String, Object> map, String key, String defaultValue) {
    Object value = map.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof String) {
      return (String) value;
    }
    return String.valueOf(value);
  }

  private static int getInt(Map<String, Object> map, String key, int defaultValue) {
    Object value = map.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
