import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { FLEET_TOOL_DEFINITIONS, ROUTED_MCAI_TOOL_NAMES } from "../src/tools.js";

describe("fleet MCP tool definitions", () => {
  it("adds required serverId to every routed mcAI tool schema", () => {
    for (const toolName of ROUTED_MCAI_TOOL_NAMES) {
      const definition = FLEET_TOOL_DEFINITIONS.find((tool) => tool.name === toolName);

      assert.ok(definition, `missing definition for ${toolName}`);
      assert.ok(definition.inputSchema.shape.serverId, `${toolName} is missing serverId`);
      assert.throws(() => definition.inputSchema.parse({}), /serverId/);
    }
  });

  it("includes fleet discovery tools", () => {
    assert.ok(FLEET_TOOL_DEFINITIONS.some((tool) => tool.name === "server_list"));
    assert.ok(FLEET_TOOL_DEFINITIONS.some((tool) => tool.name === "server_status"));
  });

  it("describes console commands as ordinary commands with bounded latest log capture", () => {
    const definition = FLEET_TOOL_DEFINITIONS.find((tool) => tool.name === "console_send_command");

    assert.ok(definition);
    assert.match(definition.description, /ordinary Minecraft commands/i);
    assert.match(definition.description, /without a leading slash/i);
    assert.match(definition.description, /bounded logs\/latest\.log capture/i);
    assert.match(definition.inputSchema.shape.command.description ?? "", /without a leading slash/i);
    assert.match(definition.inputSchema.shape.command.description ?? "", /not synchronous stdout/i);
  });

  it("describes power actions as the preferred stop and restart path", () => {
    const definition = FLEET_TOOL_DEFINITIONS.find((tool) => tool.name === "power_actions");

    assert.ok(definition);
    assert.match(definition.description, /preferred/i);
    assert.match(definition.description, /stop\/restart/i);
    assert.match(definition.description, /native Bukkit\/Paper APIs/i);
  });
});
