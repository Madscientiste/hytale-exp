package com.madscientiste.rcon;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
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

    // Null means RCON stays off (a config that cannot be used); the reason is already logged
    rconServer = RconBootstrap.prepare(configPath, getRconLogger());
    if (rconServer != null) {
      getRconLogger().atInfo().log("RCON plugin setup complete!");
    }
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
