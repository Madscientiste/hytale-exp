# Hytale Mod Experiments

This project is currently a **Work in Progress**. I'm in the process of exploring and figuring things out, and along the way I’m building the tools I need.

> [!NOTE]
> This document is also a work in progress. If you have any questions, feel free to open a discussion or issue, I usually respond quickly.
>
> I plan to make this project publicly available in the next few days to make it easier to access.

> [!NOTE]
> If any issue occurs, please open an issue, I will try to fix it as soon as possible.

## RCON Plugin

The RCON plugin provides remote console access to your Hytale server using the standard RCON (Remote Console) protocol. This allows you to execute server commands remotely, making server administration easier and enabling integration with external tools and scripts.

### Features

- **Remote Console Access**: Execute Hytale server commands remotely via RCON protocol
- **Hytale Command Integration**: All Hytale server commands are available through RCON
- **Configurable**: Easy setup through server configuration file
- **Connection Management**: Configurable connection limits and timeouts
- **Hot Reload Support**: Properly handles plugin reloading without connection issues, a nice to have i guess.

### Setup

1. **Download and add the plugin**: The plugin is available at `/.server/mods/Rcon-1.0.0.jar`. Place the RCON plugin JAR in your server's plugins directory.

2. **Configure in `config.json`**: Add the RCON configuration to your server's `config.json` file in the `Mods` section:

```json
{
  ...other server stuff
  "Mods": {
    "com.rcon:Rcon": {
      "host": "127.0.0.1",
      "port": 25575,
      "maxConnections": 10,
      "maxFrameSize": 4096,
      "readTimeoutMs": 30000,
      "connectionTimeoutMs": 5000
    }
  }
}
```

3. **Configuration Options**:
   - `host`: The IP address to bind the RCON server to (default: `127.0.0.1`)
   - `port`: The port to listen on (default: `25575`)
   - `maxConnections`: Maximum number of concurrent RCON connections (default: `10`)
   - `maxFrameSize`: Maximum frame size in bytes (default: `4096`)
   - `readTimeoutMs`: Read timeout in milliseconds (default: `30000`)
   - `connectionTimeoutMs`: Connection timeout in milliseconds (default: `5000`)

4. **Start your server**: The RCON server will automatically start when the plugin loads. You should see a log message indicating the RCON server has started on the configured host and port.

### Usage

Once configured, you can connect to the RCON server using any RCON-compatible client. The plugin will forward commands to the Hytale server, allowing you to execute any server command remotely.

**Example RCON clients:**
- `mcrcon` (command-line tool)
- `rcon-cli` (Node.js)
- Custom scripts using RCON libraries

> [!WARNING]
> **SECURITY WARNING**: This plugin currently has **NO authentication**. It is **VERY important** that you do **NOT** expose this plugin to a public-facing IP address. Anyone who can connect to the RCON port will have full access to execute commands on your server.
>
> **Security Recommendations:**
> - Keep the `host` set to `127.0.0.1` (localhost) for local-only access
> - If you need to expose RCON over the network, **IP whitelisting via firewall is highly recommended** - configure your firewall to only allow connections from trusted IP addresses
> - Authentication support will be added in a future release (this is the first release)

