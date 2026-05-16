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
});
