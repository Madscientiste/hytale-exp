package com.madscientiste.rcon;

import com.madscientiste.rcon.infrastructure.RconConfig;

/**
 * Simple manual test to validate the RCON server works. Run this class manually to test the server.
 */
public class RconServerManualTest {

  public static void main(String[] args) {
    try {
      // Create server with default config
      RconConfig config = RconConfig.builder().build();
      RconServer server = new RconServer(config);

      // Add shutdown hook
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    System.out.println("Shutting down server...");
                    server.stop();
                  }));

      // Start server
      server.start();

      System.out.println("RCON server started on " + config.getHost() + ":" + config.getPort());
      System.out.println("Architecture layers:");
      System.out.println("  Transport: TCP server with connection management");
      System.out.println("  Protocol: RCON packet parsing and state machine");
      System.out.println("  Application: Coordinated between layers");
      System.out.println("  Command: Simple echo implementation");
      System.out.println();
      System.out.println("Testing with RCON client should show:");
      System.out.println("  1. Authentication succeeds (MVP: no password)");
      System.out.println("  2. Commands echo back input");
      System.out.println("  3. Multiple packets work correctly");
      System.out.println("  4. Server stays responsive");
      System.out.println();
      System.out.println("Press Ctrl+C to stop...");

      // Keep running
      while (server.isRunning()) {
        try {
          Thread.sleep(1000);

          // Print stats every 30 seconds
          var stats = server.getStats();
          System.out.println("\rActive connections: " + stats.connectionCount);

        } catch (InterruptedException e) {
          break;
        }
      }

    } catch (Exception e) {
      System.err.println("Server error: " + e.getMessage());
      e.printStackTrace();
    }
  }
}
