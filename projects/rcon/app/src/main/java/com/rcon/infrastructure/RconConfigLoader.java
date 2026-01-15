package com.rcon.infrastructure;

import com.hypixel.hytale.server.core.HytaleServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Loads RCON configuration from server config.json.
 * Reads from the "Mods" section in config.json.
 */
public class RconConfigLoader {

    private static final String CONFIG_FILE = "config.json";
    private static final String MODS_SECTION = "Mods";
    private static final String RCON_PLUGIN_ID = "com.rcon:Rcon";

    /**
     * Load RCON configuration from server config.json.
     * Falls back to defaults if config not found or invalid.
     * 
     * @return RconConfig loaded from config.json or defaults
     */
    @Nonnull
    public static RconConfig load() {
        try {
            Path configPath = findConfigFile();
            if (configPath == null || !Files.exists(configPath)) {
                return new RconConfig(); // Use defaults
            }

            // Read and parse JSON file
            String jsonContent = Files.readString(configPath);
            Map<String, Object> rconConfig = extractRconConfig(jsonContent);

            if (rconConfig == null || rconConfig.isEmpty()) {
                return new RconConfig(); // Use defaults
            }

            // Extract configuration values
            String host = getString(rconConfig, "host", "127.0.0.1");
            int port = getInt(rconConfig, "port", 25575);
            int maxConnections = getInt(rconConfig, "maxConnections", 10);
            int maxFrameSize = getInt(rconConfig, "maxFrameSize", 4096);
            int readTimeoutMs = getInt(rconConfig, "readTimeoutMs", 30000);
            int connectionTimeoutMs = getInt(rconConfig, "connectionTimeoutMs", 5000);

            return new RconConfig(host, port, maxConnections, maxFrameSize, readTimeoutMs, connectionTimeoutMs);

        } catch (Exception e) {
            // Log error but return defaults
            System.err.println("Failed to load RCON config from config.json: " + e.getMessage());
            return new RconConfig(); // Use defaults
        }
    }

    /**
     * Find the server's config.json file.
     * Tries multiple locations:
     * 1. Current working directory
     * 2. Parent directory (if running from .server/)
     * 3. Relative to plugin location
     */
    @Nullable
    private static Path findConfigFile() {
        // Try current working directory
        Path configPath = Paths.get(CONFIG_FILE);
        if (Files.exists(configPath)) {
            return configPath;
        }

        // Try parent directory (common for .server/config.json)
        configPath = Paths.get("..", CONFIG_FILE).normalize();
        if (Files.exists(configPath)) {
            return configPath;
        }

        // Try to get from server instance if available
        try {
            HytaleServer server = HytaleServer.get();
            if (server != null) {
                // Server config is typically in the server root directory
                // We'll try a few common locations
                Path serverPath = Paths.get(".").toAbsolutePath();
                configPath = serverPath.resolve(CONFIG_FILE);
                if (Files.exists(configPath)) {
                    return configPath;
                }
            }
        } catch (Exception e) {
            // Server not available, continue with file system search
        }

        // Try common server directory locations
        String[] commonPaths = {
                ".server/" + CONFIG_FILE,
                "../.server/" + CONFIG_FILE,
                "server/" + CONFIG_FILE
        };

        for (String pathStr : commonPaths) {
            configPath = Paths.get(pathStr).normalize();
            if (Files.exists(configPath)) {
                return configPath;
            }
        }

        return null;
    }

    /**
     * Extract RCON configuration from JSON string.
     * Simple parser that looks for "Mods" -> "Rcon" section.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private static Map<String, Object> extractRconConfig(String json) {
        try {
            // Simple extraction: find "Mods" -> "com.rcon:Rcon" -> { ... }
            int modsIndex = json.indexOf("\"Mods\"");
            if (modsIndex == -1) {
                return null;
            }

            // Look for the plugin identifier "com.rcon:Rcon"
            int rconIndex = json.indexOf("\"com.rcon:Rcon\"", modsIndex);
            if (rconIndex == -1) {
                return null;
            }

            // Find the opening brace of Rcon object
            int objectStart = json.indexOf('{', rconIndex);
            if (objectStart == -1) {
                return null;
            }

            // Find matching closing brace
            int braceCount = 0;
            int objectEnd = objectStart;
            for (int i = objectStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{')
                    braceCount++;
                if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        objectEnd = i + 1;
                        break;
                    }
                }
            }

            String rconJson = json.substring(objectStart, objectEnd);
            return parseJsonObject(rconJson);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Simple JSON object parser for key-value pairs.
     * Handles: "key": "value" and "key": number
     */
    private static Map<String, Object> parseJsonObject(String json) {
        Map<String, Object> result = new java.util.HashMap<>();

        // Pattern to match: "key": value (where value can be string or number)
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\"([^\"]+)\"\\s*:\\s*([^,}\\]]+)");
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

    @SuppressWarnings("unchecked")
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

    @SuppressWarnings("unchecked")
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
