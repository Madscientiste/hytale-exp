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
| Password guessing | 5 wrong passwords from one address within 10 minutes ban that address for 5 minutes. Every failed login is logged with the address, before the reply is sent, so hanging up early does not hide it. |
| Holding all connection slots | A new connection has 5 seconds to log in, however slowly it sends bytes. One address may hold 4 connections at most. The last free slot is kept for this machine (see below). |
| Filling the disk with log lines | An address that has not logged in can cause 20 log lines per 10 seconds. The rest are counted and the count is logged. Bad input and dropped connections never log a stack trace. |
| Filling memory before login | Before login a packet may be 4096 bytes at most, whatever `maxFrameSize` says. |
| Forged log lines | Control characters in logged values (command text included) are written as visible escapes such as `\n` and `\u001b`. |
| Leaked config file | The config file is written with mode 600 (owner only). The password is stored as a PBKDF2 hash. |
| Broken config file | RCON stays off and the log says what is wrong. The file is never replaced. A config folder that cannot be read counts as broken too. |
| No password set | RCON refuses to start, unless you opt in to the no-password mode (see below). The plugin itself makes a password on first start, so this only happens when you run the server class directly. |

Every command that runs is logged in full with the address it came from. Arguments that contain
`password`, `secret` or `token` are replaced with `[REDACTED]`.

### How addresses are counted

- **This machine is one address.** All of `127.0.0.0/8` and `::1` count as one peer, called
  loopback. A local program can pick any `127.x.y.z` as its source, so counting them apart would
  be no limit at all.
- Other IPv4 addresses are counted one by one.
- Other IPv6 addresses are counted per /64 network, because one machine usually owns a whole /64.

### The limits apply to this machine too

A local program that sends 5 wrong passwords gets loopback banned for 5 minutes, and during that
time your own local RCON clients (a health check, for example) are refused as well. This is on
purpose: the other choice is unlimited guessing from the machine itself.

### The last slot is kept for this machine

Of `maxConnections`, the last free slot can only be taken by loopback. With the defaults, outside
addresses can hold 9 of the 10 slots. So even when several outside addresses fill the server, a
local client still gets in: for example the Docker health check, which runs `rcon-cli` inside the
container. (With `maxConnections` set to 1 nothing is kept back.)

### Several admins behind one address

Sometimes every outside client reaches the server with the same address: rootless Docker, Docker
Desktop, Docker's userland proxy, or a NAT in front of the server. The plugin then cannot tell
them apart. One attacker sending wrong passwords keeps that shared address banned, and all admins
behind it are locked out with them.

`docker exec <container> rcon-cli ...` still works in that case: it runs inside the container and
uses loopback, which is counted on its own.

### What is not protected

- The generated password is printed once in the server log on first start. Read it, then protect
  or delete that log file. To avoid this, set your own `passwordHash` before the first start.
- Anyone who can read the network between client and server can read the password. See the short
  version above.
- An attacker using many different addresses can still fill the slots open to outside addresses
  while the attack lasts. Local clients keep their slot, outside admins do not. That is one more
  reason to keep the port closed to the internet.

## Config file

`configs/com.madscientiste.rcon.json`, relative to the server folder. Missing keys take the
default. Unknown keys, wrong types and out-of-range values are errors: RCON then stays off until
the file is fixed, and the server log says which key is wrong.

| Key | Default | Meaning |
| --- | --- | --- |
| `host` | `127.0.0.1` | Address to listen on. |
| `port` | `25575` | Port to listen on. |
| `maxConnections` | `10` | Connections in total. The last one is kept for this machine. |
| `maxConnectionsPerIp` | `4` | Connections from one address. |
| `maxFrameSize` | `4096` | Largest packet a logged-in client may send, in bytes (64 to 1048576). Before login the limit is 4096 at most. |
| `readTimeoutMs` | `30000` | A logged-in connection is closed after this long without a complete packet. |
| `connectionTimeoutMs` | `5000` | How often the listener wakes up to check for shutdown. |
| `authTimeoutMs` | `5000` | Time a new connection has to log in. |
| `authMaxFailures` | `5` | Failed logins from one address that trigger a ban. |
| `authFailureWindowMs` | `600000` | Window in which failed logins are counted (10 minutes). |
| `authBanMs` | `300000` | Length of the ban (5 minutes). |
| `passwordHash` | none | Hash of the password. If missing, a password is generated at start (unless `allowNoPassword` is `true`). |
| `allowNoPassword` | `false` | Set to `true` to run without a password. See below. |
| `noPasswordAllowFrom` | `["127.0.0.0/8", "::1"]` | Who may log in while there is no password. |

## Running without a password

Off by default. To turn it on, leave `passwordHash` out and write:

```json
{
  "allowNoPassword": true,
  "noPasswordAllowFrom": ["127.0.0.0/8", "::1"]
}
```

- Any login is then accepted, with any password or none, but only from the addresses in
  `noPasswordAllowFrom`. The default is this machine only.
- The list takes single addresses and CIDR ranges, IPv4 and IPv6. You can widen it to anything,
  `0.0.0.0/0` included. Whoever is on the list gets the full server console, so think twice.
- Logins from any other address are refused and count as failed logins: 5 of them ban the address.
- The plugin prints a loud warning at every start, naming the allowed ranges.
- If `passwordHash` is set, the password is required and `allowNoPassword` does nothing.

## Password hash

Make one with the plugin jar:

```bash
java -cp mods/rcon-x.x.x.jar com.madscientiste.rcon.infrastructure.AuthenticationService 'your password'
```

Two formats are accepted:

- Current: `pbkdf2-sha512:<iterations>:<base64 salt>:<base64 hash>`. This is what the command
  above prints, with 210000 iterations. A hash with 10000 to 1000000 iterations is accepted.
  Below 210000 the plugin prints a warning at start. Outside that range the config is refused:
  fewer is no protection, more makes one login slower than the login deadline.
- Old: `<base64 salt>:<base64 hash>`, one round of SHA-256 over salt then password. It still
  works, so existing configs need no change, but the plugin prints a warning at start: this
  format is fast to crack if the config file leaks. Make a new hash to upgrade.

## Protocol notes for client authors

- A refused login is answered with request id `-1`, as the RCON protocol defines.
- A reply longer than one packet (4096 bytes) is sent as several packets with the same request id,
  in order. Clients that read one packet per command see only the first part.
- Packets after a failed login or a protocol error on the same connection are never handled.
