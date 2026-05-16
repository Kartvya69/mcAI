#!/usr/bin/env node
import { join } from "node:path";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

import { loadFleetConfig } from "./config.js";
import { FleetGateway } from "./gateway.js";
import { createFleetMcpServer } from "./mcp-server.js";

const configPath = process.env.MCAI_FLEET_CONFIG ?? join(process.cwd(), "mcai-fleet.config.json");
const config = await loadFleetConfig(configPath);
const gateway = new FleetGateway(config);
const server = createFleetMcpServer(gateway);

process.once("SIGINT", async () => {
  gateway.close();
  await server.close();
  process.exit(0);
});

process.once("SIGTERM", async () => {
  gateway.close();
  await server.close();
  process.exit(0);
});

await server.connect(new StdioServerTransport());
