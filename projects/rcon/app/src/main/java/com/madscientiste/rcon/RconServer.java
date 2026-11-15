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

    try {
      if (AuthenticationService.isLegacyHash(config.getPasswordHash())) {
        logger.atWarning().log(
            "The RCON passwordHash uses the old format (one round of SHA-256). It still works,"
                + " but is fast to crack if the config file leaks. Generate a new hash to"
                + " upgrade it.");
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
    RconConfig config = RconConfig.builder().build();
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
