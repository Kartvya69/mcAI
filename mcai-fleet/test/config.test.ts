import assert from "node:assert/strict";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";

import { loadFleetConfig } from "../src/config.js";
import { FleetGateway } from "../src/gateway.js";
import { ServerRegistry } from "../src/registry.js";

describe("fleet config", () => {
  it("loads and validates server definitions", async () => {
    const dir = await mkdtemp(join(tmpdir(), "mcai-fleet-config-"));
    const file = join(dir, "mcai-fleet.config.json");
    await writeFile(
      file,
      JSON.stringify({
        requestTimeoutMillis: 750,
        servers: [
          {
            id: "survival",
            name: "Survival",
            url: "ws://127.0.0.1:25577/mcp/ws",
            token: "secret",
          },
        ],
      }),
    );

    const config = await loadFleetConfig(file);

    assert.equal(config.requestTimeoutMillis, 750);
    assert.deepEqual(config.servers, [
      {
        id: "survival",
        name: "Survival",
        url: "ws://127.0.0.1:25577/mcp/ws",
        token: "secret",
      },
    ]);
  });

  it("rejects duplicate ids and non websocket URLs", async () => {
    const dir = await mkdtemp(join(tmpdir(), "mcai-fleet-invalid-"));
    const file = join(dir, "mcai-fleet.config.json");
    await writeFile(
      file,
      JSON.stringify({
        servers: [
          { id: "same", name: "One", url: "ws://127.0.0.1:25577/mcp/ws", token: "a" },
          { id: "same", name: "Two", url: "http://127.0.0.1:25577/mcp/ws", token: "b" },
        ],
      }),
    );

    await assert.rejects(() => loadFleetConfig(file), /Duplicate server id: same/);

    const httpOnly = join(dir, "http-only.json");
    await writeFile(
      httpOnly,
      JSON.stringify({
        servers: [{ id: "panel", name: "Panel", url: "http://127.0.0.1:25577/mcp/ws", token: "secret" }],
      }),
    );

    await assert.rejects(() => loadFleetConfig(httpOnly), /must use ws:\/\/ or wss:\/\//);
  });
});

describe("server registry", () => {
  it("looks up configured servers and rejects unknown ids", () => {
    const registry = new ServerRegistry([
      { id: "survival", name: "Survival", url: "ws://127.0.0.1:25577/mcp/ws", token: "secret" },
    ]);

    assert.equal(registry.get("survival").name, "Survival");
    assert.deepEqual(registry.list(), [
      { id: "survival", name: "Survival", url: "ws://127.0.0.1:25577/mcp/ws" },
    ]);
    assert.throws(() => registry.get("unknown"), /Unknown serverId: unknown/);
  });

  it("fails routed calls before dialing when serverId is unknown", async () => {
    const gateway = new FleetGateway({
      requestTimeoutMillis: 100,
      servers: [{ id: "survival", name: "Survival", url: "ws://127.0.0.1:25577/mcp/ws", token: "secret" }],
    });

    await assert.rejects(() => gateway.callTool("unknown", "fs_read_file", { path: "server.properties" }), /Unknown serverId: unknown/);
  });
});
