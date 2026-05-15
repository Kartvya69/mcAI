package dev.mcai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class McpHttpServerTest {
    @Test
    fun `mcp endpoint requires bearer auth and exposes filesystem tool calls`() = runBlocking {
        val root = createTempDirectory("mcai-mcp")
        root.resolve("logs").createDirectories()
        val fs = FileSystemTools(root, McAiLimits())
        val console = ConsoleTools(ConsoleCommandDispatcher { true }, LatestLogTailer(root.resolve("logs/latest.log")), captureMillis = 0)
        val server = KtorMcpHttpServer(
            host = "127.0.0.1",
            port = 0,
            authToken = "secret",
            mcpServerFactory = McAiMcpServerFactory(fs, console, version = "test"),
            logger = TestPluginLogger(),
        )
        server.start()
        val url = "http://127.0.0.1:${server.boundPort}/mcp"
        val http = HttpClient(CIO) {
            install(SSE)
        }

        try {
            val unauthorized = http.post(url) {
                contentType(ContentType.Application.Json)
                setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

            val client = http.mcpStreamableHttp(url) {
                bearerAuth("secret")
            }
            val tools = client.listTools().tools.map { it.name }
            assertEquals(true, "fs_write_file" in tools)
            assertEquals(true, "fs_download_file" in tools)
            assertEquals(true, "fs_find_paths" in tools)
            assertEquals(true, "config_properties_set" in tools)
            assertEquals(true, "config_json_set" in tools)
            assertEquals(true, "console_send_command" in tools)

            val writeResult = client.callTool(
                name = "fs_write_file",
                arguments = mapOf("path" to "plugins/test.txt", "content" to "hello", "createParents" to true),
            )
            assertEquals(false, writeResult.isError)
            assertNotNull(writeResult.structuredContent)

            val readResult = client.callTool(name = "fs_read_file", arguments = mapOf("path" to "plugins/test.txt"))
            val content = assertIs<TextContent>(readResult.content.single())
            assertEquals("hello", readResult.structuredContent?.get("content")?.jsonPrimitive?.content)
            assertEquals(true, content.text.contains("hello"))

            val findResult = client.callTool(
                name = "fs_find_paths",
                arguments = mapOf("query" to "test.txt", "useIndex" to false),
            )
            assertEquals(false, findResult.isError)
            assertEquals(
                "plugins/test.txt",
                findResult.structuredContent?.get("matches")?.jsonArray?.single()?.jsonObject?.get("path")?.jsonPrimitive?.content,
            )

            client.callTool(
                name = "fs_write_file",
                arguments = mapOf("path" to "server.properties", "content" to "# demo\nmotd=old\n"),
            )
            val propertySet = client.callTool(
                name = "config_properties_set",
                arguments = mapOf("path" to "server.properties", "key" to "motd", "value" to "new"),
            )
            assertEquals("replaced", propertySet.structuredContent?.get("action")?.jsonPrimitive?.content)

            val errorResult = client.callTool(
                name = "config_json_set",
                arguments = mapOf("path" to "../settings.json", "pointer" to "/enabled", "value" to true),
            )
            assertEquals(true, errorResult.isError)
            assertEquals("PathOutsideServerRootException", errorResult.structuredContent?.get("error")?.jsonObject?.get("type")?.jsonPrimitive?.content)

            client.close()
        } finally {
            http.close()
            server.stop()
        }
    }
}
