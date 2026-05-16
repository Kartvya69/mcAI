import type { FleetServerConfig } from "./config.js";

export type FleetServerSummary = Omit<FleetServerConfig, "token">;

export class ServerRegistry {
  private readonly servers = new Map<string, FleetServerConfig>();

  constructor(servers: FleetServerConfig[]) {
    for (const server of servers) {
      this.servers.set(server.id, server);
    }
  }

  get(serverId: string): FleetServerConfig {
    const server = this.servers.get(serverId);
    if (!server) {
      throw new Error(`Unknown serverId: ${serverId}`);
    }
    return server;
  }

  list(): FleetServerSummary[] {
    return [...this.servers.values()].map(({ token: _token, ...summary }) => summary);
  }
}
