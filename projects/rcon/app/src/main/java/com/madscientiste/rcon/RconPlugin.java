package com.madscientiste.rcon;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.madscientiste.rcon.infrastructure.AuthenticationService;
import com.madscientiste.rcon.infrastructure.ConfigException;
import com.madscientiste.rcon.infrastructure.ConfigLoader;
import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.infrastructure.RconConstants;
import com.madscientiste.rcon.logging.LogEvent;
import com.madscientiste.rcon.logging.RconLogger;
import java.nio.file.Path;
import javax.annotation.Nonnull;

public class RconPlugin extends JavaPlugin {

  private static RconPlugin instance;
  private RconServer rconServer;
  private final String pluginName;

  @Nonnull private final RconLogger logger;

  public RconPlugin(@Nonnull JavaPluginInit init) {
    super(init);
    this.pluginName = init.getPluginManifest().getName();
    this.logger = RconLogger.createPluginLogger(null);
  }

  @Nonnull
  public RconLogger getRconLogger() {
    return this.logger;
  }

  public static RconPlugin get() {
    return instance;
  }

  @Override
  protected void setup() {
    instance = this;

    getRconLogger().atInfo().log("RCON plugin setting up...");

    Path configPath = Path.of(RconConstants.CONFIG_DIR, RconConstants.CONFIG_FILENAME);
    ConfigLoader loader = new ConfigLoader(configPath);
    RconConfig config;
    try {
      config = loader.load();
    } catch (ConfigException e) {
      // A config that exists but cannot be used is never replaced with defaults and a new
      // password: RCON stays off until the file is fixed.
      getRconLogger()
          .atSevere()
          .log(
              "RCON is DISABLED: the config file cannot be used (%s). The file was left"
                  + " untouched. Fix or delete it, then restart the server.",
              e.getMessage());
      return;
    }

    try {
      if (loader.restrictPermissions()) {
        getRconLogger()
            .atInfo()
            .log("%s was readable by other users; it is now owner-only (mode 600)", configPath);
      }
    } catch (Exception e) {
      getRconLogger()
          .atWarning()
          .log(
              "%s holds the RCON password hash and is readable by other users, and its"
                  + " permissions could not be changed: %s",
              configPath, e.toString());
    }

    if (!config.requiresPassword()) {
      String generatedPassword = AuthenticationService.generateSecurePassword();
      String hashedPassword = AuthenticationService.hashPassword(generatedPassword);

      RconConfig updated = config.withPasswordHash(hashedPassword);
      if (!loader.save(updated)) {
        getRconLogger()
            .atWarning()
            .log(
                "Could not write %s: the password below is only valid until the server restarts",
                configPath);
      }

      getRconLogger()
          .atWarning()
          .log("================================================================================");
      getRconLogger().atWarning().log("RCON password auto-generated: " + generatedPassword);
      getRconLogger().atWarning().log("Please save this password! It will not be shown again.");
      getRconLogger()
          .atWarning()
          .log("================================================================================");

      config = updated;
    }

    rconServer = new RconServer(config);
    getRconLogger().atInfo().log("RCON plugin setup complete!");
  }

  @Override
  protected void start() {
    if (rconServer == null) {
      // setup() refused the config and already said why
      return;
    }

    try {
      rconServer.start();
      RconConfig config = rconServer.getConfig();
      getRconLogger()
          .atInfo()
          .log("RCON server started on %s:%d", config.getHost(), config.getPort());
    } catch (Exception e) {
      getRconLogger()
          .event(LogEvent.APPLICATION_ERROR)
          .withParam("error_code", "start_failure")
          .withParam("message", "Failed to start RCON server")
          .withCause(e)
          .log();
    }
  }

  @Override
  protected void shutdown() {
    if (rconServer != null && rconServer.isRunning()) {
      try {
        rconServer.stop();
        getRconLogger().atInfo().log("RCON server stopped");
      } catch (Exception e) {
        getRconLogger()
            .event(LogEvent.APPLICATION_ERROR)
            .withParam("error_code", "stop_failure")
            .withParam("message", "Error stopping RCON server: " + e.getMessage())
            .withCause(e)
            .log();
      }
    }

    instance = null;

    getRconLogger().atInfo().log("RCON plugin shutdown complete");
  }

  public RconServer getRconServer() {
    return rconServer;
  }
}
