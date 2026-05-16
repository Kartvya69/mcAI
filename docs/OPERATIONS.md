# Operations

This guide covers building, installing, configuring, and verifying mcAI on a Paper/Folia server.

## Requirements

- Java 21
- Paper or Folia compatible with API `1.21`
- Network access during builds for Gradle dependency resolution
- A secure place to store the MCP bearer token

## Build

Build the shaded plugin jar from the repository root:

```bash
./gradlew build
```

Expected artifact:

```text
build/libs/mcAI-0.1.0.jar
```

Optional artifact inspection:

```bash
jar tf build/libs/mcAI-0.1.0.jar | rg '^(dev/mcai|io/ktor|io/modelcontextprotocol)' | head -n 20
```

## Commit Release Artifacts

Jar publishing is handled locally and intentionally does not use GitHub Actions.

Install the tracked hooks once per clone:

```bash
git config core.hooksPath .githooks
```

After installation:

- `.githooks/pre-commit` runs `./gradlew test build`
- `.githooks/post-commit` runs `scripts/release-current-commit.sh`
- the release script builds from a temporary worktree at the exact commit SHA
- the current branch is pushed to `origin`
- tag `commit-<12-char-sha>` is created or reused
- jar asset `mcAI-<12-char-sha>.jar` is uploaded to the GitHub release
- when releasing current `HEAD`, tag `latest` is moved and `mcAI-latest.jar` is uploaded

Stable latest links:

- `https://github.com/Kartvya69/mcAI/releases/tag/latest`
- `https://github.com/Kartvya69/mcAI/releases/download/latest/mcAI-latest.jar`

Manual rerun for the current commit:

```bash
scripts/release-current-commit.sh
```

Backfill a specific commit:

```bash
scripts/release-current-commit.sh <commit-sha>
```

## Install

Stop the target Paper server, then copy the shaded jar into `plugins/`:

```bash
install -m 0644 build/libs/mcAI-0.1.0.jar /path/to/paper/plugins/mcAI.jar
```

Start Paper again:

```bash
cd /path/to/paper
java -jar paper.jar --nogui
```

On first load, mcAI creates:

```text
plugins/mcAI/config.yml
```

The generated config includes a bearer token and leaves `server.port` unset. MCP remains disabled until you choose a dedicated HTTP port.

## Configure MCP

Edit `plugins/mcAI/config.yml`.

Safe loopback-only example:

```yaml
server:
  host: "127.0.0.1"
  port: 25577

auth:
  token: "<long-random-secret>"
```

Port rules enforced by the plugin:

- `server.port` must not be `null`.
- `server.port` must not equal the Minecraft gameplay port.
- If either rule fails, the plugin stays loaded but does not start the MCP HTTP server.

Recommended practice:

- Keep Minecraft gameplay on its normal port, commonly `25565`.
- Use a distinct MCP port, commonly `25577`.
- Bind to `127.0.0.1` unless remote MCP access is required.
- If remote access is required, use a reverse proxy, tunnel, VPN, or firewall outside the plugin.

## Power Actions

`power_actions` is a write-capable MCP tool for whole-server stop and restart operations.

Security and safety notes:

- MCP bearer authentication is the security boundary. Any client with the token can take the server offline.
- `action: "stop"` calls the native Bukkit/Paper shutdown API.
- `action: "restart"` calls the native Bukkit/Paper restart API.
- `action: "restart"` requires the `settings.restart-script` file configured in `spigot.yml` to exist. Paper passes this script to the OS shell when restarting.
- Use `power_actions` for stop/restart instead of sending `stop` or `restart` through `console_send_command`.
- `delaySeconds` is capped at 600 seconds, or 10 minutes.
- Only one delayed power action can be pending. A new delayed stop/restart replaces the previous pending action.
- Delayed power actions are in-process best effort and disappear if the server or plugin shuts down before the delay fires.
- Provide `reason` when operators or players should see why the server is stopping or restarting.

## Downloader Policy

`fs_download_file` is write-capable. Keep the default private-network blocking unless you deliberately need an internal artifact source.

Default policy:

```yaml
downloadPolicy:
  connectTimeoutMillis: 5000
  requestTimeoutMillis: 30000
  readTimeoutMillis: 30000
  blockPrivateNetworks: true
  trustedHosts: []
  trustedCidrs: []
  maxRedirects: 5
```

Behavior:

