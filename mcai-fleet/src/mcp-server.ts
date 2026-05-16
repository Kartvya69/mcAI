import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { ZodError } from "zod";

import { FleetToolError, type JsonObject } from "./client.js";
import type { FleetGateway } from "./gateway.js";
import { FLEET_TOOL_DEFINITIONS } from "./tools.js";

export function createFleetMcpServer(gateway: FleetGateway): McpServer {
  const server = new McpServer(
    {
      name: "mcAI-fleet",
      version: "0.1.0",
    },
    {
      capabilities: {
        tools: {},
      },
    },
  );

  for (const definition of FLEET_TOOL_DEFINITIONS) {
    server.registerTool(
      definition.name,
      {
        title: definition.name,
        description: definition.description,
        inputSchema: definition.inputSchema,
        annotations: {
          readOnlyHint: definition.readOnly,
          openWorldHint: false,
        },
      },
      async (rawArgs: unknown) => {
        try {
          const args = definition.inputSchema.parse(rawArgs);
          if (definition.name === "server_list") {
            return toolResult(gateway.listServers());
          }
          if (definition.name === "server_status") {
            return toolResult(gateway.serverStatus((args as { serverId?: string }).serverId));
          }

          const { serverId, ...toolArgs } = args as { serverId: string } & JsonObject;
          return toolResult(await gateway.callTool(serverId, definition.name, toolArgs));
        } catch (error) {
          return toolErrorResult(error);
        }
      },
    );
  }

  return server;
}

function toolResult(structuredContent: JsonObject): CallToolResult {
  return {
    content: [{ type: "text", text: JSON.stringify(structuredContent) }],
    structuredContent,
  };
}

function toolErrorResult(error: unknown): CallToolResult {
  const payload = errorPayload(error);
  return {
    isError: true,
    content: [{ type: "text", text: JSON.stringify(payload) }],
    structuredContent: payload,
  };
}

function errorPayload(error: unknown): JsonObject {
  if (error instanceof FleetToolError) {
    return {
      error: {
        type: error.type,
        message: error.message,
        serverId: error.serverId,
      },
    };
  }
  if (error instanceof ZodError) {
    return {
      error: {
        type: "ValidationError",
        message: error.issues.map((issue) => issue.message).join("; "),
      },
    };
  }
  if (error instanceof Error) {
    return {
      error: {
        type: error.name || "Error",
        message: error.message,
      },
    };
  }
  return {
    error: {
      type: "Error",
      message: "Unknown fleet error",
    },
  };
}
