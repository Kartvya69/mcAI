# Architecture

mcAI is intentionally plugin-only. It runs inside Paper/Folia and starts an authenticated Ktor MCP HTTP endpoint when configured.

## Runtime Flow

1. Paper loads `dev.mcai.McAiPlugin`.
2. `McAiConfigRepository` loads or creates `plugins/mcAI/config.yml`.
3. `McAiStartupDecider` checks whether MCP should be enabled.
4. If enabled, `McAiPlugin` creates:
   - `FileSystemTools`
   - `ConsoleTools`
   - `PowerActions`
   - `McAiMcpServerFactory`
   - `KtorMcpHttpServer`
5. `KtorMcpHttpServer` serves MCP at `/mcp`.
6. `McAiMcpServerFactory` registers the public tool surface and MCP server instructions.

## Main Components

### `McAiPlugin`

Paper plugin entrypoint. It owns startup and shutdown, including closing the MCP server and filesystem index watchers.

### `Config.kt`

Defines config data and first-run generation:

- HTTP bind settings
- bearer token
- file/read/write limits
- downloader policy
- path index policy

### `KtorMcpHttpServer`

Hosts the MCP stateless streamable HTTP transport. It enforces bearer authentication before requests reach MCP tool handling.

### `McAiMcpServerFactory`

Registers all MCP tools and maps JSON arguments to typed request objects. It returns structured JSON results and structured error payloads. It also sets MCP initialization instructions for docs-first plugin-configuration work and tells agents to use `power_actions` for stop/restart.

### `FileSystemTools`

Primary server-root security boundary. It handles:

- path normalization and jail checks
- read/write limits
- file operations
- downloader policy enforcement
- path indexing
- `.properties` mutation
- JSON pointer mutation

### `ConsoleTools`

Dispatches Minecraft console commands and captures recent log output from `logs/latest.log`.

### `PowerActions`

Owns whole-server stop and restart behavior for MCP. It validates the `stop`/`restart` action, caps delays at 600 seconds, preflights the configured restart script before accepting restart, returns structured scheduling metadata, and ensures only one delayed action is pending.

`BukkitNativePowerActionExecutor` calls Bukkit/Paper APIs directly:

- `server.shutdown()` for stop
- `server.spigot().restart()` for restart

`BukkitPowerActionRunner` runs immediate actions on the server scheduler when needed and schedules delayed actions through the global Paper/Folia scheduler.

## Filesystem Boundary

All filesystem tools resolve paths under the Minecraft server root. The implementation rejects:

- absolute paths
- `..` traversal escaping the root
- existing paths whose real path leaves the root
- write parents whose nearest existing ancestor leaves the root

The root is the Paper world container path returned by the server, not the plugin data folder.

## Indexing

`FileSystemTools` starts path indexing on construction:

- asynchronous initial build
- Java `WatchService` updates
- scheduled full reconciliation
- configurable excludes

`fs_find_paths` reports:

- `source`: `index` or `live`
- `indexedAt`
- `isIndexReady`
- `mayBeStale`

## Testing

The test suite covers:

- config generation and startup decisions
- MCP auth and tool calls through Ktor
- filesystem jail behavior
- downloader SSRF, timeout, checksum, and cleanup cases
- indexed finder behavior
- structured `.properties` and JSON editing
- console dispatch behavior
- native power action validation, immediate execution, delayed scheduling, and replacement
- MockBukkit plugin lifecycle behavior
