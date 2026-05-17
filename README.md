# mcAI

[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Paper/Folia](https://img.shields.io/badge/Paper%20%2F%20Folia-1.21%2B-2d6cdf)](https://papermc.io/)
[![MCP](https://img.shields.io/badge/MCP-Streamable%20HTTP-111827)](https://modelcontextprotocol.io/)
[![License](https://img.shields.io/badge/License-Source--Available-f59e0b)](LICENSE)

mcAI is a Paper/Folia plugin that exposes a bearer-protected MCP administration
server for Minecraft. It gives trusted MCP clients a controlled way to inspect
and edit server files, update common config formats, discover paths, download
artifacts, dispatch console commands, and run native stop/restart actions.

Use the plugin directly at `/mcp` for one server. Use the optional
`mcAI-fleet` stdio gateway when your AI client should control multiple
Minecraft servers through one local MCP server.

## Quick Links

- [Download latest plugin jar](https://github.com/Kartvya69/mcAI/releases/download/latest/mcAI-latest.jar)
- [Latest release tag](https://github.com/Kartvya69/mcAI/releases/tag/latest)
- [Tool contract](docs/MCP_TOOLS.md)
- [Operations guide](docs/OPERATIONS.md)
- [Architecture notes](docs/ARCHITECTURE.md)
- [Fleet gateway guide](mcai-fleet/README.md)

## What It Provides

- Authenticated MCP endpoint at `/mcp`
- Server-root jailed filesystem tools for read, write, append, edit, copy, move,
  delete, stat, tree, tail, and search operations
- Hardened HTTP/HTTPS downloader with timeouts, redirect validation, optional
  SHA-256 checks, and private-network blocking by default
- Indexed path discovery through `fs_find_paths` with freshness metadata and
  default excludes for large cache and world data
- Structured config tools for Java `.properties` files and JSON pointer edits
- Console command dispatch with recent `logs/latest.log` capture
- Native `power_actions` tool for authenticated stop and restart operations
- MCP server instructions that tell agents to inspect local plugin files first,
  then check official plugin docs or trusted current sources before editing
  unfamiliar plugin configuration
- Optional WebSocket API at `/mcp/ws` for the local `mcAI-fleet` stdio gateway
- First-run config generation with a generated bearer token
- Quiet runtime logging by default, with `logging.verbose` for raw MCP, Ktor,
  and SDK debugging logs

## Architecture

```text
AI MCP client
  |
  | Streamable HTTP + Bearer token
  v
Paper/Folia server
  plugins/mcAI.jar
  http://127.0.0.1:25577/mcp
  |
  +-- jailed filesystem/config tools under the Minecraft server root
  +-- console command dispatch with latest.log capture
  +-- native stop/restart through Bukkit/Paper APIs
```

For multi-server setups, keep each plugin instance local to its Minecraft
server and register one local `mcAI-fleet` stdio MCP server with your AI client:

```text
AI MCP client
  |
  | stdio
  v
mcAI-fleet
  |
  | WebSocket + Bearer token per server
  +--> survival: ws://127.0.0.1:25577/mcp/ws
  +--> creative: ws://127.0.0.1:25578/mcp/ws
```

## Install The Plugin

Download the latest jar:

```bash
curl -L -o mcAI.jar \
  https://github.com/Kartvya69/mcAI/releases/download/latest/mcAI-latest.jar
```

Copy the downloaded jar into a Paper/Folia server:

```bash
install -m 0644 mcAI.jar /path/to/paper/plugins/mcAI.jar
```

Or build it locally and install the shaded artifact:

```bash
./gradlew build
install -m 0644 build/libs/mcAI-0.1.0.jar /path/to/paper/plugins/mcAI.jar
```

Start the server once so mcAI can generate:

```text
plugins/mcAI/config.yml
```

Set a dedicated MCP HTTP port. It must not equal the Minecraft gameplay port
from `server.properties`.

```yaml
server:
  host: "127.0.0.1"
  port: 25577

auth:
  token: "<generated-or-replaced-secret>"
```

Restart Paper and point your MCP client to:

```text
http://127.0.0.1:25577/mcp
```

with:

```http
Authorization: Bearer <token>
```

If `server.port` remains `null`, the plugin loads but MCP stays disabled.

## Use mcAI Fleet

Direct plugin MCP at `/mcp` remains the default. Use `mcAI-fleet` when an agent
should connect to one local stdio MCP server and route calls across multiple
Minecraft servers.

Enable the WebSocket API on each Minecraft server that fleet should reach:

```yaml
websocket:
  enabled: true
```

The WebSocket endpoint is:

```text
ws://<server.host>:<server.port>/mcp/ws
```

It uses the same bearer token as `/mcp`.

Build and configure fleet:

```bash
cd mcai-fleet
npm install
cp mcai-fleet.config.example.json mcai-fleet.config.json
npm test
npm run build
```

Example `mcai-fleet.config.json`:

```json
{
  "requestTimeoutMillis": 5000,
  "servers": [
    {
      "id": "survival",
      "name": "Survival",
      "url": "ws://127.0.0.1:25577/mcp/ws",
      "token": "<plugins/mcAI/config.yml auth.token>"
    }
  ]
}
```

`mcai-fleet.config.json` is ignored by git because it contains bearer tokens.

Register the built stdio server with your MCP client:

```bash
MCAI_FLEET_CONFIG=/absolute/path/to/mcAI/mcai-fleet/mcai-fleet.config.json \
  node /absolute/path/to/mcAI/mcai-fleet/dist/index.js
```

Fleet adds:

- `server_list`
- `server_status`

It also registers the existing mcAI tool names with an added required
`serverId` argument.

## AI Agent Setup Prompt

Copy this prompt into any AI coding agent when you want it to add `mcAI-fleet`
to its own MCP-capable platform. Fill in the placeholders first.

```text
You are configuring the mcAI Fleet stdio MCP server for this AI platform.

Goal:
- Add a local stdio MCP server named "mcai-fleet".
- The server routes Minecraft administration tool calls to one or more mcAI
  Paper/Folia plugin instances over WebSocket.
- Use this platform's native MCP registration mechanism if it has one. If it
  does not, edit the platform's MCP config file directly.

Inputs:
- mcAI repository path: <absolute path to cloned mcAI repo>
- Fleet config path: <absolute path to mcai-fleet.config.json>
- Minecraft servers:
  - id: <stable id such as survival>
    name: <display name>
    websocket_url: <ws://host:port/mcp/ws>
    token: <plugins/mcAI/config.yml auth.token>

Required steps:
1. Inspect this platform's MCP documentation or existing local MCP config so
   the registration uses the correct shape for this client.
2. Verify Node.js 20 or newer is available.
3. In <mcAI repository path>/mcai-fleet, run:
   - npm install
   - npm test
   - npm run build
4. Create or update <Fleet config path> using mcai-fleet.config.example.json as
   the template. Put each Minecraft server in the "servers" array with id,
   name, url, and token.
5. Keep tokens secret. Do not commit mcai-fleet.config.json or print token
   values in final output.
6. Register the MCP server as stdio with:
   - command: node
   - args: ["<mcAI repository path>/mcai-fleet/dist/index.js"]
   - env:
       MCAI_FLEET_CONFIG: "<Fleet config path>"
   - cwd: "<mcAI repository path>/mcai-fleet" if the platform supports cwd
7. Restart or reload the AI platform's MCP server list if required.
8. Verify the connection by listing tools, then call server_list. If possible,
   call server_status for each configured server.

Important behavior:
- mcAI-fleet exposes server_list and server_status.
- Existing mcAI tool names are available through fleet, but each routed tool
  requires a serverId argument.
- The target Paper/Folia plugin must have websocket.enabled: true.
- The WebSocket endpoint uses the same bearer token as the plugin's /mcp HTTP
  endpoint.
- For direct single-server MCP, use the plugin's Streamable HTTP endpoint at
  http://host:port/mcp instead of fleet.

Final response:
- State which MCP config or command was changed.
- State the registered command, args, cwd, and env key names, but redact token
  values.
- Include the exact verification performed and whether server_list/server_status
  worked.
- Mention any manual restart/reload still required by the platform.
```

## Tool Surface

Tool registration is centralized in
[McAiMcpServerFactory.kt](src/main/kotlin/dev/mcai/McAiMcpServerFactory.kt).

Main tool families:

- Files: `fs_read_file`, `fs_read_many_files`, `fs_write_file`,
  `fs_edit_file`, `fs_append_file`, `fs_download_file`,
  `fs_list_directory`, `fs_directory_tree`, `fs_find_paths`,
  `fs_search_files`, `fs_search_content`, `fs_create_directory`, `fs_move`,
  `fs_copy`, `fs_delete`, `fs_stat`, `fs_tail_file`
- Java properties: `config_properties_get`, `config_properties_set`,
  `config_properties_remove`, `config_properties_list`
- JSON: `config_json_get`, `config_json_set`, `config_json_remove`,
  `config_json_append`
- Console: `console_send_command`
- Power: `power_actions`

See [docs/MCP_TOOLS.md](docs/MCP_TOOLS.md) for arguments, access
classification, outputs, and examples.

## Security Model

mcAI is an administration surface. Treat access to it like access to the Paper
console and server filesystem.

- Every MCP request requires `Authorization: Bearer <token>`.
- The token is stored in `plugins/mcAI/config.yml` under `auth.token`.
- All filesystem paths are relative to the Minecraft server root.
- Absolute paths and traversal outside the root are rejected.
- Existing symlink and real-path escapes are rejected at the filesystem
  boundary.
- Reads, writes, directory listings, and downloads are bounded by config limits.
- Downloads only support HTTP/HTTPS and validate every redirect target against
  the private-network policy.
- The plugin refuses to start the MCP server unless `server.port` is set and
  differs from the Minecraft gameplay port.
- The WebSocket API is disabled by default and uses the same bearer token as
  `/mcp` when enabled.
- `power_actions` can stop or restart the whole Minecraft server. MCP bearer
  auth is the security boundary for this operation.
- Restart requires the `settings.restart-script` file configured in
  `spigot.yml` to exist.

Prefer binding the MCP server to `127.0.0.1` and reaching it through a trusted
local process, tunnel, VPN, or reverse proxy with its own access controls.

## Configuration

The default config is generated from
[src/main/resources/config.yml](src/main/resources/config.yml).

```yaml
server:
  host: "0.0.0.0"
  port: null

auth:
  token: "<generated-on-first-start>"

limits:
  maxReadBytes: 104857600
  maxWriteBytes: 104857600
  maxDirectoryEntries: 10000
  commandCaptureMillis: 1500

downloadPolicy:
  connectTimeoutMillis: 5000
  requestTimeoutMillis: 30000
  readTimeoutMillis: 30000
  blockPrivateNetworks: true
  trustedHosts: []
  trustedCidrs: []
  maxRedirects: 5

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

websocket:
  enabled: false

logging:
  verbose: false
```

`logging.verbose` defaults to `false`, including when older config files are
backfilled on the next plugin load. Quiet mode keeps startup, disabled-state,
listening, token-location, auth-failure, tool-error, power-action warning, and
fatal startup logs, while suppressing routine per-tool MCP call logs and
routine Ktor/MCP SDK INFO logs.

See [docs/OPERATIONS.md](docs/OPERATIONS.md) for deployment and safety notes.
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for component and runtime flow
details.

## Development

Requirements:

- Java 21
- Network access for Gradle dependency resolution
- Node.js 20 or newer for `mcAI-fleet`

Common plugin commands:

```bash
./gradlew test
./gradlew build
./gradlew shadowJar
```

Common fleet commands:

```bash
cd mcai-fleet
npm test
npm run build
```

The shaded plugin jar is written to:

```text
build/libs/mcAI-0.1.0.jar
```

## Commit Jars

This repository publishes jar artifacts from local git hooks instead of GitHub
Actions.

Install the tracked hooks once per clone:

```bash
git config core.hooksPath .githooks
```

After that, each commit runs Gradle verification locally, pushes the commit,
creates tag `commit-<12-char-sha>`, and uploads `mcAI-<12-char-sha>.jar` as a
GitHub prerelease asset.

The newest committed jar is always available from the moving `latest` tag:

- [Latest release tag](https://github.com/Kartvya69/mcAI/releases/tag/latest)
- [Download latest jar](https://github.com/Kartvya69/mcAI/releases/download/latest/mcAI-latest.jar)

If the post-commit release step needs to be rerun manually:

```bash
scripts/release-current-commit.sh
```

## Repository Hygiene

The local Paper runtime under `run/`, Gradle caches, Kotlin session files, and
build outputs are ignored. This repository should contain source, Gradle
metadata, tests, and documentation, not generated server worlds, packaged
runtime state, or bearer tokens.

## License

mcAI is source-available under the
[XreatLabz Source-Available License](LICENSE). It is not open source.

You may inspect the code and submit issues or pull requests. You may not use,
redistribute, host, sell, rebrand, package, or create derivative works from
this project except as expressly allowed in the license.

Contributions are accepted under the terms in [CONTRIBUTING.md](CONTRIBUTING.md).
