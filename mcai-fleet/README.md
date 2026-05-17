# mcAI Fleet

`mcAI-fleet` is a local stdio MCP gateway for routing mcAI tool calls across multiple Minecraft servers.

The Paper/Folia plugin remains the default direct MCP host at `/mcp`. Fleet is optional: enable the plugin WebSocket API per server, configure those WebSocket endpoints locally, then register one stdio MCP server with your agent client.

## Configure Each Minecraft Server

In `plugins/mcAI/config.yml`:

```yaml
server:
  host: "127.0.0.1"
  port: 25577

auth:
  token: "<server-token>"

websocket:
  enabled: true
```

The WebSocket endpoint is:

```text
ws://127.0.0.1:25577/mcp/ws
```

It uses the same bearer token as `/mcp`.

## Configure Fleet

Create `mcai-fleet.config.json` from the example:

```bash
cp mcai-fleet.config.example.json mcai-fleet.config.json
```

Example:

```json
{
  "requestTimeoutMillis": 5000,
  "servers": [
    {
      "id": "survival",
      "name": "Survival",
      "url": "ws://127.0.0.1:25577/mcp/ws",
      "token": "<server-token>"
    }
  ]
}
```

`mcai-fleet.config.json` is ignored by git because it contains bearer tokens.

## Build and Test

```bash
npm install
npm test
npm run build
```

## Run

From `mcai-fleet/`:

```bash
npm run build
MCAI_FLEET_CONFIG="$PWD/mcai-fleet.config.json" node dist/index.js
```

MCP clients should launch the stdio command from this directory or set `MCAI_FLEET_CONFIG` to an absolute config path.

Fleet registers the existing mcAI tool names and adds a required `serverId` argument to every routed tool. It also registers `server_list` and `server_status` for discovery.

## License

`mcAI-fleet` is covered by the repository root [XreatLabz Source-Available License](../LICENSE). It is not open source.
