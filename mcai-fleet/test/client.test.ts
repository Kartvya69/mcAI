import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { WebSocketServer, type WebSocket } from "ws";

import { FleetToolError, McAiFleetClient } from "../src/client.js";
import type { FleetServerConfig } from "../src/config.js";

describe("mcAI websocket client", () => {
  it("routes a tool call to a server websocket with bearer auth", async () => {
    const requests: unknown[] = [];
    const authHeaders: Array<string | undefined> = [];
    const mock = await withMockServer((message, socket, authHeader) => {
      requests.push(message);
      authHeaders.push(authHeader);
      socket.send(JSON.stringify({ id: message.id, ok: true, result: { path: "server.properties", content: "hello" } }));
    });
    const client = new McAiFleetClient(serverConfig(mock.url), { requestTimeoutMillis: 500 });

    const result = await client.callTool("fs_read_file", { path: "server.properties" });

    assert.deepEqual(result, { path: "server.properties", content: "hello" });
    assert.deepEqual(requests, [{ id: "1", tool: "fs_read_file", arguments: { path: "server.properties" } }]);
    assert.deepEqual(authHeaders, ["Bearer secret"]);

    client.close();
    await mock.close();
  });

  it("raises structured tool errors returned by the server", async () => {
    const mock = await withMockServer((message, socket) => {
      socket.send(
        JSON.stringify({
          id: message.id,
          ok: false,
          error: { type: "IllegalArgumentException", message: "bad path" },
        }),
      );
    });
    const client = new McAiFleetClient(serverConfig(mock.url), { requestTimeoutMillis: 500 });

    await assert.rejects(
      () => client.callTool("fs_read_file", { path: "../server.properties" }),
      (error) => error instanceof FleetToolError && error.type === "IllegalArgumentException" && /bad path/.test(error.message),
    );

    client.close();
    await mock.close();
  });

  it("fails cleanly when a server is offline or times out", async () => {
    const timeoutServer = await withMockServer(() => {
      // Intentionally do not respond.
    });
    const timeoutClient = new McAiFleetClient(serverConfig(timeoutServer.url), { requestTimeoutMillis: 100 });

    await assert.rejects(() => timeoutClient.callTool("fs_read_file", { path: "server.properties" }), /timed out/);

    timeoutClient.close();
    await timeoutServer.close();

    const offlineClient = new McAiFleetClient(serverConfig("ws://127.0.0.1:9/mcp/ws"), { requestTimeoutMillis: 100 });
    await assert.rejects(() => offlineClient.callTool("fs_read_file", { path: "server.properties" }), /offline|ECONNREFUSED|connect/i);
    offlineClient.close();
  });

  it("fails cleanly when websocket auth is rejected", async () => {
    const server = new WebSocketServer({
      port: 0,
      verifyClient: (_info, done) => done(false, 401, "Unauthorized"),
    });
    await onceListening(server);
    const port = (server.address() as { port: number }).port;
    const client = new McAiFleetClient(serverConfig(`ws://127.0.0.1:${port}/mcp/ws`), { requestTimeoutMillis: 500 });

    await assert.rejects(() => client.callTool("fs_read_file", { path: "server.properties" }), /401|Unauthorized|auth/i);

    client.close();
    await closeServer(server);
  });
});

function serverConfig(url: string): FleetServerConfig {
  return { id: "survival", name: "Survival", url, token: "secret" };
}

async function withMockServer(
  onMessage: (
    message: { id: string; tool: string; arguments: Record<string, unknown> },
    socket: WebSocket,
    authHeader: string | undefined,
  ) => void,
): Promise<{ url: string; close: () => Promise<void> }> {
  const server = new WebSocketServer({ port: 0 });
  server.on("connection", (socket, request) => {
    socket.on("message", (raw) => {
      onMessage(JSON.parse(raw.toString()), socket, request.headers.authorization);
    });
  });
  await onceListening(server);
  const port = (server.address() as { port: number }).port;
  return {
    url: `ws://127.0.0.1:${port}/mcp/ws`,
    close: () => closeServer(server),
  };
}

function onceListening(server: WebSocketServer): Promise<void> {
  return new Promise((resolve) => server.once("listening", resolve));
}

function closeServer(server: WebSocketServer): Promise<void> {
  return new Promise((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()));
  });
}
