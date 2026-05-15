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
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.coroutines.runBlocking
import java.util.logging.Logger

class KtorMcpHttpServer(
    private val host: String,
    private val port: Int,
    private val authToken: String,
    private val mcpServerFactory: McAiMcpServerFactory,
    private val logger: Logger,
) {
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    var boundPort: Int = port
        private set

    fun start() {
        check(engine == null) { "MCP HTTP server is already started" }
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
            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.path() == "/mcp" && call.request.httpMethod != HttpMethod.Options) {
                    val expected = "Bearer $authToken"
                    if (call.request.header(HttpHeaders.Authorization) != expected) {
                        logger.warning("MCP auth failure")
                        call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                        call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                        finish()
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
    }

    fun stop() {
        engine?.stop(1_000, 3_000)
        engine = null
    }
}
