package com.madscientiste.rcon.infrastructure;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Stateful service for loading and saving RCON configuration from/to disk. Uses a dedicated config
 * file at configs/com.madscientiste.rcon.json instead of storing in config.json.
 *
 * <p>The file holds the password hash, so it is written readable by its owner only. A file that
 * exists but cannot be used is reported with a {@link ConfigException} and never replaced.
 */
public class ConfigLoader {

  private static final Set<PosixFilePermission> OWNER_ONLY =
      PosixFilePermissions.fromString("rw-------");

  private static final Set<String> KNOWN_KEYS =
      Set.of(
          "host",
          "port",
          "maxConnections",
          "maxConnectionsPerIp",
          "maxFrameSize",
          "readTimeoutMs",
          "connectionTimeoutMs",
          "authTimeoutMs",
          "authMaxFailures",
          "authFailureWindowMs",
          "authBanMs",
          "passwordHash",
          "allowNoPassword",
          "noPasswordAllowFrom");

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
   * @throws ConfigException if the file exists but is unreadable, is not valid JSON, has an unknown
   *     key or a value of the wrong type or out of range
   */
  @Nonnull
  public RconConfig load() throws ConfigException {
    // Read first and ask afterwards: "does it exist?" answers no for a file in a folder we may
    // not look into, and a config we cannot read must never be taken for a missing one.
    String jsonContent;
    try {
      jsonContent = Files.readString(configPath);
    } catch (NoSuchFileException e) {
      return RconConfig.builder().build();
    } catch (IOException e) {
      throw new ConfigException("cannot read " + configPath + ": " + e, e);
    }

    // Tolerate a byte order mark left by some editors
    if (!jsonContent.isEmpty() && jsonContent.charAt(0) == 0xFEFF) {
      jsonContent = jsonContent.substring(1);
    }

    try {
      return deserialize(FlatJson.parse(jsonContent));
    } catch (IllegalArgumentException e) {
      throw new ConfigException(configPath + ": " + e.getMessage(), e);
    }
  }

  /**
   * Reload configuration from disk (fresh read, ignores any cached state).
   *
   * @return RconConfig loaded from file or defaults
   */
  @Nonnull
  public RconConfig reload() throws ConfigException {
    return load();
  }

  /**
   * Save configuration to disk, readable by the file owner only.
   *
   * @param config Config to save
   * @return true if save was successful, false otherwise
   */
  public boolean save(@Nonnull RconConfig config) {
    try {
      // Create parent directory if it doesn't exist
      Path parent = configPath.toAbsolutePath().getParent();
      if (parent != null && !Files.exists(parent)) {
        Files.createDirectories(parent);
      }

      writeOwnerOnly(FlatJson.format(serialize(config)));
      return true;
    } catch (Exception e) {
      System.err.println("Failed to save RCON config to " + configPath + ": " + e.getMessage());
      return false;
    }
  }

  /**
   * Make an existing config file readable by its owner only.
   *
   * @return true if the permissions were too wide and have been tightened
   * @throws IOException if the permissions are too wide and cannot be changed
   */
  public boolean restrictPermissions() throws IOException {
    if (!exists() || !isPosix()) {
      return false;
    }
    if (OWNER_ONLY.containsAll(Files.getPosixFilePermissions(configPath))) {
      return false;
    }
    Files.setPosixFilePermissions(configPath, OWNER_ONLY);
    return true;
  }

  /**
   * Update password hash in config file. Loads current config, updates password hash, and saves.
   *
   * @param passwordHash New password hash
   * @return true if update was successful, false otherwise
   */
  public boolean updatePassword(@Nonnull String passwordHash) throws ConfigException {
    RconConfig config = load();
    RconConfig updated = config.withPasswordHash(passwordHash);
    return save(updated);
  }

