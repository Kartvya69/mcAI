import { readFile } from "node:fs/promises";
import { z } from "zod";

const ServerConfigSchema = z
  .object({
    id: z.string().min(1),
    name: z.string().min(1),
    url: z.string().url(),
    token: z.string().min(1),
  })
  .strict();

const FleetConfigSchema = z
  .object({
    requestTimeoutMillis: z.number().int().positive().default(5_000),
    servers: z.array(ServerConfigSchema).min(1),
  })
  .strict();

export type FleetServerConfig = z.infer<typeof ServerConfigSchema>;

export type FleetConfig = z.infer<typeof FleetConfigSchema>;

export async function loadFleetConfig(path: string): Promise<FleetConfig> {
  const raw = JSON.parse(await readFile(path, "utf8")) as unknown;
  const config = FleetConfigSchema.parse(raw);
  validateServerDefinitions(config.servers);
  return config;
}

function validateServerDefinitions(servers: FleetServerConfig[]): void {
  const seen = new Set<string>();
  for (const server of servers) {
    if (seen.has(server.id)) {
      throw new Error(`Duplicate server id: ${server.id}`);
    }
    seen.add(server.id);

    const protocol = new URL(server.url).protocol;
    if (protocol !== "ws:" && protocol !== "wss:") {
      throw new Error(`Server ${server.id} url must use ws:// or wss://`);
    }
  }
}
