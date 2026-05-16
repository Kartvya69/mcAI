package dev.mcai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.bearerAuth
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggingConfigTest {
    @Test
    fun `quiet mode does not emit routine MCP tool call logs`() = runBlocking {
        val (logger, records) = recordingLogger()
        val root = createTempDirectory("mcai-quiet-logging")
        root.resolve("logs").createDirectories()
        val server = KtorMcpHttpServer(
            host = "127.0.0.1",
            port = 0,
            authToken = "secret",
            mcpServerFactory = McAiMcpServerFactory(
                FileSystemTools(root, McAiLimits()),
                ConsoleTools(ConsoleCommandDispatcher { true }, LatestLogTailer(root.resolve("logs/latest.log")), captureMillis = 0),
                PowerActions(NoopNativePowerActionExecutor, NoopPowerActionRunner),
                version = "test",
                logger = logger,
                verboseLogging = false,
            ),
            logger = logger,
            verboseLogging = false,
        )
        val http = HttpClient(CIO) {
            install(SSE)
        }

        try {
            server.start()
            val client = http.mcpStreamableHttp("http://127.0.0.1:${server.boundPort}/mcp") {
                bearerAuth("secret")
            }
            client.callTool(name = "fs_list_directory", arguments = mapOf("path" to "."))
            client.close()

            assertFalse(records.any { it.message == "MCP tool call: fs_list_directory" })
        } finally {
            http.close()
            server.stop()
        }
    }

    @Test
    fun `verbose mode emits routine MCP tool call logs`() = runBlocking {
        val (logger, records) = recordingLogger()
        val root = createTempDirectory("mcai-verbose-logging")
        root.resolve("logs").createDirectories()
        val server = KtorMcpHttpServer(
            host = "127.0.0.1",
            port = 0,
            authToken = "secret",
            mcpServerFactory = McAiMcpServerFactory(
                FileSystemTools(root, McAiLimits()),
                ConsoleTools(ConsoleCommandDispatcher { true }, LatestLogTailer(root.resolve("logs/latest.log")), captureMillis = 0),
                PowerActions(NoopNativePowerActionExecutor, NoopPowerActionRunner),
                version = "test",
                logger = logger,
                verboseLogging = true,
            ),
            logger = logger,
            verboseLogging = true,
        )
        val http = HttpClient(CIO) {
            install(SSE)
        }

        try {
            server.start()
            val client = http.mcpStreamableHttp("http://127.0.0.1:${server.boundPort}/mcp") {
                bearerAuth("secret")
            }
            client.callTool(name = "fs_list_directory", arguments = mapOf("path" to "."))
            client.close()

            assertTrue(records.any { it.message == "MCP tool call: fs_list_directory" })
        } finally {
            http.close()
            server.stop()
        }
    }

    @Test
    fun `quiet logging controls keep routine SDK and Ktor loggers at warning or higher`() {
        McAiLoggingControls.apply(verbose = false)

        assertTrue("FeatureRegistry[Tool]" in McAiLoggingControls.routineLoggerNames)
        assertTrue("io.modelcontextprotocol.kotlin.sdk.shared.Protocol" in McAiLoggingControls.routineLoggerNames)
        assertTrue("io.modelcontextprotocol.kotlin.sdk.server.ServerSessionRegistry" in McAiLoggingControls.routineLoggerNames)

        McAiLoggingControls.routineLoggerNames.forEach { loggerName ->
            val logger = Logger.getLogger(loggerName)
            assertEquals(Level.WARNING, logger.level)
            assertEquals("warn", System.getProperty("org.slf4j.simpleLogger.log.$loggerName"))
            assertFalse(logger.isLoggable(Level.INFO))
            assertTrue(logger.isLoggable(Level.WARNING))
            assertTrue(logger.isLoggable(Level.SEVERE))
        }
    }

    private fun recordingLogger(): Pair<Logger, List<LogRecord>> {
        val logger = Logger.getLogger("mcai-test-${UUID.randomUUID()}")
        val handler = RecordingHandler()
        logger.useParentHandlers = false
        logger.level = Level.ALL
        handler.level = Level.ALL
        logger.addHandler(handler)
        return logger to handler.records
    }

    private class RecordingHandler : Handler() {
        val records = mutableListOf<LogRecord>()

        override fun publish(record: LogRecord) {
            records += record
        }

        override fun flush() = Unit

        override fun close() = Unit
    }
}
