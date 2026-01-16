package com.madscientiste.rcon;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.madscientiste.rcon.infrastructure.RconConfig;
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

    // Initialize configuration from server config.json
    RconConfig config = com.madscientiste.rcon.infrastructure.RconConfigLoader.load();
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
