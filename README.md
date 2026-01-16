# Hytale Mod Experiments

This project is currently a **Work in Progress**. I'm in the process of exploring and figuring things out, and along the way I’m building the tools I need.

> [!NOTE]
> If you encounter any issues or have questions, please open an issue or discussion. I typically respond quickly.

## RCON Plugin

The RCON plugin provides remote console access to your Hytale server using the standard RCON (Remote Console) protocol. This allows you to execute server commands remotely, making server administration easier and enabling integration with external tools and scripts.

### Features

- **Remote Console Access**: Execute Hytale server commands remotely via RCON protocol
- **Password Authentication**: Secure password-based authentication using SHA-256 hashing with salt
- **Hytale Command Integration**: All Hytale server commands are available through RCON
- **Configurable**: Easy setup through server configuration file
- **Connection Management**: Configurable connection limits and timeouts
- **Protocol Compliance**: Full RCON protocol implementation with proper framing and error handling
- **Security Hardened**: Comprehensive security fixes including integer overflow protection, TCP fragmentation handling, and state isolation

### Setup

1. **Download and add the plugin**: Download the latest release from the [Releases](https://github.com/Madscientiste/hytale-exp/releases) tab. Place the `rcon-x.x.x.jar` file in your server's `mods/` directory.

2. **Configure in `config.json`**: Add the RCON configuration to your server's `config.json` file in the `Mods` section:

```json
{
  ...other server stuff
  "Mods": {
    "com.madscientiste.rcon:Rcon": {
      "host": "127.0.0.1",
      "port": 25575,
      "maxConnections": 10,
      "maxFrameSize": 4096,
      "readTimeoutMs": 30000,
      "connectionTimeoutMs": 5000,
      "passwordHash": "base64salt:base64hash"
    }
  }
}
```

3. **Generate Password Hash**: Generate a password hash using one of the following methods:

**Using Make (Recommended for developers)**
```bash
make password PASSWORD=your_password_here
```

**Using the plugin JAR directly**
```bash
java -cp .server/mods/Rcon-1.0.0.jar com.madscientiste.rcon.infrastructure.AuthenticationService your_password_here
```

**Example:**
```bash
$ make password PASSWORD=MySecurePassword123
Generating password hash...
Password hash: dGhpc2lzYXNsdA==:YW5kdGhpc2lzdGhlcGFzc3dvcmRoYXNo
Add this to your config.json:
  "passwordHash": "dGhpc2lzYXNsdA==:YW5kdGhpc2lzdGhlcGFzc3dvcmRoYXNo"
Password hash generated
```

The utility will output a hash in the format `base64salt:base64hash`. Copy this value to the `passwordHash` field in your config.

**Note**: If `passwordHash` is omitted or set to `null`, the server will run in insecure mode (no authentication required). This is only recommended for local development/testing.

> [!NOTE]
> For none devs, i'll figure out a better way to setup the password hash. Soon.
>

4. **Configuration Options**:
   - `host`: The IP address to bind the RCON server to (default: `127.0.0.1`)
   - `port`: The port to listen on (default: `25575`)
   - `maxConnections`: Maximum number of concurrent RCON connections (default: `10`)
   - `maxFrameSize`: Maximum frame size in bytes (default: `4096`)
   - `readTimeoutMs`: Read timeout in milliseconds (default: `30000`)
   - `connectionTimeoutMs`: Connection timeout in milliseconds (default: `5000`)
   - `passwordHash`: Password hash in format `base64salt:base64hash` (optional, but **highly recommended**)

5. **Start your server**: The RCON server will automatically start when the plugin loads. You should see a log message indicating the RCON server has started on the configured host and port.

### Usage

Once configured, you can connect to the RCON server using any RCON-compatible client. The plugin will forward commands to the Hytale server, allowing you to execute any server command remotely.

**Example RCON clients:**
- `mcrcon` (command-line tool)
- `rcon-cli` (Node.js)
- Custom scripts using RCON libraries

> [!WARNING]
> **Security Considerations**
>
> RCON provides console-level access to your server. Follow these guidelines:
>
> **Authentication:**
> - Always configure `passwordHash` for production use. Without it, anyone can connect and execute commands.
> - Use strong passwords and keep your `config.json` file secure (it contains the password hash).
> - The plugin uses SHA-256 with salt. 
>
> **Network Exposure:**
> - Keep `host` set to `127.0.0.1` (localhost) when possible for local-only access.
> - If exposing RCON over the network, use firewall IP whitelisting to restrict access to trusted IP addresses.
> - Do not expose the RCON port to the public internet unless absolutely necessary.

> [!NOTE]
> **Security Implementation**
>
> This plugin includes several security measures:
> - Password-based authentication with SHA-256 hashing and salt
> - Integer overflow protection in packet parsing
> - TCP fragmentation handling to prevent protocol errors
> - Per-connection state isolation to prevent cross-connection attacks
> - Re-authentication rejection for protocol compliance
> - Comprehensive protocol validation and error handling
> - Extensive security test coverage (36+ tests)
>
> The implementation follows security best practices for internal tools. If you need additional security features or have specific requirements, please open an issue.
>