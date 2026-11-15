# RCON plugin: security and configuration

Why this file exists: RCON gives full console access to the server. This page says what the
plugin protects against, what it does not, and what every config key does. Setup steps are in the
[repository README](../../README.md).

## The short version

- Keep `host` on `127.0.0.1`. That is the default.
- RCON has **no encryption**. The password and every command cross the network in clear text.
  To use RCON from another machine, go through an SSH tunnel or a VPN such as WireGuard.
  Never open the RCON port to the internet.
- Use a long random password. The one the plugin generates is 24 random characters.

SSH tunnel example, run on your own machine:

```bash
ssh -N -L 25575:127.0.0.1:25575 user@your-server
rcon-cli --host 127.0.0.1 --port 25575 --password "..." who
```

If `host` is not a loopback address, the plugin prints a warning at every start.

## What the plugin does to protect itself

| Threat | Protection |
| --- | --- |
| Password guessing | 5 wrong passwords from one address within 10 minutes ban that address for 5 minutes. Failed logins are logged with the address. |
| Holding all connection slots | A new connection has 5 seconds to log in, however slowly it sends bytes. One address may hold 4 connections at most. |
| Filling the disk with log lines | An address that has not logged in can cause 20 log lines per 10 seconds. The rest are counted and the count is logged. Bad input never logs a stack trace. |
| Forged log lines | Control characters in logged values (command text included) are written as visible escapes such as `\n` and `\u001b`. |
| Leaked config file | The config file is written with mode 600 (owner only). The password is stored as a PBKDF2 hash. |
| Broken config file | RCON stays off and the log says what is wrong. The file is never replaced. |

Every command that runs is logged in full with the address it came from. Arguments that contain
`password`, `secret` or `token` are replaced with `[REDACTED]`.

### Limits apply to 127.0.0.1 too

Everything on the same machine shares the address `127.0.0.1`. So a local program that sends 5
wrong passwords gets `127.0.0.1` banned for 5 minutes, and during that time your own local RCON
clients (a health check, for example) are refused as well. This is on purpose: the other choice is
unlimited guessing from the machine itself.

IPv6 addresses are counted per /64 network, because one machine usually owns a whole /64.

### What is not protected

- The generated password is printed once in the server log on first start. Read it, then protect
  or delete that log file. To avoid this, set your own `passwordHash` before the first start.
- Anyone who can read the network between client and server can read the password. See the short
  version above.
- An attacker using many different addresses can still fill the connection slots while the
  attack lasts. That is one more reason to keep the port closed to the internet.

## Config file

`configs/com.madscientiste.rcon.json`, relative to the server folder. Missing keys take the
default. Unknown keys, wrong types and out-of-range values are errors: RCON then stays off until
the file is fixed, and the server log says which key is wrong.

| Key | Default | Meaning |
| --- | --- | --- |
| `host` | `127.0.0.1` | Address to listen on. |
| `port` | `25575` | Port to listen on. |
| `maxConnections` | `10` | Connections in total. |
| `maxConnectionsPerIp` | `4` | Connections from one address. |
| `maxFrameSize` | `4096` | Largest packet a client may send, in bytes (64 to 1048576). |
| `readTimeoutMs` | `30000` | A logged-in connection is closed after this long without a complete packet. |
| `connectionTimeoutMs` | `5000` | How often the listener wakes up to check for shutdown. |
| `authTimeoutMs` | `5000` | Time a new connection has to log in. |
| `authMaxFailures` | `5` | Wrong passwords from one address that trigger a ban. |
| `authFailureWindowMs` | `600000` | Window in which wrong passwords are counted (10 minutes). |
| `authBanMs` | `300000` | Length of the ban (5 minutes). |
| `passwordHash` | none | Hash of the password. If missing, a password is generated at start. |

## Password hash

Make one with the plugin jar:

```bash
java -cp mods/rcon-x.x.x.jar com.madscientiste.rcon.infrastructure.AuthenticationService 'your password'
```

Two formats are accepted:

- Current: `pbkdf2-sha512:<iterations>:<base64 salt>:<base64 hash>`. This is what the command
  above prints.
- Old: `<base64 salt>:<base64 hash>`, one round of SHA-256 over salt then password. It still
  works, so existing configs need no change, but the plugin prints a warning at start: this
  format is fast to crack if the config file leaks. Make a new hash to upgrade.

## Protocol notes for client authors

- A refused login is answered with request id `-1`, as the RCON protocol defines.
- A reply longer than one packet (4096 bytes) is sent as several packets with the same request id,
  in order. Clients that read one packet per command see only the first part.
- Packets after a failed login or a protocol error on the same connection are never handled.