- Only `http` and `https` URLs are accepted.
- Every redirect target is revalidated.
- Private, loopback, link-local, multicast, and metadata-style targets are blocked by default.
- Downloads write to a temp file and move into place only after size and checksum checks pass.
- `sha256` is optional, but when provided it must match exactly.

Allow internal sources only when they are trusted:

```yaml
downloadPolicy:
  blockPrivateNetworks: true
  trustedHosts:
    - "artifacts.example.internal"
  trustedCidrs:
    - "10.0.0.0/8"
```

## Path Index

`fs_find_paths` uses an in-memory path index when ready. The index is built asynchronously, updated through Java `WatchService`, and reconciled periodically.

Default settings:

```yaml
pathIndex:
  reconciliationIntervalMillis: 600000
  excludeGlobs:
    - ".git/**"
    - ".gradle/**"
    - "build/**"
    - "cache/**"
    - "libraries/**"
    - "versions/**"
    - "world*/region/**"
    - "world*/entities/**"
```

Guidance:

- Keep world `region` and `entities` excluded unless you have a strong reason to search them.
- Add custom cache, backup, or artifact directories to `excludeGlobs`.
- Use `useIndex: false` on `fs_find_paths` when you need a live walk.
- Check the returned freshness metadata before trusting indexed results for very recent changes.

## Verification

Build and test:

```bash
./gradlew test
./gradlew build
```

Verify the plugin starts:

```bash
rg -n "mcAI MCP server listening|auth token config location|disabled because|Failed to start mcAI MCP server" logs/latest.log
```

Expected enabled-state messages include:

```text
mcAI MCP server listening at http://127.0.0.1:25577/mcp
mcAI MCP auth token config location: plugins/mcAI/config.yml -> auth.token
```

Confirm the MCP port is listening:

```bash
ss -ltnp | rg ':25577'
```

Unauthenticated requests should fail:

```bash
curl -i \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  http://127.0.0.1:25577/mcp
```

Authenticated `tools/list` smoke:

```bash
export MCAI_TOKEN='<token-from-plugins/mcAI/config.yml>'

curl -sS \
  -H "Authorization: Bearer ${MCAI_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  http://127.0.0.1:25577/mcp
```

Functional smoke against a known file:

```bash
curl -sS \
  -H "Authorization: Bearer ${MCAI_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"fs_stat","arguments":{"path":"server.properties"}}}' \
  http://127.0.0.1:25577/mcp
```

## Backups and Safety

Before enabling MCP on a production server, back up:

- `server.properties`
- `plugins/`
- `config/`
- `world/`, `world_nether/`, and `world_the_end/`
- datapacks and plugin-managed data

Safer backup sequence:

1. Run `save-all flush`.
2. Stop the server cleanly.
3. Take the backup.
4. Restart after the backup completes.

Example:

```bash
tar -czf paper-backup-$(date +%Y%m%d-%H%M%S).tgz \
  server.properties plugins config world world_nether world_the_end
```

## Troubleshooting

### MCP does not start

Check for:

- `server.port is not configured`
- `server.port equals the Minecraft gameplay port`
- `Failed to start mcAI MCP server`

Fix:

- Set `server.port`.
- Ensure it differs from the Minecraft gameplay port.
- Confirm the MCP port is not already in use.

### `401 Unauthorized`

Fix:

- Re-read `auth.token` from `plugins/mcAI/config.yml`.
- Send `Authorization: Bearer <token>`.
- Restart the client session after rotating the token.

### MCP is reachable on the wrong interface

Fix:

- Change `server.host` from `0.0.0.0` to `127.0.0.1` or a specific trusted interface.
- Restart Paper.

### Downloads are blocked

Likely cause:

- The target or one of its redirects resolves to a private/internal address.

Fix:

- Keep blocking enabled unless the destination is trusted.
- Add the narrowest possible `trustedHosts` or `trustedCidrs`.
- Prefer using `sha256` for downloaded artifacts.

### `fs_find_paths` misses files

Likely causes:

- The path is excluded by `pathIndex.excludeGlobs`.
- The index is not ready.
- Indexed results may be stale.

Fix:

- Inspect the `freshness` object.
- Use `useIndex: false` for a live walk.
- Adjust excludes only when needed.

### Console command output is incomplete

Fix:

- Increase `limits.commandCaptureMillis` if a command logs after the capture window.