  /** Write through a private temp file, so the hash is never on disk with wider permissions. */
  private void writeOwnerOnly(String content) throws IOException {
    Path target = configPath.toAbsolutePath();
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");

    Files.deleteIfExists(tmp);
    if (isPosix()) {
      Files.createFile(tmp, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    }
    Files.writeString(tmp, content);

    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private boolean isPosix() {
    return configPath.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  /**
   * Turn the parsed JSON into an immutable RconConfig instance. Missing keys use defaults.
   *
   * @throws IllegalArgumentException on an unknown key, a wrong type or an out-of-range value
   */
  @Nonnull
  private RconConfig deserialize(@Nonnull Map<String, Object> map) {
    for (String key : map.keySet()) {
      if (!KNOWN_KEYS.contains(key)) {
        throw new IllegalArgumentException("unknown key \"" + key + "\"");
      }
    }

    RconConfig.Builder builder = RconConfig.builder();

    if (map.containsKey("host")) {
      builder.host(requireString(map, "host"));
    }
    if (map.containsKey("port")) {
      builder.port(requireInt(map, "port"));
    }
    if (map.containsKey("maxConnections")) {
      builder.maxConnections(requireInt(map, "maxConnections"));
    }
    if (map.containsKey("maxConnectionsPerIp")) {
      builder.maxConnectionsPerIp(requireInt(map, "maxConnectionsPerIp"));
    }
    if (map.containsKey("maxFrameSize")) {
      builder.maxFrameSize(requireInt(map, "maxFrameSize"));
    }
    if (map.containsKey("readTimeoutMs")) {
      builder.readTimeoutMs(requireInt(map, "readTimeoutMs"));
    }
    if (map.containsKey("connectionTimeoutMs")) {
      builder.connectionTimeoutMs(requireInt(map, "connectionTimeoutMs"));
    }
    if (map.containsKey("authTimeoutMs")) {
      builder.authTimeoutMs(requireInt(map, "authTimeoutMs"));
    }
    if (map.containsKey("authMaxFailures")) {
      builder.authMaxFailures(requireInt(map, "authMaxFailures"));
    }
    if (map.containsKey("authFailureWindowMs")) {
      builder.authFailureWindowMs(requireInt(map, "authFailureWindowMs"));
    }
    if (map.containsKey("authBanMs")) {
      builder.authBanMs(requireInt(map, "authBanMs"));
    }
    // "passwordHash": null means the same as leaving it out: no password set yet
    if (map.get("passwordHash") != null) {
      builder.passwordHash(requireString(map, "passwordHash"));
    }
    if (map.containsKey("allowNoPassword")) {
      builder.allowNoPassword(requireBoolean(map, "allowNoPassword"));
    }
    if (map.containsKey("noPasswordAllowFrom")) {
      builder.noPasswordAllowFrom(requireStringList(map, "noPasswordAllowFrom"));
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
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("host", config.getHost());
    map.put("port", config.getPort());
    map.put("maxConnections", config.getMaxConnections());
    map.put("maxConnectionsPerIp", config.getMaxConnectionsPerIp());
    map.put("maxFrameSize", config.getMaxFrameSize());
    map.put("readTimeoutMs", config.getReadTimeoutMs());
    map.put("connectionTimeoutMs", config.getConnectionTimeoutMs());
    map.put("authTimeoutMs", config.getAuthTimeoutMs());
    map.put("authMaxFailures", config.getAuthMaxFailures());
    map.put("authFailureWindowMs", config.getAuthFailureWindowMs());
    map.put("authBanMs", config.getAuthBanMs());
    if (config.getPasswordHash() != null) {
      map.put("passwordHash", config.getPasswordHash());
    }
    map.put("allowNoPassword", config.isAllowNoPassword());
    List<String> allowFrom = new ArrayList<>();
    for (AddressRange range : config.getNoPasswordAllowFrom()) {
      allowFrom.add(range.toString());
    }
    map.put("noPasswordAllowFrom", allowFrom);
    return map;
  }

  private static boolean requireBoolean(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value instanceof Boolean b) {
      return b;
    }
    throw new IllegalArgumentException("\"" + key + "\" must be true or false, without quotes");
  }

  private static List<String> requireStringList(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value instanceof List<?> list) {
      List<String> result = new ArrayList<>();
      for (Object item : list) {
        result.add((String) item); // FlatJson only builds lists of strings
      }
      return result;
    }
    throw new IllegalArgumentException(
        "\"" + key + "\" must be a list of strings, for example [\"127.0.0.0/8\", \"::1\"]");
  }

  private static String requireString(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException("\"" + key + "\" must be a string in double quotes");
  }

  private static int requireInt(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
      return l.intValue();
    }
    throw new IllegalArgumentException("\"" + key + "\" must be a whole number without quotes");
  }
}
