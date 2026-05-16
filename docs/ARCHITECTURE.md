# Architecture

mcAI is intentionally plugin-only for each Minecraft server. The plugin runs inside Paper/Folia and starts an authenticated Ktor MCP HTTP endpoint when configured. The optional `mcai-fleet/` project is a local stdio MCP gateway that agents run outside Minecraft to route across multiple plugin instances.

## Runtime Flow

1. Paper loads `dev.mcai.McAiPlugin`.
2. `McAiConfigRepository` loads or creates `plugins/mcAI/config.yml`.
3. `McAiLoggingControls` applies quiet or verbose logging behavior.
4. `McAiStartupDecider` checks whether MCP should be enabled.
5. If enabled, `McAiPlugin` creates:
   - `FileSystemTools`
   - `ConsoleTools`
   - `PowerActions`
   - `McAiMcpServerFactory`
   - `KtorMcpHttpServer`
6. `KtorMcpHttpServer` serves MCP at `/mcp`.
7. `McAiMcpServerFactory` registers the public tool surface and MCP server instructions.
8. If `websocket.enabled` is true, `KtorMcpHttpServer` also serves the fleet WebSocket API at `/mcp/ws`.

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
- optional WebSocket API flag
- quiet/verbose runtime logging

Existing config files are rewritten after load so missing sections, including `websocket.enabled` and `logging.verbose`, are backfilled with defaults.

### `McAiLoggingControls`

Applies the `logging.verbose` setting before the Ktor MCP server starts. Quiet mode leaves mcAI operational warnings and errors visible while raising routine Ktor and MCP SDK logger thresholds to warnings-or-higher. `McAiMcpServerFactory` also uses the setting to suppress routine per-tool `MCP tool call: ...` INFO logs unless verbose logging is enabled.

### `KtorMcpHttpServer`

Hosts the MCP stateless streamable HTTP transport. It enforces bearer authentication before requests reach MCP tool handling.

When `websocket.enabled` is true, it also installs Ktor WebSockets and exposes `/mcp/ws` on the same configured mcAI port. The WebSocket route uses the same bearer token and dispatches JSON request frames to `McAiMcpServerFactory.callTool`.

### `McAiMcpServerFactory`

Registers all MCP tools and maps JSON arguments to typed request objects. It returns structured JSON results and structured error payloads. It also exposes matching tool dispatch for the WebSocket API against the same backend services. It sets MCP initialization instructions for docs-first plugin-configuration work and tells agents to use `power_actions` for stop/restart.

### `mcai-fleet/`

Optional TypeScript stdio MCP gateway for multi-server routing. It:

- loads `mcai-fleet.config.json`
- validates configured `id`, `name`, `url`, and `token`
- registers `server_list` and `server_status`
- registers the existing mcAI tool names with a required `serverId`
- opens authenticated WebSocket connections lazily
- returns structured errors for unknown servers, offline servers, auth failures, timeouts, and plugin tool errors

Fleet does not replace the plugin-hosted `/mcp` endpoint; it is an agent-facing routing layer for operators who want one local MCP registration for multiple Minecraft servers.

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
- optional WebSocket route default-disabled/auth/tool-routing behavior
- filesystem jail behavior
- downloader SSRF, timeout, checksum, and cleanup cases
- indexed finder behavior
- structured `.properties` and JSON editing
- console dispatch behavior
- native power action validation, immediate execution, delayed scheduling, and replacement
- MockBukkit plugin lifecycle behavior
- fleet config parsing, registry lookup, mock WebSocket routing, error handling, and MCP tool schema coverage
