---
name: mcai-fleet
description: Use when an agent needs to install, register, or operate the optional mcAI fleet stdio MCP gateway for routing tools across multiple Minecraft servers.
---

# mcAI Fleet Gateway

Use this skill when the user wants one local MCP server that can route mcAI administration tools to multiple Minecraft servers.

## Preconditions

- The Minecraft server is running the mcAI Paper/Folia plugin.
- Direct plugin MCP at `/mcp` remains available and is still the backward-compatible default.
- Fleet requires the plugin WebSocket API to be explicitly enabled on each target server:

```yaml
websocket:
  enabled: true
```

- The WebSocket endpoint is `ws://<host>:<mcai-port>/mcp/ws`.
- The WebSocket endpoint uses the same `auth.token` bearer token as `/mcp`.

## Local Setup

From the repository root:

```bash
cd mcai-fleet
npm install
cp mcai-fleet.config.example.json mcai-fleet.config.json
```

Edit `mcai-fleet.config.json` with each configured server:

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

Never commit `mcai-fleet.config.json`; it contains bearer tokens.

## Verification

```bash
npm test
npm run build
```

For plugin-side verification:

```bash
./gradlew test
./gradlew build
```

## MCP Client Registration

Register a stdio MCP server that runs:

```bash
node /root/mcAI/mcai-fleet/dist/index.js
```

Set:

```bash
MCAI_FLEET_CONFIG=/root/mcAI/mcai-fleet/mcai-fleet.config.json
```

## Usage Contract

- Discovery tools:
  - `server_list`
  - `server_status`
- Routed tools keep the direct mcAI tool names and require `serverId`.
- Example routed call:

```json
{
  "serverId": "survival",
  "path": "server.properties"
}
```

- Unknown server ids, offline servers, auth failures, request timeouts, and plugin tool errors are returned as structured MCP tool errors.
