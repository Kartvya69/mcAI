# MCP Tools

mcAI exposes a stateless MCP HTTP endpoint at `/mcp`. Requests must include:

```http
Authorization: Bearer <token>
```

The token is stored in `plugins/mcAI/config.yml`.

## Response Shape

Successful tool calls return `isError: false` and a JSON object in `structuredContent`.

```json
{
  "isError": false,
  "structuredContent": {
    "path": "plugins/test.txt",
    "bytesWritten": 5
  }
}
```

Tool failures return `isError: true` and a structured error payload.

```json
{
  "isError": true,
  "structuredContent": {
    "error": {
      "type": "PathOutsideServerRootException",
      "message": "Path is outside the Minecraft server root: ../settings.json"
    }
  }
}
```

## Common Safety Rules

- Paths are relative to the Minecraft server root.
- Absolute paths are rejected.
- Path traversal outside the root is rejected.
- Existing path symlink and real-path escapes are rejected.
- Read and write sizes are bounded by `limits.maxReadBytes` and `limits.maxWriteBytes`.
- Tools registered as read-only use `readOnlyHint: true`; write-capable tools use `readOnlyHint: false`.
- All tools use `openWorldHint: false`.

## File Tools

| Tool | Access | Required arguments | Optional arguments | Result |
| --- | --- | --- | --- | --- |
| `fs_read_file` | Read-only | `path` | `encoding`, `offset`, `length` | `path`, `encoding`, `content`, `bytesRead`, `offset`, `truncated` |
| `fs_read_many_files` | Read-only | `paths` | `encoding`, `maxBytesPerFile` | `files` |
| `fs_write_file` | Write | `path`, `content` | `encoding`, `createParents` | `path`, `bytesWritten` |
| `fs_edit_file` | Write | `path`, `oldText`, `newText` | `replaceAll` | `path`, `replacements` |
| `fs_append_file` | Write | `path`, `content` | `encoding`, `createParents` | `path`, `bytesWritten` |
| `fs_download_file` | Write | `url`, `path` | `overwrite`, `createParents`, `sha256` | `path`, `url`, `finalUrl`, `statusCode`, `bytesWritten`, `sha256` |
| `fs_create_directory` | Write | `path` | none | `path` |
| `fs_move` | Write | `source`, `destination` | `overwrite`, `createParents` | `source`, `destination` |
| `fs_copy` | Write | `source`, `destination` | `overwrite`, `createParents` | `source`, `destination` |
| `fs_delete` | Write | `path` | `recursive` | `path`, `deleted` |
| `fs_stat` | Read-only | `path` | none | `path`, `type`, `size`, timestamps, `readable`, `writable` |
| `fs_tail_file` | Read-only | `path` | `lines` | `path`, `lines` |

### `fs_download_file`

`fs_download_file` only supports HTTP and HTTPS URLs. It writes to a temporary file, validates size and optional SHA-256, then moves the file into place.

Downloader protections:

- connect, request, and read timeouts
- maximum redirects
- redirect target validation
- private/internal network blocking by default
- trusted host/CIDR allowlists for intentional internal targets
- temp-file cleanup on failure

Example:

```json
{
  "name": "fs_download_file",
  "arguments": {
    "url": "https://example.com/plugin.jar",
    "path": "plugins/example.jar",
    "createParents": true,
    "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }
}
```

## Discovery and Search Tools

| Tool | Access | Required arguments | Optional arguments | Result |
| --- | --- | --- | --- | --- |
| `fs_list_directory` | Read-only | none | `path` | `path`, `entries` |
| `fs_directory_tree` | Read-only | none | `path`, `maxDepth` | `path`, `entries` |
| `fs_find_paths` | Read-only | `query` | `path`, `glob`, `maxResults`, `includeDirectories`, `useIndex` | `matches`, `freshness` |
| `fs_search_files` | Read-only | `query` | `path`, `glob`, `maxResults` | `matches` |
| `fs_search_content` | Read-only | `query` | `path`, `regex`, `glob`, `maxResults` | `matches` |

### `fs_find_paths`

`fs_find_paths` uses the in-memory path index when `useIndex` is true and the index is ready. If the index is not ready, it falls back to a live walk and returns freshness metadata.

Example:

```json
{
  "name": "fs_find_paths",
  "arguments": {
    "query": "config",
    "path": ".",
    "glob": "**/*",
    "maxResults": 25,
    "includeDirectories": false,
    "useIndex": true
  }
}
```

Result shape:

```json
{
  "matches": [
    {
      "path": "plugins/example/config.yml",
      "type": "file",
      "size": 128,
      "lastModifiedMillis": 1763226000000
    }
  ],
  "freshness": {
    "source": "index",
    "indexedAt": 1763226000000,
    "isIndexReady": true,
    "mayBeStale": false
  }
}
```

Default index excludes:

- `.git/**`
- `.gradle/**`
- `build/**`
- `cache/**`
- `libraries/**`
- `versions/**`
- `world*/region/**`
- `world*/entities/**`

Use `fs_search_files` and `fs_search_content` when you specifically need a live filesystem walk.

## Properties Tools

These tools target Java `.properties` files such as `server.properties`. They preserve comments and unrelated lines during mutation.

| Tool | Access | Required arguments | Result |
| --- | --- | --- | --- |
| `config_properties_get` | Read-only | `path`, `key` | `path`, `key`, `value`, `found` |
| `config_properties_set` | Write | `path`, `key`, `value` | `path`, `key`, `value`, `action`, `removed` |
| `config_properties_remove` | Write | `path`, `key` | `path`, `key`, `action`, `removed` |
| `config_properties_list` | Read-only | `path` | `path`, `entries` |

Example:

```json
{
  "name": "config_properties_set",
  "arguments": {
    "path": "server.properties",
    "key": "motd",
    "value": "Welcome to mcAI"
  }
}
```

`action` is `created`, `replaced`, `removed`, or `missing` depending on the operation.

## JSON Tools

JSON tools use JSON Pointer style paths.

| Tool | Access | Required arguments | Result |
| --- | --- | --- | --- |
| `config_json_get` | Read-only | `path`, `pointer` | `path`, `pointer`, `value`, `found` |
| `config_json_set` | Write | `path`, `pointer`, `value` | `path`, `pointer`, `action` |
| `config_json_remove` | Write | `path`, `pointer` | `path`, `pointer`, `action` |
| `config_json_append` | Write | `path`, `pointer`, `value` | `path`, `pointer`, `action` |

Pointer notes:

- `""` refers to the document root for get/set.
- Non-root pointers must start with `/`.
- `~1` decodes to `/`; `~0` decodes to `~`.
- `config_json_set` can create missing object nodes.
- Array indices must already exist for nested set/remove/append operations.
- `config_json_append` requires the pointer target to be an array.

Example:

```json
{
  "name": "config_json_set",
  "arguments": {
    "path": "plugins/example/config.json",
    "pointer": "/settings/maxPlayers",
    "value": 20
  }
}
```

Append example:

```json
{
  "name": "config_json_append",
  "arguments": {
    "path": "plugins/example/config.json",
    "pointer": "/players",
    "value": "Steve"
  }
}
```

## Console Tool

| Tool | Access | Required arguments | Result |
| --- | --- | --- | --- |
| `console_send_command` | Write | `command` | `dispatched`, `capturedLines` |

The command should not include a leading slash.

Example:

```json
{
  "name": "console_send_command",
  "arguments": {
    "command": "say mcAI smoke test"
  }
}
```

`capturedLines` contains new non-blank lines read from `logs/latest.log` after the command dispatch checkpoint. The capture window is controlled by `limits.commandCaptureMillis`.

