import { z } from "zod";

export type FleetToolDefinition = {
  name: string;
  description: string;
  inputSchema: z.AnyZodObject;
  routed: boolean;
  readOnly: boolean;
};

const serverId = z.string().min(1).describe("Configured mcAI fleet server id.");
const path = z.string().min(1).describe("Relative path under the Minecraft server root.");
const optionalPath = z.string().min(1).optional();
const encoding = z.enum(["text", "base64"]).optional();
const jsonValue = z.unknown();

function routedTool(
  name: string,
  description: string,
  readOnly: boolean,
  shape: z.ZodRawShape,
): FleetToolDefinition {
  return {
    name,
    description,
    routed: true,
    readOnly,
    inputSchema: z.object({ serverId, ...shape }).strict(),
  };
}

function fleetTool(name: string, description: string, shape: z.ZodRawShape = {}): FleetToolDefinition {
  return {
    name,
    description,
    routed: false,
    readOnly: true,
    inputSchema: z.object(shape).strict(),
  };
}

export const ROUTED_MCAI_TOOL_NAMES = [
  "fs_read_file",
  "fs_read_many_files",
  "fs_write_file",
  "fs_edit_file",
  "fs_append_file",
  "fs_download_file",
  "fs_list_directory",
  "fs_directory_tree",
  "fs_create_directory",
  "fs_move",
  "fs_copy",
  "fs_delete",
  "fs_stat",
  "fs_search_files",
  "fs_find_paths",
  "fs_search_content",
  "fs_tail_file",
  "config_properties_get",
  "config_properties_set",
  "config_properties_remove",
  "config_properties_list",
  "config_json_get",
  "config_json_set",
  "config_json_remove",
  "config_json_append",
  "console_send_command",
  "power_actions",
] as const;

export const FLEET_TOOL_DEFINITIONS: FleetToolDefinition[] = [
  fleetTool("server_list", "List configured Minecraft servers without exposing bearer tokens."),
  fleetTool("server_status", "Show fleet connection status for one server or all configured servers.", {
    serverId: z.string().min(1).optional(),
  }),
  routedTool("fs_read_file", "Read a text or base64 file under the Minecraft server root.", true, {
    path,
    encoding,
    offset: z.number().int().nonnegative().optional(),
    length: z.number().int().nonnegative().optional(),
  }),
  routedTool("fs_read_many_files", "Read multiple files under the Minecraft server root.", true, {
    paths: z.array(path).min(1),
    encoding,
    maxBytesPerFile: z.number().int().positive().optional(),
  }),
  routedTool("fs_write_file", "Write a text or base64 file under the Minecraft server root.", false, {
    path,
    content: z.string(),
    encoding,
    createParents: z.boolean().optional(),
  }),
  routedTool("fs_edit_file", "Replace literal text in a file under the Minecraft server root.", false, {
    path,
    oldText: z.string().min(1),
    newText: z.string(),
    replaceAll: z.boolean().optional(),
  }),
  routedTool("fs_append_file", "Append text or base64 content to a file under the Minecraft server root.", false, {
    path,
    content: z.string(),
    encoding,
    createParents: z.boolean().optional(),
  }),
  routedTool("fs_download_file", "Download an HTTP or HTTPS URL into a file under the Minecraft server root.", false, {
    url: z.string().url(),
    path,
    overwrite: z.boolean().optional(),
    createParents: z.boolean().optional(),
    sha256: z.string().optional(),
  }),
  routedTool("fs_list_directory", "List a directory under the Minecraft server root.", true, {
    path: optionalPath,
  }),
  routedTool("fs_directory_tree", "Return a bounded recursive directory tree.", true, {
    path: optionalPath,
    maxDepth: z.number().int().nonnegative().optional(),
  }),
  routedTool("fs_create_directory", "Create a directory under the Minecraft server root.", false, { path }),
  routedTool("fs_move", "Move a file or directory under the Minecraft server root.", false, {
    source: path,
    destination: path,
    overwrite: z.boolean().optional(),
    createParents: z.boolean().optional(),
  }),
  routedTool("fs_copy", "Copy a file under the Minecraft server root.", false, {
    source: path,
    destination: path,
    overwrite: z.boolean().optional(),
    createParents: z.boolean().optional(),
  }),
  routedTool("fs_delete", "Delete a file or directory under the Minecraft server root.", false, {
    path,
    recursive: z.boolean().optional(),
  }),
  routedTool("fs_stat", "Return file metadata under the Minecraft server root.", true, { path }),
  routedTool("fs_search_files", "Search file paths by substring and glob under the Minecraft server root.", true, {
    path: optionalPath,
    query: z.string().min(1),
    glob: z.string().optional(),
    maxResults: z.number().int().positive().optional(),
  }),
  routedTool("fs_find_paths", "Search indexed file paths under the Minecraft server root with freshness metadata.", true, {
    query: z.string().min(1),
    path: optionalPath,
    glob: z.string().optional(),
    maxResults: z.number().int().positive().optional(),
    includeDirectories: z.boolean().optional(),
    useIndex: z.boolean().optional(),
  }),
  routedTool("fs_search_content", "Search text file content by substring or regex under the Minecraft server root.", true, {
    path: optionalPath,
    query: z.string().min(1),
    regex: z.boolean().optional(),
    glob: z.string().optional(),
    maxResults: z.number().int().positive().optional(),
  }),
  routedTool("fs_tail_file", "Read the last lines of a file under the Minecraft server root.", true, {
    path,
    lines: z.number().int().positive().optional(),
  }),
  routedTool("config_properties_get", "Read one key from a Java .properties file under the Minecraft server root.", true, {
    path,
    key: z.string().min(1),
  }),
  routedTool("config_properties_set", "Set one key in a Java .properties file under the Minecraft server root while preserving comments.", false, {
    path,
    key: z.string().min(1),
    value: z.string(),
  }),
  routedTool("config_properties_remove", "Remove one key from a Java .properties file under the Minecraft server root.", false, {
    path,
    key: z.string().min(1),
  }),
  routedTool("config_properties_list", "List keys from a Java .properties file under the Minecraft server root.", true, { path }),
  routedTool("config_json_get", "Read a JSON value by JSON pointer from a file under the Minecraft server root.", true, {
    path,
    pointer: z.string(),
  }),
  routedTool("config_json_set", "Set a JSON value by JSON pointer in a file under the Minecraft server root.", false, {
    path,
    pointer: z.string(),
    value: jsonValue,
  }),
  routedTool("config_json_remove", "Remove a JSON value by JSON pointer from a file under the Minecraft server root.", false, {
    path,
    pointer: z.string(),
  }),
  routedTool("config_json_append", "Append a JSON value to an array selected by JSON pointer in a file under the Minecraft server root.", false, {
    path,
    pointer: z.string(),
    value: jsonValue,
  }),
  routedTool("console_send_command", "Dispatch a Minecraft console command and return captured latest.log lines.", false, {
    command: z.string().min(1),
  }),
  routedTool("power_actions", "Stop or restart the Minecraft server through native Bukkit/Paper APIs.", false, {
    action: z.enum(["stop", "restart"]),
    reason: z.string().optional(),
    delaySeconds: z.number().int().min(0).max(600).optional(),
  }),
];
