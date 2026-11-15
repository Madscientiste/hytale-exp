package com.madscientiste.rcon;

import com.madscientiste.rcon.application.RconApplication;
import com.madscientiste.rcon.command.CommandDispatcher;
import com.madscientiste.rcon.infrastructure.AuthFailureTracker;
import com.madscientiste.rcon.infrastructure.AuthenticationService;
import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.infrastructure.RconConstants;
import com.madscientiste.rcon.logging.LogEvent;
import com.madscientiste.rcon.logging.LogThrottle;
import com.madscientiste.rcon.logging.RconLogger;
import com.madscientiste.rcon.protocol.RconProtocol;
import com.madscientiste.rcon.transport.RconTransport;

public class RconServer {

  /** A peer that has not logged in may cause this many log lines per window, per address. */
  private static final int PRE_AUTH_LOG_LINES = 20;

  private static final long PRE_AUTH_LOG_WINDOW_MS = 10_000;
  private static final int PRE_AUTH_LOG_MAX_ADDRESSES = 4096;

  private final RconConfig config;

  private RconTransport transport;
  private RconProtocol protocol;
  private CommandDispatcher commandDispatcher;
  private RconApplication application;

  private final RconLogger logger = RconLogger.createPluginLogger(RconConstants.LOGGER_APPLICATION);

  private volatile boolean started = false;

  public RconServer(RconConfig config) {
    this.config = config;
  }

  public void start() throws Exception {
    if (started) {
      throw new IllegalStateException("Server already started");
    }

    // Fail closed: with no password hash, nobody gets a console unless the owner said so
    if (!config.requiresPassword() && !config.isAllowNoPassword()) {
      logger.atSevere().log(
          "RCON refuses to start: no passwordHash is set. Set one, or set \"allowNoPassword\""
              + " to true to accept logins without a password from the addresses in"
              + " \"noPasswordAllowFrom\".");
      throw new IllegalStateException(
          "RCON has no passwordHash and allowNoPassword is not set: refusing to start");
    }

    try {
      if (config.isNoPasswordMode()) {
        logger.atWarning().log(
            "RCON RUNS WITHOUT A PASSWORD (allowNoPassword is true and no passwordHash is set)."
                + " Anyone connecting from %s gets the full server console. Set a passwordHash"
                + " to turn this off.",
            config.getNoPasswordAllowFrom());
      } else if (config.isAllowNoPassword()) {
        logger.atInfo().log(
            "allowNoPassword is true but a passwordHash is set: the password is required.");
      }

      if (AuthenticationService.isLegacyHash(config.getPasswordHash())) {
        logger.atWarning().log(
            "The RCON passwordHash uses the old format (one round of SHA-256). It still works,"
                + " but is fast to crack if the config file leaks. Generate a new hash to"
                + " upgrade it.");
      } else if (AuthenticationService.hasWeakIterations(config.getPasswordHash())) {
        logger.atWarning().log(
            "The RCON passwordHash was made with fewer than %d PBKDF2 iterations. It works, but"
                + " is weaker than it should be. Generate a new hash to upgrade it.",
            AuthenticationService.PBKDF2_ITERATIONS);
      }

      AuthFailureTracker authFailures =
          new AuthFailureTracker(
              config.getAuthMaxFailures(), config.getAuthFailureWindowMs(), config.getAuthBanMs());
      LogThrottle preAuthLogs =
          new LogThrottle(PRE_AUTH_LOG_LINES, PRE_AUTH_LOG_WINDOW_MS, PRE_AUTH_LOG_MAX_ADDRESSES);

      protocol = new RconProtocol(config.getMaxFrameSize());
      commandDispatcher = new CommandDispatcher();
      transport = new RconTransport(config, null, authFailures, preAuthLogs);
      application =
          new RconApplication(
              transport, protocol, commandDispatcher, config, authFailures, preAuthLogs);

      transport.setCallbacks(application);

      application.start();
      started = true;

    } catch (Exception e) {
      logger
          .event(LogEvent.APPLICATION_ERROR)
          .withParam("error_code", "start_failure")
          .withParam("message", "Failed to start server")
          .withCause(e)
          .log();
      stop();
      throw e;
    }
  }

  public void stop() {
    if (started) {
      try {
        if (application != null) {
          application.stop();
        }
      } catch (Exception e) {
        logger
            .event(LogEvent.APPLICATION_ERROR)
            .withParam("error_code", "stop_failure")
            .withParam("message", "Error stopping server")
            .withCause(e)
            .log();
      } finally {
        started = false;
      }
    }
  }

  public boolean isRunning() {
    return started;
  }

  public RconConfig getConfig() {
    return config;
  }

  public RconApplication.ApplicationStats getStats() {
    return application != null
        ? application.getStats()
        : new RconApplication.ApplicationStats(0, 0);
  }

  public static void main(String[] args) {
    // Standalone run for development: no password, this machine only
    RconConfig config = RconConfig.builder().allowNoPassword(true).build();
    RconServer server = new RconServer(config);

    RconLogger logger = RconLogger.createPluginLogger(RconConstants.LOGGER_APPLICATION);

    try {
      server.start();
      logger.atInfo().log("RCON server started on %s:%d", config.getHost(), config.getPort());

      while (server.isRunning()) {
        Thread.sleep(1000);
      }

    } catch (Exception e) {
      logger
          .event(LogEvent.APPLICATION_ERROR)
          .withParam("error_code", "server_error")
          .withParam("message", "Server error")
          .withCause(e)
          .log();
    } finally {
      server.stop();
    }
  }
}
