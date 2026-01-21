package com.madscientiste.rcon;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.madscientiste.rcon.logging.RconLogger;
import javax.annotation.Nonnull;

public class MinimalSetup extends JavaPlugin {

  @Nonnull private final RconLogger logger;

  public MinimalSetup(@Nonnull JavaPluginInit init) {
    super(init);
    this.logger = RconLogger.createPluginLogger(null);
  }

  @Nonnull
  public RconLogger getRconLogger() {
    return this.logger;
  }

  @Override
  protected void setup() {
    getRconLogger().atInfo().log("RCON plugin setup");
  }

  @Override
  protected void start() {
    getRconLogger().atInfo().log("RCON plugin started");
  }

  @Override
  protected void shutdown() {
    getRconLogger().atInfo().log("RCON plugin shutdown");
  }
}
