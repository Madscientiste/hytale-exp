# Hytale Mod Experiments

This project is currently a **Work in Progress**. I'm in the process of exploring and figuring things out, and along the way I’m building the tools I need.

> [!NOTE]
> If you encounter any issues or have questions, please open an issue or discussion. I typically respond quickly.

## RCON Plugin

The RCON plugin gives remote console access to your Hytale server over the standard RCON (Remote Console) protocol. Any RCON client can then run server commands, which makes administration and scripting easier.

**Works with:** Hytale server 0.7.7 and the January 2026 builds.

### Features

- **Every server command** over RCON, with the same rights as the server console
- **Password login**, with a random password made for you on first start and stored as a PBKDF2 hash
- **Protection against attacks**: bans after repeated wrong passwords, limits per address, login deadlines, limits on log spam
- **One small config file**, checked at start: a broken file turns RCON off instead of being silently replaced
- **Long replies** split over several packets, as the protocol expects

### Setup

1. **Install**: download `rcon-x.x.x.jar` from [Releases](https://github.com/Madscientiste/hytale-exp/releases), put it in your server's `mods/` folder and remove any older `rcon-*.jar`.

2. **Start the server once.** The plugin then:
   - creates `configs/com.madscientiste.rcon.json` (readable by its owner only)
   - makes a random password and prints it in the server log

   **Save that password now.** Only its hash is stored, so it cannot be shown again.

3. **Connect** with any RCON client, for example [`rcon-cli`](https://github.com/itzg/rcon-cli) or `mcrcon`, on this machine:

   ```bash
   rcon-cli --host 127.0.0.1 --port 25575 --password "..." who
   ```

   Commands are typed without the leading `/`. Player-only commands such as `hub` do not work over RCON, just as on the server console.

### Configuration

Settings live in `configs/com.madscientiste.rcon.json`, relative to the server folder. Every key is optional; a missing key takes its default. Restart the server after a change.

```json
{
  "host": "127.0.0.1",
  "port": 25575,
  "passwordHash": "pbkdf2-sha512:210000:..."
}
```

The most used keys:

| Key | Default | Meaning |
| --- | --- | --- |
| `host` | `127.0.0.1` | Address to listen on. Keep it on loopback. |
| `port` | `25575` | Port to listen on. |
| `passwordHash` | made on first start | Hash of the password (see below). |
| `maxConnections` | `10` | Connections in total. |
| `maxConnectionsPerIp` | `4` | Connections from one address. |
| `authMaxFailures` | `5` | Wrong passwords from one address that trigger a ban. |
| `authBanMs` | `300000` | Length of that ban (5 minutes). |

All 14 keys, their limits and the no-password mode for local installs are described in the [RCON security and configuration guide](projects/rcon/README.md#config-file).

An unknown key, a wrong type or bad JSON turns RCON off and leaves the file untouched. The server log names the key that is wrong.

### Setting your own password

Make a hash with the plugin jar, then put it in `passwordHash`:

```bash
java -cp mods/rcon-x.x.x.jar com.madscientiste.rcon.infrastructure.AuthenticationService 'your password'
```

From a checkout of this repository (with Java 25 and the server jar in `libs/`), `make password PASSWORD='your password'` does the same.

Hashes from plugin 1.1.x (`base64salt:base64hash`) still work, but the plugin warns at every start. Make a new hash to upgrade. Both formats are explained in the [guide](projects/rcon/README.md#password-hash).

### Running in Docker

The [hytale-docker](https://github.com/Madscientiste/hytale-docker) image installs this plugin and sets it up for you: `docker exec <container> rcon-cli who`.

> [!WARNING]
> **RCON gives full console access to your server and has no encryption.** The password and every command cross the network in clear text.
>
> - Keep `host` on `127.0.0.1`. The plugin warns at start when it listens on any other address.
> - To reach RCON from another machine, use an SSH tunnel or a VPN such as WireGuard. **Never open the RCON port to the internet.**
> - Keep `configs/com.madscientiste.rcon.json` private: it holds the password hash.
>
> What the plugin protects against, and what it does not, is listed in the [security guide](projects/rcon/README.md).
