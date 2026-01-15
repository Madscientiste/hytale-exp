package com.playground;

import java.util.logging.Level;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

/**
 * Example Hytale plugin demonstrating basic plugin structure.
 * Supports hot reload through proper instance management and cleanup.
 */
public class Playground extends JavaPlugin {

    private static Playground instance;

    /**
     * Get the current plugin instance.
     * Safe for hot reload: instance is refreshed on reload.
     *
     * @return The current plugin instance, or null if not initialized
     */
    public static Playground get() {
        return instance;
    }

    public Playground(JavaPluginInit init) {
        super(init);
    }

    @Override
    public void setup() {
        // Set instance for hot reload support
        // Safe: instance is refreshed on reload
        instance = this;

        // Called during server setup phase
        // Register configs, prepare resources here
        getLogger().at(Level.INFO).log("Playground is setting up!");
    }

    @Override
    public void start() {
        // Called when the plugin starts
        // Register commands, events, entities here
        // Note: Use registries for automatic cleanup on reload
        getLogger().at(Level.INFO).log("Playground has started!");
    }

    @Override
    public void shutdown() {
        // Called when the plugin is stopping or being reloaded
        // Clean up resources here:
        // - Unregister listeners
        // - Stop tasks/schedulers
        // - Close connections
        // - Clear static state (if any)

        // Clear instance to prevent stale references after reload
        instance = null;

        getLogger().at(Level.INFO).log("Playground is shutting down!");
    }
}
