package com.madscientiste.rcon;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.madscientiste.rcon.infrastructure.AuthenticationService;
import com.madscientiste.rcon.infrastructure.ConfigLoader;
import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.infrastructure.RconConstants;
import java.nio.file.Path;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Hytale plugin wrapper for RCON server. Integrates RconServer into the Hytale plugin lifecycle.
 * Supports hot reloading through proper instance management and cleanup.
 */
public class RconPlugin extends JavaPlugin {

  private static RconPlugin instance;
  private RconServer rconServer;

  /**
   * Get the current plugin instance. Safe for hot reload: instance is refreshed on reload.
   *
   * @return The current plugin instance, or null if not initialized
   */
  public static RconPlugin get() {
    return instance;
  }

  public RconPlugin(@Nonnull JavaPluginInit init) {
    super(init);
  }

  @Override
  protected void setup() {
    instance = this;
    getLogger().at(Level.INFO).log("RCON plugin setting up...");

    // Initialize configuration from dedicated config file
    Path configPath = Path.of(RconConstants.CONFIG_DIR, RconConstants.CONFIG_FILENAME);
    ConfigLoader loader = new ConfigLoader(configPath);
    RconConfig config = loader.load();

    // Check if password needs generation
    if (!config.requiresPassword()) {
      // Auto-generate password
      String generatedPassword = AuthenticationService.generateSecurePassword();
      String hashedPassword = AuthenticationService.hashPassword(generatedPassword);

      // Create new config with password hash
      RconConfig updated = config.withPasswordHash(hashedPassword);
      loader.save(updated);

      // Log generated password prominently
      getLogger()
          .at(Level.WARNING)
          .log("================================================================================");
      getLogger().at(Level.WARNING).log("RCON password auto-generated: " + generatedPassword);
      getLogger().at(Level.WARNING).log("Please save this password! It will not be shown again.");
      getLogger()
          .at(Level.WARNING)
          .log("================================================================================");

      config = updated;
    }

    rconServer = new RconServer(config);
    getLogger().at(Level.INFO).log("RCON plugin setup complete!");
  }

  @Override
  protected void start() {
    try {
      rconServer.start();
      RconConfig config = rconServer.getConfig();
      getLogger()
          .at(Level.INFO)
          .log(String.format("RCON server started on %s:%d", config.getHost(), config.getPort()));
    } catch (Exception e) {
      getLogger().at(Level.SEVERE).log("Failed to start RCON server: " + e.getMessage());
      e.printStackTrace();
    }
  }

  @Override
  protected void shutdown() {
    if (rconServer != null && rconServer.isRunning()) {
      try {
        rconServer.stop();
        getLogger().at(Level.INFO).log("RCON server stopped");
      } catch (Exception e) {
        getLogger().at(Level.WARNING).log("Error stopping RCON server: " + e.getMessage());
      }
    }

    // Clear instance to prevent stale references after reload
    instance = null;

    getLogger().at(Level.INFO).log("RCON plugin shutdown complete");
  }

  /**
   * Get the RCON server instance. Useful for accessing server stats or configuration.
   *
   * @return The RCON server instance, or null if not initialized
   */
  public RconServer getRconServer() {
    return rconServer;
  }
}
