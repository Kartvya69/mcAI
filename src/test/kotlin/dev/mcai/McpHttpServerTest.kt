package dev.mcai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class McpHttpServerTest {
    @Test
    fun `websocket endpoint is disabled by default`() = runBlocking {
        val root = createTempDirectory("mcai-ws-disabled")
        root.resolve("logs").createDirectories()
        val server = testServer(root = root, dispatchedCommands = mutableListOf())
        val http = HttpClient(CIO)

        try {
            server.start()

            val response = http.get("http://127.0.0.1:${server.boundPort}/mcp/ws") {
                bearerAuth("secret")
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        } finally {
            http.close()
            server.stop()
        }
        Unit
    }

    @Test
    fun `websocket endpoint rejects missing and wrong bearer auth`() = runBlocking {
        val root = createTempDirectory("mcai-ws-auth")
        root.resolve("logs").createDirectories()
        val server = testServer(root = root, dispatchedCommands = mutableListOf(), webSocketEnabled = true)
        val http = HttpClient(CIO) {
            install(WebSockets)
        }

        try {
            server.start()

            assertFailsWith<Exception> {
                withTimeout(5_000) {
                    http.webSocket(
                        method = HttpMethod.Get,
                        host = "127.0.0.1",
                        port = server.boundPort,
                        path = "/mcp/ws",
                    ) {}
                }
            }
            assertFailsWith<Exception> {
                withTimeout(5_000) {
                    http.webSocket(
                        method = HttpMethod.Get,
                        host = "127.0.0.1",
                        port = server.boundPort,
                        path = "/mcp/ws",
                        request = {
                            header(HttpHeaders.Authorization, "Bearer wrong")
                        },
                    ) {}
                }
            }
        } finally {
            http.close()
            server.stop()
        }
        Unit
    }

    @Test
    fun `websocket endpoint routes tool calls through existing services`() = runBlocking {
        val root = createTempDirectory("mcai-ws-tools")
        root.resolve("logs").createDirectories()
        val dispatchedCommands = mutableListOf<String>()
        val server = testServer(root = root, dispatchedCommands = dispatchedCommands, webSocketEnabled = true)
        val http = HttpClient(CIO) {
            install(WebSockets)
        }

        try {
            server.start()

            val write = callWebSocketTool(
                http,
                server.boundPort,
                """
                {"id":"write-1","tool":"fs_write_file","arguments":{"path":"plugins/ws.txt","content":"hello from ws","createParents":true}}
                """.trimIndent(),
            )
            assertEquals("write-1", write["id"]?.jsonPrimitive?.content)
            assertEquals(true, write["ok"]?.jsonPrimitive?.booleanOrNull)
            assertEquals("plugins/ws.txt", write["result"]?.jsonObject?.get("path")?.jsonPrimitive?.content)

            val read = callWebSocketTool(
                http,
                server.boundPort,
                """
                {"id":"read-1","tool":"fs_read_file","arguments":{"path":"plugins/ws.txt"}}
                """.trimIndent(),
            )
            assertEquals(true, read["ok"]?.jsonPrimitive?.booleanOrNull)
            assertEquals("hello from ws", read["result"]?.jsonObject?.get("content")?.jsonPrimitive?.content)

            val console = callWebSocketTool(
                http,
                server.boundPort,
                """
                {"id":"console-1","tool":"console_send_command","arguments":{"command":"say websocket smoke"}}
                """.trimIndent(),
            )
            assertEquals(true, console["ok"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(listOf("say websocket smoke"), dispatchedCommands)
        } finally {
            http.close()
            server.stop()
        }
    }

    @Test
    fun `direct mcp endpoint still works when websocket is enabled`() = runBlocking {
        val root = createTempDirectory("mcai-mcp-with-ws")
        root.resolve("logs").createDirectories()
        val server = testServer(root = root, dispatchedCommands = mutableListOf(), webSocketEnabled = true)
        val http = HttpClient(CIO) {
            install(SSE)
        }

        try {
            server.start()
            val client = http.mcpStreamableHttp("http://127.0.0.1:${server.boundPort}/mcp") {
                bearerAuth("secret")
            }

            val tools = client.listTools().tools.map { it.name }

            assertEquals(true, "fs_read_file" in tools)
            assertEquals(true, "console_send_command" in tools)
            client.close()
        } finally {
            http.close()
            server.stop()
        }
    }

    @Test
    fun `mcp endpoint requires bearer auth and exposes filesystem tool calls`() = runBlocking {
        val root = createTempDirectory("mcai-mcp")
        root.resolve("logs").createDirectories()
        val fs = FileSystemTools(root, McAiLimits())
        val dispatchedCommands = mutableListOf<String>()
        val console = ConsoleTools(
            ConsoleCommandDispatcher { command ->
                dispatchedCommands += command
                true
            },
            LatestLogTailer(root.resolve("logs/latest.log")),
            captureMillis = 0,
        )
        val powerActionExecutor = RecordingPowerActionExecutor()
        val server = KtorMcpHttpServer(
            host = "127.0.0.1",
            port = 0,
            authToken = "secret",
            mcpServerFactory = McAiMcpServerFactory(
                fs,
                console,
                PowerActions(powerActionExecutor, ImmediatePowerActionRunner()),
                version = "test",
            ),
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
            assertEquals(true, client.serverInstructions?.contains("official plugin docs") == true)

            val tools = client.listTools().tools.map { it.name }
            assertEquals(true, "fs_write_file" in tools)
            assertEquals(true, "fs_download_file" in tools)
            assertEquals(true, "fs_find_paths" in tools)
            assertEquals(true, "config_properties_set" in tools)
            assertEquals(true, "config_json_set" in tools)
            assertEquals(true, "console_send_command" in tools)
            assertEquals(true, "power_actions" in tools)

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

            val consoleResult = client.callTool(
                name = "console_send_command",
                arguments = mapOf("command" to "say mcAI integration smoke"),
            )
            assertEquals(false, consoleResult.isError)
            assertEquals(listOf("say mcAI integration smoke"), dispatchedCommands)

            val powerResult = client.callTool(
                name = "power_actions",
                arguments = mapOf("action" to "stop", "reason" to "integration test"),
            )
            assertEquals(false, powerResult.isError)
            assertEquals("stop", powerResult.structuredContent?.get("action")?.jsonPrimitive?.content)
            assertEquals("false", powerResult.structuredContent?.get("scheduled")?.jsonPrimitive?.content)
            assertEquals(listOf<String?>("integration test"), powerActionExecutor.stopReasons)

            val invalidPowerResult = client.callTool(
                name = "power_actions",
                arguments = mapOf("action" to "reload"),
            )
            assertEquals(true, invalidPowerResult.isError)
            assertEquals("IllegalArgumentException", invalidPowerResult.structuredContent?.get("error")?.jsonObject?.get("type")?.jsonPrimitive?.content)

            client.close()
        } finally {
            http.close()
            server.stop()
        }
    }

    private class RecordingPowerActionExecutor : NativePowerActionExecutor {
        val stopReasons = mutableListOf<String?>()

        override fun stop(reason: String?) {
            stopReasons += reason
        }

        override fun restart(reason: String?) = Unit
    }

    private class ImmediatePowerActionRunner : PowerActionRunner {
        override fun runNow(action: () -> Unit) = action()

        override fun runLater(delaySeconds: Int, action: () -> Unit): PowerActionTask =
            PowerActionTask { }
    }

    private fun testServer(
        root: java.nio.file.Path,
        dispatchedCommands: MutableList<String>,
        webSocketEnabled: Boolean = false,
    ): KtorMcpHttpServer {
        val fs = FileSystemTools(root, McAiLimits())
        val console = ConsoleTools(
            ConsoleCommandDispatcher { command ->
                dispatchedCommands += command
                true
            },
            LatestLogTailer(root.resolve("logs/latest.log")),
            captureMillis = 0,
        )
        return KtorMcpHttpServer(
            host = "127.0.0.1",
            port = 0,
            authToken = "secret",
            mcpServerFactory = McAiMcpServerFactory(
                fs,
                console,
                PowerActions(RecordingPowerActionExecutor(), ImmediatePowerActionRunner()),
                version = "test",
            ),
            logger = TestPluginLogger(),
            webSocketEnabled = webSocketEnabled,
        )
    }

    private suspend fun callWebSocketTool(
        http: HttpClient,
        port: Int,
        request: String,
    ): kotlinx.serialization.json.JsonObject {
        var response: kotlinx.serialization.json.JsonObject? = null
        http.webSocket(
            method = HttpMethod.Get,
            host = "127.0.0.1",
            port = port,
            path = "/mcp/ws",
            request = {
                header(HttpHeaders.Authorization, "Bearer secret")
            },
        ) {
            send(Frame.Text(request))
            val frame = withTimeout(5_000) { incoming.receive() }
            response = Json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
        }
        return checkNotNull(response)
    }
}
