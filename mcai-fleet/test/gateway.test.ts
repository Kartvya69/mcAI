import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { WebSocketServer } from "ws";

import type { FleetConfig, FleetServerConfig } from "../src/config.js";
import { FleetGateway } from "../src/gateway.js";

describe("fleet gateway server status", () => {
  it("actively reports a server connected when websocket handshake succeeds before routed tool calls", async () => {
    const mock = await withAuthenticatedServer();
    const gateway = new FleetGateway(fleetConfig([serverConfig("survival", mock.url)]));

    try {
      const result = await gateway.serverStatus("survival");

      assert.deepEqual(result, {
        server: {
          id: "survival",
          name: "Survival",
          url: mock.url,
          connected: true,
        },
      });
      assert.equal(mock.connections(), 1);
      assert.equal(mock.messages(), 0);
      assert.equal(JSON.stringify(result).includes("secret"), false);
    } finally {
      gateway.close();
      await mock.close();
    }
  });

  it("reports offline and auth-rejected servers disconnected without failing all-server status", async () => {
    const online = await withAuthenticatedServer();
    const rejected = await withAuthenticatedServer({ rejectAuth: true });
    const gateway = new FleetGateway(
      fleetConfig([
        serverConfig("online", online.url, "Online"),
        serverConfig("offline", "ws://127.0.0.1:9/mcp/ws", "Offline"),
        serverConfig("rejected", rejected.url, "Rejected"),
      ]),
      200,
    );

    try {
      const result = await gateway.serverStatus();

      assert.deepEqual(result, {
        servers: [
          {
            id: "online",
            name: "Online",
            url: online.url,
            connected: true,
          },
          {
            id: "offline",
            name: "Offline",
            url: "ws://127.0.0.1:9/mcp/ws",
            connected: false,
          },
          {
            id: "rejected",
            name: "Rejected",
            url: rejected.url,
            connected: false,
          },
        ],
      });
      assert.equal(JSON.stringify(result).includes("secret"), false);
    } finally {
      gateway.close();
      await online.close();
      await rejected.close();
    }
  });
});

function fleetConfig(servers: FleetServerConfig[], requestTimeoutMillis = 500): FleetConfig {
  return { requestTimeoutMillis, servers };
}

function serverConfig(id: string, url: string, name = "Survival"): FleetServerConfig {
  return { id, name, url, token: "secret" };
}

async function withAuthenticatedServer(options: { rejectAuth?: boolean } = {}): Promise<{
  url: string;
  connections: () => number;
  messages: () => number;
  close: () => Promise<void>;
}> {
  let connectionCount = 0;
  let messageCount = 0;
  const server = new WebSocketServer({
    port: 0,
    verifyClient: (info, done) => {
      const authorized = info.req.headers.authorization === "Bearer secret";
      done(authorized && !options.rejectAuth, 401, "Unauthorized");
    },
  });
  server.on("connection", (socket) => {
    connectionCount += 1;
    socket.on("message", () => {
      messageCount += 1;
    });
  });
  await onceListening(server);
  const port = (server.address() as { port: number }).port;
  return {
    url: `ws://127.0.0.1:${port}/mcp/ws`,
    connections: () => connectionCount,
    messages: () => messageCount,
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
