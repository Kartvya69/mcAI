package dev.mcai

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.logging.Logger

class McAiMcpServerFactory(
    private val fs: FileSystemTools,
    private val console: ConsoleTools,
    private val powerActions: PowerActions,
    private val version: String,
    private val logger: Logger = Logger.getLogger(McAiMcpServerFactory::class.java.name),
    private val verboseLogging: Boolean = false,
) {
    fun callTool(name: String, arguments: JsonObject = JsonObject(emptyMap())): McAiToolCallResult {
        if (verboseLogging) {
            logger.info("MCP tool call: $name")
        }
        return try {
            McAiToolCallResult.Success(executeTool(name, arguments))
        } catch (throwable: Throwable) {
            logger.warning("MCP tool error in $name: ${throwable.message}")
            McAiToolCallResult.Error(
                buildJsonObject {
                    putJsonObject("error") {
                        put("type", throwable::class.simpleName ?: "Throwable")
                        put("message", throwable.message ?: "")
                    }
                },
                "${throwable::class.simpleName}: ${throwable.message}",
            )
        }
    }

    private fun executeTool(name: String, arguments: JsonObject): JsonObject =
        when (name) {
            "fs_read_file" -> encodeToolResult(fs.readFile(ReadFileRequest(arguments.requiredString("path"), arguments.string("encoding", "text"), arguments.long("offset", 0), arguments.intOrNull("length"))))
            "fs_read_many_files" -> encodeToolResult(fs.readManyFiles(ReadManyFilesRequest(arguments.requiredStringArray("paths"), arguments.string("encoding", "text"), arguments.intOrNull("maxBytesPerFile"))))
            "fs_write_file" -> encodeToolResult(fs.writeFile(WriteFileRequest(arguments.requiredString("path"), arguments.requiredString("content"), arguments.string("encoding", "text"), arguments.bool("createParents", false))))
            "fs_edit_file" -> encodeToolResult(fs.editFile(EditFileRequest(arguments.requiredString("path"), arguments.requiredString("oldText"), arguments.requiredString("newText"), arguments.bool("replaceAll", false))))
            "fs_append_file" -> encodeToolResult(fs.appendFile(AppendFileRequest(arguments.requiredString("path"), arguments.requiredString("content"), arguments.string("encoding", "text"), arguments.bool("createParents", false))))
            "fs_download_file" -> encodeToolResult(
                fs.downloadFile(
                    DownloadFileRequest(
                        url = arguments.requiredString("url"),
                        path = arguments.requiredString("path"),
                        overwrite = arguments.bool("overwrite", true),
                        createParents = arguments.bool("createParents", false),
                        sha256 = arguments.stringOrNull("sha256"),
                    ),
                ),
            )
            "fs_list_directory" -> encodeToolResult(fs.listDirectory(ListDirectoryRequest(arguments.string("path", "."))))
            "fs_directory_tree" -> encodeToolResult(fs.directoryTree(DirectoryTreeRequest(arguments.string("path", "."), arguments.int("maxDepth", 10))))
            "fs_create_directory" -> encodeToolResult(fs.createDirectory(CreateDirectoryRequest(arguments.requiredString("path"))))
            "fs_move" -> encodeToolResult(fs.move(MoveRequest(arguments.requiredString("source"), arguments.requiredString("destination"), arguments.bool("overwrite", true), arguments.bool("createParents", false))))
            "fs_copy" -> encodeToolResult(fs.copy(CopyRequest(arguments.requiredString("source"), arguments.requiredString("destination"), arguments.bool("overwrite", true), arguments.bool("createParents", false))))
            "fs_delete" -> encodeToolResult(fs.delete(DeleteRequest(arguments.requiredString("path"), arguments.bool("recursive", false))))
            "fs_stat" -> encodeToolResult(fs.stat(StatRequest(arguments.requiredString("path"))))
            "fs_search_files" -> encodeToolResult(fs.searchFiles(SearchFilesRequest(arguments.string("path", "."), arguments.requiredString("query"), arguments.string("glob", "**/*"), arguments.int("maxResults", 100))))
            "fs_find_paths" -> encodeToolResult(
                fs.findPaths(
                    FindPathsRequest(
                        query = arguments.requiredString("query"),
                        path = arguments.string("path", "."),
                        glob = arguments.string("glob", "**/*"),
                        maxResults = arguments.int("maxResults", 100),
                        includeDirectories = arguments.bool("includeDirectories", false),
                        useIndex = arguments.bool("useIndex", true),
                    ),
                ),
            )
            "fs_search_content" -> encodeToolResult(fs.searchContent(SearchContentRequest(arguments.string("path", "."), arguments.requiredString("query"), arguments.bool("regex", false), arguments.string("glob", "**/*"), arguments.int("maxResults", 100))))
            "fs_tail_file" -> encodeToolResult(fs.tailFile(TailFileRequest(arguments.requiredString("path"), arguments.int("lines", 100))))
            "config_properties_get" -> encodeToolResult(fs.configPropertiesGet(ConfigPropertiesGetRequest(arguments.requiredString("path"), arguments.requiredString("key"))))
            "config_properties_set" -> encodeToolResult(fs.configPropertiesSet(ConfigPropertiesSetRequest(arguments.requiredString("path"), arguments.requiredString("key"), arguments.requiredString("value"))))
            "config_properties_remove" -> encodeToolResult(fs.configPropertiesRemove(ConfigPropertiesRemoveRequest(arguments.requiredString("path"), arguments.requiredString("key"))))
            "config_properties_list" -> encodeToolResult(fs.configPropertiesList(ConfigPropertiesListRequest(arguments.requiredString("path"))))
            "config_json_get" -> encodeToolResult(fs.configJsonGet(ConfigJsonGetRequest(arguments.requiredString("path"), arguments.requiredString("pointer"))))
            "config_json_set" -> encodeToolResult(fs.configJsonSet(ConfigJsonSetRequest(arguments.requiredString("path"), arguments.requiredString("pointer"), arguments.requiredJson("value"))))
            "config_json_remove" -> encodeToolResult(fs.configJsonRemove(ConfigJsonRemoveRequest(arguments.requiredString("path"), arguments.requiredString("pointer"))))
            "config_json_append" -> encodeToolResult(fs.configJsonAppend(ConfigJsonAppendRequest(arguments.requiredString("path"), arguments.requiredString("pointer"), arguments.requiredJson("value"))))
            "console_send_command" -> encodeToolResult(console.sendCommand(arguments.requiredString("command")))
            "power_actions" -> encodeToolResult(
                powerActions.perform(
                    action = arguments.requiredString("action"),
                    reason = arguments.stringOrNull("reason"),
                    delaySeconds = arguments.int("delaySeconds", 0),
                ),
            )
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }

    fun create(): Server {
        val server = Server(
            Implementation(name = "mcAI", version = version),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    logging = ServerCapabilities.Logging,
                ),
            ),
            MCAI_MCP_SERVER_INSTRUCTIONS,
        )

        server.register("fs_read_file", "Read a text or base64 file under the Minecraft server root.", readOnly = true, schema = fileReadSchema()) {
            fs.readFile(ReadFileRequest(it.requiredString("path"), it.string("encoding", "text"), it.long("offset", 0), it.intOrNull("length")))
        }
        server.register("fs_read_many_files", "Read multiple files under the Minecraft server root.", readOnly = true, schema = readManySchema()) {
            fs.readManyFiles(ReadManyFilesRequest(it.requiredStringArray("paths"), it.string("encoding", "text"), it.intOrNull("maxBytesPerFile")))
        }
        server.register("fs_write_file", "Write a text or base64 file under the Minecraft server root.", readOnly = false, schema = writeSchema()) {
            fs.writeFile(WriteFileRequest(it.requiredString("path"), it.requiredString("content"), it.string("encoding", "text"), it.bool("createParents", false)))
        }
        server.register("fs_edit_file", "Replace literal text in a file under the Minecraft server root.", readOnly = false, schema = editSchema()) {
            fs.editFile(EditFileRequest(it.requiredString("path"), it.requiredString("oldText"), it.requiredString("newText"), it.bool("replaceAll", false)))
        }
        server.register("fs_append_file", "Append text or base64 content to a file under the Minecraft server root.", readOnly = false, schema = writeSchema()) {
            fs.appendFile(AppendFileRequest(it.requiredString("path"), it.requiredString("content"), it.string("encoding", "text"), it.bool("createParents", false)))
        }
        server.register("fs_download_file", "Download an HTTP or HTTPS URL into a file under the Minecraft server root.", readOnly = false, schema = downloadSchema()) {
            fs.downloadFile(
                DownloadFileRequest(
                    url = it.requiredString("url"),
                    path = it.requiredString("path"),
                    overwrite = it.bool("overwrite", true),
                    createParents = it.bool("createParents", false),
                    sha256 = it.stringOrNull("sha256"),
                ),
            )
        }
        server.register("fs_list_directory", "List a directory under the Minecraft server root.", readOnly = true, schema = pathOnlySchema(required = false)) {
            fs.listDirectory(ListDirectoryRequest(it.string("path", ".")))
        }
        server.register("fs_directory_tree", "Return a bounded recursive directory tree.", readOnly = true, schema = treeSchema()) {
            fs.directoryTree(DirectoryTreeRequest(it.string("path", "."), it.int("maxDepth", 10)))
        }
        server.register("fs_create_directory", "Create a directory under the Minecraft server root.", readOnly = false, schema = pathOnlySchema()) {
            fs.createDirectory(CreateDirectoryRequest(it.requiredString("path")))
        }
        server.register("fs_move", "Move a file or directory under the Minecraft server root.", readOnly = false, schema = sourceDestinationSchema()) {
            fs.move(MoveRequest(it.requiredString("source"), it.requiredString("destination"), it.bool("overwrite", true), it.bool("createParents", false)))
        }
        server.register("fs_copy", "Copy a file under the Minecraft server root.", readOnly = false, schema = sourceDestinationSchema()) {
            fs.copy(CopyRequest(it.requiredString("source"), it.requiredString("destination"), it.bool("overwrite", true), it.bool("createParents", false)))
        }
        server.register("fs_delete", "Delete a file or directory under the Minecraft server root.", readOnly = false, schema = deleteSchema()) {
            fs.delete(DeleteRequest(it.requiredString("path"), it.bool("recursive", false)))
        }
        server.register("fs_stat", "Return file metadata under the Minecraft server root.", readOnly = true, schema = pathOnlySchema()) {
            fs.stat(StatRequest(it.requiredString("path")))
        }
        server.register("fs_search_files", "Search file paths by substring and glob under the Minecraft server root.", readOnly = true, schema = searchFilesSchema()) {
            fs.searchFiles(SearchFilesRequest(it.string("path", "."), it.requiredString("query"), it.string("glob", "**/*"), it.int("maxResults", 100)))
        }
        server.register("fs_find_paths", "Search indexed file paths under the Minecraft server root with freshness metadata.", readOnly = true, schema = findPathsSchema()) {
            fs.findPaths(
                FindPathsRequest(
                    query = it.requiredString("query"),
                    path = it.string("path", "."),
                    glob = it.string("glob", "**/*"),
                    maxResults = it.int("maxResults", 100),
                    includeDirectories = it.bool("includeDirectories", false),
                    useIndex = it.bool("useIndex", true),
                ),
            )
        }
        server.register("fs_search_content", "Search text file content by substring or regex under the Minecraft server root.", readOnly = true, schema = searchContentSchema()) {
            fs.searchContent(SearchContentRequest(it.string("path", "."), it.requiredString("query"), it.bool("regex", false), it.string("glob", "**/*"), it.int("maxResults", 100)))
        }
        server.register("fs_tail_file", "Read the last lines of a file under the Minecraft server root.", readOnly = true, schema = tailSchema()) {
            fs.tailFile(TailFileRequest(it.requiredString("path"), it.int("lines", 100)))
        }
        server.register("config_properties_get", "Read one key from a Java .properties file under the Minecraft server root.", readOnly = true, schema = propertiesKeySchema()) {
            fs.configPropertiesGet(ConfigPropertiesGetRequest(it.requiredString("path"), it.requiredString("key")))
        }
        server.register("config_properties_set", "Set one key in a Java .properties file under the Minecraft server root while preserving comments.", readOnly = false, schema = propertiesSetSchema()) {
            fs.configPropertiesSet(ConfigPropertiesSetRequest(it.requiredString("path"), it.requiredString("key"), it.requiredString("value")))
        }
        server.register("config_properties_remove", "Remove one key from a Java .properties file under the Minecraft server root.", readOnly = false, schema = propertiesKeySchema()) {
            fs.configPropertiesRemove(ConfigPropertiesRemoveRequest(it.requiredString("path"), it.requiredString("key")))
        }
        server.register("config_properties_list", "List keys from a Java .properties file under the Minecraft server root.", readOnly = true, schema = pathOnlySchema()) {
            fs.configPropertiesList(ConfigPropertiesListRequest(it.requiredString("path")))
        }
        server.register("config_json_get", "Read a JSON value by JSON pointer from a file under the Minecraft server root.", readOnly = true, schema = jsonPointerSchema()) {
            fs.configJsonGet(ConfigJsonGetRequest(it.requiredString("path"), it.requiredString("pointer")))
        }
        server.register("config_json_set", "Set a JSON value by JSON pointer in a file under the Minecraft server root.", readOnly = false, schema = jsonMutationSchema()) {
            fs.configJsonSet(ConfigJsonSetRequest(it.requiredString("path"), it.requiredString("pointer"), it.requiredJson("value")))
        }
        server.register("config_json_remove", "Remove a JSON value by JSON pointer from a file under the Minecraft server root.", readOnly = false, schema = jsonPointerSchema()) {
            fs.configJsonRemove(ConfigJsonRemoveRequest(it.requiredString("path"), it.requiredString("pointer")))
        }
        server.register("config_json_append", "Append a JSON value to an array selected by JSON pointer in a file under the Minecraft server root.", readOnly = false, schema = jsonMutationSchema()) {
            fs.configJsonAppend(ConfigJsonAppendRequest(it.requiredString("path"), it.requiredString("pointer"), it.requiredJson("value")))
        }
        server.register("console_send_command", "Dispatch a Minecraft console command and return captured latest.log lines.", readOnly = false, schema = consoleSchema()) {
            console.sendCommand(it.requiredString("command"))
        }
        server.register("power_actions", "Stop or restart the Minecraft server through native Bukkit/Paper APIs; restart preflights settings.restart-script.", readOnly = false, schema = powerActionSchema()) {
            powerActions.perform(
                action = it.requiredString("action"),
                reason = it.stringOrNull("reason"),
                delaySeconds = it.int("delaySeconds", 0),
            )
        }

        return server
    }

    private inline fun <reified T> Server.register(
        name: String,
        description: String,
        readOnly: Boolean,
        schema: ToolSchema,
        crossinline block: (JsonObject) -> T,
    ) {
        addTool(
            name = name,
            description = description,
            inputSchema = schema,
            toolAnnotations = ToolAnnotations(readOnlyHint = readOnly, openWorldHint = false),
        ) { request ->
            val arguments = request.arguments ?: JsonObject(emptyMap())
            if (verboseLogging) {
                logger.info("MCP tool call: $name")
            }
            try {
                structuredResult(block(arguments))
            } catch (throwable: Throwable) {
                logger.warning("MCP tool error in $name: ${throwable.message}")
                val error = buildJsonObject {
                    putJsonObject("error") {
                        put("type", throwable::class.simpleName ?: "Throwable")
                        put("message", throwable.message ?: "")
                    }
                }
                CallToolResult(
                    content = listOf(TextContent("${throwable::class.simpleName}: ${throwable.message}")),
                    isError = true,
                    structuredContent = error,
                )
            }
        }
    }

    private inline fun <reified T> structuredResult(value: T): CallToolResult {
        val json = McpJson.encodeToJsonElement(value).jsonObject
        return CallToolResult(
            content = listOf(TextContent(McpJson.encodeToString(json))),
            isError = false,
            structuredContent = json,
        )
    }

    private inline fun <reified T> encodeToolResult(value: T): JsonObject =
        McpJson.encodeToJsonElement(value).jsonObject

    private fun fileReadSchema(): ToolSchema = objectSchema(
        "path" to stringSchema("Relative file path"),
        "encoding" to enumSchema("text", "base64"),
        "offset" to integerSchema("Byte offset"),
        "length" to integerSchema("Maximum bytes to read"),
        required = listOf("path"),
    )

    private fun readManySchema(): ToolSchema = objectSchema(
        "paths" to arraySchema("Relative file paths"),
        "encoding" to enumSchema("text", "base64"),
        "maxBytesPerFile" to integerSchema("Maximum bytes per file"),
        required = listOf("paths"),
    )

    private fun writeSchema(): ToolSchema = objectSchema(
        "path" to stringSchema("Relative file path"),
        "content" to stringSchema("Text content or base64 payload"),
        "encoding" to enumSchema("text", "base64"),
        "createParents" to booleanSchema("Create parent directories"),
        required = listOf("path", "content"),
    )

    private fun editSchema(): ToolSchema = objectSchema(
        "path" to stringSchema("Relative file path"),
        "oldText" to stringSchema("Literal text to replace"),
        "newText" to stringSchema("Replacement text"),
        "replaceAll" to booleanSchema("Replace all occurrences instead of the first"),
        required = listOf("path", "oldText", "newText"),
    )

    private fun downloadSchema(): ToolSchema = objectSchema(
        "url" to stringSchema("HTTP or HTTPS URL to download"),
        "path" to stringSchema("Relative destination file path"),
        "overwrite" to booleanSchema("Replace the destination when it exists"),
        "createParents" to booleanSchema("Create destination parent directories"),
        "sha256" to stringSchema("Optional expected SHA-256 checksum"),
        required = listOf("url", "path"),
    )

    private fun pathOnlySchema(required: Boolean = true): ToolSchema = objectSchema(
        "path" to stringSchema("Relative path"),
        required = if (required) listOf("path") else emptyList(),
    )

    private fun treeSchema(): ToolSchema = objectSchema(
        "path" to stringSchema("Relative directory path"),
        "maxDepth" to integerSchema("Maximum recursion depth"),
    )

    private fun sourceDestinationSchema(): ToolSchema = objectSchema(
        "source" to stringSchema("Relative source path"),
        "destination" to stringSchema("Relative destination path"),
        "overwrite" to booleanSchema("Replace the destination when it exists"),
        "createParents" to booleanSchema("Create destination parent directories"),
        required = listOf("source", "destination"),
    )

    private fun deleteSchema(): ToolSchema = objectSchema(
        "path" to stringSchema("Relative path"),
        "recursive" to booleanSchema("Recursively delete directories"),
        required = listOf("path"),
    )

    private fun searchFilesSchema(): ToolSchema = objectSchema(
        "path" to stringSchema("Relative directory path"),
        "query" to stringSchema("Case-insensitive substring to match"),
        "glob" to stringSchema("Glob filter relative to path"),
        "maxResults" to integerSchema("Maximum matches"),
        required = listOf("query"),
    )

    private fun findPathsSchema(): ToolSchema = objectSchema(
        "query" to stringSchema("Case-insensitive substring to match"),
        "path" to stringSchema("Relative directory path"),
        "glob" to stringSchema("Glob filter relative to path"),
        "maxResults" to integerSchema("Maximum matches"),
        "includeDirectories" to booleanSchema("Include directories in matches"),
        "useIndex" to booleanSchema("Use the in-memory path index when ready"),
        required = listOf("query"),
    )

    private fun searchContentSchema(): ToolSchema = objectSchema(
        "path" to stringSchema("Relative directory path"),
        "query" to stringSchema("Substring or regex"),
        "regex" to booleanSchema("Treat query as a regular expression"),
        "glob" to stringSchema("Glob filter relative to path"),
        "maxResults" to integerSchema("Maximum matches"),
        required = listOf("query"),
    )

    private fun tailSchema(): ToolSchema = objectSchema(
        "path" to stringSchema("Relative file path"),
        "lines" to integerSchema("Number of trailing lines"),
        required = listOf("path"),
    )

    private fun consoleSchema(): ToolSchema = objectSchema(
        "command" to stringSchema("Minecraft console command without leading slash"),
        required = listOf("command"),
    )

    private fun powerActionSchema(): ToolSchema = objectSchema(
        "action" to enumSchema("stop", "restart"),
        "reason" to stringSchema("Reason to broadcast and log when the action runs"),
        "delaySeconds" to boundedIntegerSchema("Delay before running the action, from 0 to 600 seconds", 0, 600),
        required = listOf("action"),
    )

    private fun propertiesKeySchema(): ToolSchema = objectSchema(
        "path" to stringSchema("Relative .properties file path"),
        "key" to stringSchema("Property key"),
        required = listOf("path", "key"),
    )

    private fun propertiesSetSchema(): ToolSchema = objectSchema(
        "path" to stringSchema("Relative .properties file path"),
        "key" to stringSchema("Property key"),
        "value" to stringSchema("Property value"),
        required = listOf("path", "key", "value"),
    )

    private fun jsonPointerSchema(): ToolSchema = objectSchema(
        "path" to stringSchema("Relative JSON file path"),
        "pointer" to stringSchema("JSON pointer path such as /settings/enabled"),
        required = listOf("path", "pointer"),
    )

    private fun jsonMutationSchema(): ToolSchema = objectSchema(
        "path" to stringSchema("Relative JSON file path"),
        "pointer" to stringSchema("JSON pointer path such as /settings/enabled"),
        "value" to anySchema("JSON value"),
        required = listOf("path", "pointer", "value"),
    )

    private fun objectSchema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): ToolSchema =
        ToolSchema(
            properties = buildJsonObject {
                properties.forEach { (name, schema) -> put(name, schema) }
            },
            required = required,
        )

    private fun stringSchema(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun integerSchema(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun boundedIntegerSchema(description: String, minimum: Int, maximum: Int): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
        put("minimum", minimum)
        put("maximum", maximum)
    }

    private fun booleanSchema(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun enumSchema(vararg values: String): JsonObject = buildJsonObject {
        put("type", "string")
        putJsonArray("enum") {
            values.forEach { add(JsonPrimitive(it)) }
        }
    }

    private fun arraySchema(description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        putJsonObject("items") {
            put("type", "string")
        }
    }

    private fun anySchema(description: String): JsonObject = buildJsonObject {
        put("description", description)
    }
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("Missing required string argument: $name")

private fun JsonObject.requiredStringArray(name: String): List<String> {
    val array = this[name]?.jsonArray ?: throw IllegalArgumentException("Missing required array argument: $name")
    return array.map { it.jsonPrimitive.contentOrNull ?: throw IllegalArgumentException("Array argument $name must contain strings") }
}

private fun JsonObject.string(name: String, default: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: default

private fun JsonObject.stringOrNull(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.bool(name: String, default: Boolean): Boolean =
    this[name]?.jsonPrimitive?.booleanOrNull ?: default

private fun JsonObject.int(name: String, default: Int): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: default

private fun JsonObject.intOrNull(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.long(name: String, default: Long): Long =
    this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: default

private fun JsonObject.requiredJson(name: String): kotlinx.serialization.json.JsonElement =
    this[name] ?: throw IllegalArgumentException("Missing required JSON argument: $name")

sealed interface McAiToolCallResult {
    data class Success(val result: JsonObject) : McAiToolCallResult

    data class Error(val error: JsonObject, val text: String) : McAiToolCallResult
}

const val MCAI_MCP_SERVER_INSTRUCTIONS: String = """
Use a docs-first workflow for Minecraft plugin administration.

Before modifying unfamiliar plugin configuration, inspect the local plugin files and current config under the Minecraft server root first. Then check the official plugin docs or another trusted current source for that plugin before editing behavior you do not already know. Do not rely only on training data for unknown plugins or config keys.

Use mcAI filesystem and config tools within the server-root jail for file inspection and edits. Use console_send_command for ordinary Minecraft commands. Use power_actions for server stop and restart operations instead of dispatching stop or restart as generic console commands.
"""
