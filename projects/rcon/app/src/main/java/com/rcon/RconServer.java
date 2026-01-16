package com.rcon;

import com.rcon.application.RconApplication;
import com.rcon.command.CommandDispatcher;
import com.rcon.infrastructure.RconConfig;
import com.rcon.infrastructure.RconLogger;
import com.rcon.protocol.RconProtocol;
import com.rcon.transport.RconTransport;

/**
 * Main RCON server - ties together all layers.
 * This is the entry point and lifecycle manager.
 */
public class RconServer {

    private final RconConfig config;
    private final RconLogger logger;

    // Layer components
    private RconTransport transport;
    private RconProtocol protocol;
    private CommandDispatcher commandDispatcher;
    private RconApplication application;

    private volatile boolean started = false;

    public RconServer(RconConfig config) {
        this.config = config;
        this.logger = new RconLogger();
    }

    /**
     * Start the RCON server.
     */
    public void start() throws Exception {
        if (started) {
            throw new IllegalStateException("Server already started");
        }

        try {
            // Initialize protocol and command layers first
            protocol = new RconProtocol(logger);
            commandDispatcher = new CommandDispatcher();

            // Create transport with callbacks setter
            transport = new RconTransport(config, logger, null);

            // Create application layer (this sets callbacks)
            application = new RconApplication(transport, protocol, commandDispatcher, logger, config);

            // Now set the callbacks in transport after application is created
            transport.setCallbacks(application);

            // Start server
            application.start();
            started = true;

            logger.logEvent("SERVER_STARTED", "server", java.util.Map.of(
                    "host", config.getHost(),
                    "port", config.getPort(),
                    "maxConnections", config.getMaxConnections()));

        } catch (Exception e) {
            logger.logError("Failed to start server", e);
            stop(); // Cleanup any partial initialization
            throw e;
        }
    }

    /**
     * Stop the RCON server.
     */
    public void stop() {
        if (started) {
            try {
                if (application != null) {
                    application.stop();
                }

                logger.logEvent("SERVER_STOPPED", "server", java.util.Map.of());

            } catch (Exception e) {
                logger.logError("Error stopping server", e);
            } finally {
                started = false;
            }
        }
    }

    /**
     * Check if server is running.
     */
    public boolean isRunning() {
        return started;
    }

    /**
     * Get server configuration.
     */
    public RconConfig getConfig() {
        return config;
    }

    /**
     * Get application statistics.
     */
    public RconApplication.ApplicationStats getStats() {
        return application != null ? application.getStats() : new RconApplication.ApplicationStats(0, 0);
    }

    /**
     * Main method for standalone testing.
     */
    public static void main(String[] args) {
        RconConfig config = new RconConfig();
        RconServer server = new RconServer(config);

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        try {
            server.start();
            System.out.println("RCON server started on " + config.getHost() + ":" + config.getPort());

            // Keep running until interrupted
            while (server.isRunning()) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            server.stop();
        }
    }
}