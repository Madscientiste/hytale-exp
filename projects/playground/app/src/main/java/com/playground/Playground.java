package com.playground;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.logging.Level;

/**
 * Example Hytale plugin demonstrating basic plugin structure.
 */
public class Playground extends JavaPlugin {

    public Playground(JavaPluginInit init) {
        super(init);
    }

    @Override
    public void setup() {
        // Called during server setup phase
        // Register configs, prepare resources here
        getLogger().at(Level.INFO).log("Playground is setting up!");
    }

    @Override
    public void start() {
        // Called when the plugin starts
        // Register commands, events, entities here
        getLogger().at(Level.INFO).log("Playground has started!");
    }

    @Override
    public void shutdown() {
        // Called when the plugin is stopping
        // Clean up resources here
        getLogger().at(Level.INFO).log("Playground is shutting down!");
    }
}
