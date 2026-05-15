# mcAI

mcAI is a Paper/Folia plugin that exposes a bearer-protected MCP HTTP server for Minecraft server administration. It gives trusted MCP clients a controlled way to inspect and edit files under the server root, update common config formats, discover paths, download artifacts, and dispatch console commands.

## Features

- Authenticated MCP endpoint at `/mcp`
- Server-root jailed filesystem tools for read, write, append, edit, copy, move, delete, stat, tree, tail, and search operations
- Hardened HTTP/HTTPS downloader with timeouts, redirect validation, optional SHA-256 checks, and private-network blocking by default
- Indexed path discovery through `fs_find_paths` with freshness metadata and default excludes for large cache/world data
- Structured config tools for Java `.properties` files and JSON pointer edits
- Console command dispatch with recent `logs/latest.log` capture
- First-run config generation with a generated bearer token
- Java 21, Kotlin, Ktor, and the MCP Kotlin SDK

## Security Model

mcAI is an administration surface. Treat access to it like access to the Paper console and server filesystem.

- Every MCP request requires `Authorization: Bearer <token>`.
- The token is stored in `plugins/mcAI/config.yml` under `auth.token`.
- All filesystem paths are relative to the Minecraft server root.
- Absolute paths and traversal outside the root are rejected.
- Existing symlink and real-path escapes are rejected at the filesystem boundary.
- Reads, writes, directory listings, and downloads are bounded by config limits.
- Downloads only support HTTP/HTTPS and validate every redirect target against the private-network policy.
- The plugin refuses to start the MCP server unless `server.port` is set and differs from the Minecraft gameplay port.

Prefer binding the MCP server to `127.0.0.1` and reaching it through a trusted local process, tunnel, or reverse proxy with its own access controls.

## Quick Start

Build the shaded plugin jar:

```bash
./gradlew build
```

Copy the artifact into a Paper/Folia server:

```bash
install -m 0644 build/libs/mcAI-0.1.0.jar /path/to/paper/plugins/mcAI.jar
```

Start the server once so mcAI can generate:

```text
plugins/mcAI/config.yml
```

Set a dedicated MCP HTTP port. It must not equal the Minecraft gameplay port from `server.properties`.

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

## Configuration

The default config is generated from [src/main/resources/config.yml](src/main/resources/config.yml).

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
```

See [docs/OPERATIONS.md](docs/OPERATIONS.md) for deployment and safety notes.
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for component and runtime flow details.

## MCP Tools

Tool registration is centralized in [McAiMcpServerFactory.kt](src/main/kotlin/dev/mcai/McAiMcpServerFactory.kt).

Main tool families:

- `fs_read_file`, `fs_write_file`, `fs_edit_file`, `fs_append_file`, `fs_download_file`
- `fs_list_directory`, `fs_directory_tree`, `fs_find_paths`, `fs_search_files`, `fs_search_content`
- `fs_create_directory`, `fs_move`, `fs_copy`, `fs_delete`, `fs_stat`, `fs_tail_file`
- `config_properties_get`, `config_properties_set`, `config_properties_remove`, `config_properties_list`
- `config_json_get`, `config_json_set`, `config_json_remove`, `config_json_append`
- `console_send_command`

See [docs/MCP_TOOLS.md](docs/MCP_TOOLS.md) for arguments, access classification, outputs, and examples.

## Development

Requirements:

- Java 21
- Network access for Gradle dependency resolution

Common commands:

```bash
./gradlew test
./gradlew build
./gradlew shadowJar
```

The shaded plugin jar is written to:

```text
build/libs/mcAI-0.1.0.jar
```

## Commit Jars

This repository publishes jar artifacts from local git hooks instead of GitHub Actions.

Install the tracked hooks once per clone:

```bash
git config core.hooksPath .githooks
```

After that, each commit runs the Gradle verification locally, pushes the commit, creates tag `commit-<12-char-sha>`, and uploads `mcAI-<12-char-sha>.jar` as a GitHub prerelease asset.

The newest committed jar is always available from the moving `latest` tag:

- [Latest release tag](https://github.com/Kartvya69/mcAI/releases/tag/latest)
- [Download latest jar](https://github.com/Kartvya69/mcAI/releases/download/latest/mcAI-latest.jar)

If the post-commit release step needs to be rerun manually:

```bash
scripts/release-current-commit.sh
```

## Repository Hygiene

The local Paper runtime under `run/`, Gradle caches, Kotlin session files, and build outputs are ignored. This repository should contain source, Gradle metadata, tests, and documentation, not generated server worlds or packaged runtime state.
