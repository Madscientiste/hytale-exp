package com.madscientiste.rcon;

import com.madscientiste.rcon.infrastructure.RconConfig;
import com.madscientiste.rcon.infrastructure.RconConstants;
import com.madscientiste.rcon.logging.LogEvent;
import com.madscientiste.rcon.logging.RconLogger;

public class RconServerManualTest {

  public static void main(String[] args) {
    RconConfig config = RconConfig.builder().build();
    RconLogger logger = RconLogger.createPluginLogger(RconConstants.LOGGER_APPLICATION);
    RconServer server = new RconServer(config);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  logger.atInfo().log("Shutting down server...");
                  server.stop();
                }));

    try {
      server.start();

      logger.atInfo().log("RCON server started on %s:%d", config.getHost(), config.getPort());
      logger.atInfo().log("Architecture layers:");
      logger.atInfo().log("  Transport: TCP server with connection management");
      logger.atInfo().log("  Protocol: RCON packet parsing and state machine");
      logger.atInfo().log("  Application: Coordinated between layers");
      logger.atInfo().log("  Command: Simple echo implementation");
      logger.atInfo().log("");
      logger.atInfo().log("Testing with RCON client should show:");
      logger.atInfo().log("  1. Authentication succeeds (MVP: no password)");
      logger.atInfo().log("  2. Commands echo back input");
      logger.atInfo().log("  3. Multiple packets work correctly");
      logger.atInfo().log("  4. Server stays responsive");
      logger.atInfo().log("");
      logger.atInfo().log("Press Ctrl+C to stop...");

      while (server.isRunning()) {
        try {
          Thread.sleep(1000);
          var stats = server.getStats();
          logger.atInfo().log("\rActive connections: %d", stats.connectionCount);
        } catch (InterruptedException e) {
          break;
        }
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
