import type { FleetConfig } from "./config.js";
import { McAiFleetClient, type JsonObject } from "./client.js";
import { ServerRegistry } from "./registry.js";

export class FleetGateway {
  private readonly registry: ServerRegistry;
  private readonly clients = new Map<string, McAiFleetClient>();

  constructor(private readonly config: FleetConfig) {
    this.registry = new ServerRegistry(config.servers);
  }

  listServers(): JsonObject {
    return {
      servers: this.registry.list().map((server) => ({
        ...server,
        connected: this.clients.get(server.id)?.isConnected ?? false,
      })),
    };
  }

  serverStatus(serverId?: string): JsonObject {
    if (serverId) {
      const server = this.registry.get(serverId);
      return {
        server: {
          id: server.id,
          name: server.name,
          url: server.url,
          connected: this.clients.get(server.id)?.isConnected ?? false,
        },
      };
    }
    return this.listServers();
  }

  async callTool(serverId: string, tool: string, args: JsonObject): Promise<JsonObject> {
    const client = this.clientFor(serverId);
    return client.callTool(tool, args);
  }

  close(): void {
    for (const client of this.clients.values()) {
      client.close();
    }
    this.clients.clear();
  }

  private clientFor(serverId: string): McAiFleetClient {
    const existing = this.clients.get(serverId);
    if (existing) return existing;

    const server = this.registry.get(serverId);
    const client = new McAiFleetClient(server, { requestTimeoutMillis: this.config.requestTimeoutMillis });
    this.clients.set(serverId, client);
    return client;
  }
}
