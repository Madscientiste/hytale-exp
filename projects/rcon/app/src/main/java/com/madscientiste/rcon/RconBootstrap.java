package com.madscientiste.rcon;

import com.madscientiste.rcon.infrastructure.AuthenticationService;
import com.madscientiste.rcon.infrastructure.ConfigException;
import com.madscientiste.rcon.infrastructure.ConfigLoader;
import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.logging.RconLogger;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Turns the config file into a server that is ready to start. This is everything the plugin does at
 * setup, kept apart from the Hytale plugin class so it can be run and tested on its own.
 */
public final class RconBootstrap {

  private RconBootstrap() {}

  /**
   * Load the config and build the server.
   *
   * <ul>
   *   <li>No config file: defaults, and a password is generated and saved.
   *   <li>A config file without a password hash: a password is generated and saved, unless the file
   *       opts in to the no-password mode with {@code allowNoPassword}.
   *   <li>A config file that cannot be used (unreadable, bad JSON, unknown key, bad value): RCON
   *       stays off. Nothing is generated and nothing is written.
   * </ul>
   *
   * @return the server, not started yet; or null when RCON must stay off (the reason is logged)
   */
  @Nullable
  public static RconServer prepare(@Nonnull Path configPath, @Nonnull RconLogger logger) {
    ConfigLoader loader = new ConfigLoader(configPath);
    RconConfig config;
    try {
      config = loader.load();
    } catch (ConfigException e) {
      // A config that exists but cannot be used is never replaced with defaults and a new
      // password: RCON stays off until the file is fixed.
      logger.atSevere().log(
          "RCON is DISABLED: the config file cannot be used (%s). The file was left"
              + " untouched. Fix or delete it, then restart the server.",
          e.getMessage());
      return null;
    }

    try {
      if (loader.restrictPermissions()) {
        logger.atInfo().log(
            "%s was readable by other users; it is now owner-only (mode 600)", configPath);
      }
    } catch (Exception e) {
      logger.atWarning().log(
          "%s holds the RCON password hash and is readable by other users, and its"
              + " permissions could not be changed: %s",
          configPath, e.toString());
    }

    if (!config.requiresPassword() && !config.isAllowNoPassword()) {
      String generatedPassword = AuthenticationService.generateSecurePassword();
      String hashedPassword = AuthenticationService.hashPassword(generatedPassword);

      RconConfig updated = config.withPasswordHash(hashedPassword);
      if (!loader.save(updated)) {
        logger.atWarning().log(
            "Could not write %s: the password below is only valid until the server restarts",
            configPath);
      }

      logger.atWarning().log(
          "================================================================================");
      logger.atWarning().log("RCON password auto-generated: " + generatedPassword);
      logger.atWarning().log("Please save this password! It will not be shown again.");
      logger.atWarning().log(
          "================================================================================");

      config = updated;
    }

    return new RconServer(config);
  }
}
