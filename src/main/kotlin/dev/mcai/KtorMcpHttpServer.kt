package dev.mcai

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.logging.Logger

const val MCAI_WEBSOCKET_PATH: String = "/mcp/ws"

class KtorMcpHttpServer(
    private val host: String,
    private val port: Int,
    private val authToken: String,
    private val mcpServerFactory: McAiMcpServerFactory,
    private val logger: Logger,
    private val verboseLogging: Boolean = false,
    private val webSocketEnabled: Boolean = false,
) {
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    var boundPort: Int = port
        private set
    private val webSocketJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun start() {
        check(engine == null) { "MCP HTTP server is already started" }
        McAiLoggingControls.apply(verboseLogging)
        val started = embeddedServer(CIO, host = host, port = port) {
            install(CORS) {
                anyHost()
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Delete)
                allowHeader(HttpHeaders.Authorization)
                allowHeader("Mcp-Protocol-Version")
                allowHeader("Mcp-Session-Id")
                exposeHeader("Mcp-Protocol-Version")
                exposeHeader("Mcp-Session-Id")
                allowNonSimpleContentTypes = true
            }
            if (webSocketEnabled) {
                install(WebSockets)
            }
            intercept(ApplicationCallPipeline.Plugins) {
                val protectedPath = call.request.path() == "/mcp" ||
                    (webSocketEnabled && call.request.path() == MCAI_WEBSOCKET_PATH)
                if (protectedPath && call.request.httpMethod != HttpMethod.Options) {
                    val expected = "Bearer $authToken"
                    if (call.request.header(HttpHeaders.Authorization) != expected) {
                        logger.warning("MCP auth failure")
                        call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                        call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                        finish()
                    }
                }
            }
            if (webSocketEnabled) {
                routing {
                    webSocket(MCAI_WEBSOCKET_PATH) {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                send(handleWebSocketToolRequest(frame.readText()))
                            }
                        }
                    }
                }
            }
            mcpStatelessStreamableHttp(
                path = "/mcp",
                enableDnsRebindingProtection = false,
            ) {
                mcpServerFactory.create()
            }
        }.start(wait = false)

        engine = started
        boundPort = runBlocking { started.engine.resolvedConnectors().single().port }
        logger.info("mcAI MCP server listening at http://$host:$boundPort/mcp")
        if (webSocketEnabled) {
            logger.info("mcAI WebSocket API listening at ws://$host:$boundPort$MCAI_WEBSOCKET_PATH")
        }
    }

    fun stop() {
        engine?.stop(1_000, 3_000)
        engine = null
    }

    private fun handleWebSocketToolRequest(text: String): String {
        val requestId = parseRequestId(text)
        return try {
            val request = webSocketJson.decodeFromString<McAiWebSocketToolRequest>(text)
            when (val result = mcpServerFactory.callTool(request.tool, request.arguments)) {
                is McAiToolCallResult.Success -> webSocketJson.encodeToString(
                    McAiWebSocketToolSuccessResponse(
                        id = request.id,
                        result = result.result,
                    ),
                )
                is McAiToolCallResult.Error -> webSocketJson.encodeToString(
                    McAiWebSocketToolErrorResponse(
                        id = request.id,
                        error = result.error["error"]?.jsonObject ?: result.error,
                    ),
                )
            }
        } catch (throwable: Throwable) {
            webSocketJson.encodeToString(
                McAiWebSocketToolErrorResponse(
                    id = requestId,
                    error = buildJsonObject {
                        put("type", throwable::class.simpleName ?: "Throwable")
                        put("message", throwable.message ?: "")
                    },
                ),
            )
        }
    }

    private fun parseRequestId(text: String): JsonElement =
        runCatching {
            webSocketJson.parseToJsonElement(text).jsonObject["id"] ?: JsonNull
        }.getOrDefault(JsonNull)
}

@Serializable
private data class McAiWebSocketToolRequest(
    val id: JsonElement = JsonNull,
    val tool: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

@Serializable
private data class McAiWebSocketToolSuccessResponse(
    val id: JsonElement = JsonNull,
    val ok: Boolean = true,
    val result: JsonObject,
)

@Serializable
private data class McAiWebSocketToolErrorResponse(
    val id: JsonElement = JsonNull,
    val ok: Boolean = false,
    val error: JsonObject,
)
