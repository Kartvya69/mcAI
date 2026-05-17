package dev.mcai

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class McAiMcpServerFactoryTest {
    @Test
    fun `factory registers power actions tool`() {
        val root = createTempDirectory("mcai-factory")
        root.resolve("logs").createDirectories()
        val server = McAiMcpServerFactory(
            FileSystemTools(root, McAiLimits()),
            ConsoleTools(ConsoleCommandDispatcher { true }, LatestLogTailer(root.resolve("logs/latest.log")), captureMillis = 0),
            PowerActions(NoopNativePowerActionExecutor, NoopPowerActionRunner),
            version = "test",
        ).create()

        assertContains(server.tools.keys, "power_actions")
        val description = server.tools.getValue("power_actions").tool.description.orEmpty()
        assertTrue(description.contains("preferred stop/restart path", ignoreCase = true))
        assertTrue(description.contains("native Bukkit/Paper APIs"))
        assertTrue(description.contains("restart preflights settings.restart-script"))
    }

    @Test
    fun `factory documents console command and power action guidance`() {
        val root = createTempDirectory("mcai-factory")
        root.resolve("logs").createDirectories()
        val server = McAiMcpServerFactory(
            FileSystemTools(root, McAiLimits()),
            ConsoleTools(ConsoleCommandDispatcher { true }, LatestLogTailer(root.resolve("logs/latest.log")), captureMillis = 0),
            PowerActions(NoopNativePowerActionExecutor, NoopPowerActionRunner),
            version = "test",
        ).create()

        val consoleTool = server.tools.getValue("console_send_command").tool
        val consoleDescription = consoleTool.description.orEmpty()
        assertTrue(consoleDescription.contains("ordinary Minecraft commands", ignoreCase = true))
        assertTrue(consoleDescription.contains("without a leading slash", ignoreCase = true))
        assertTrue(consoleDescription.contains("bounded logs/latest.log capture", ignoreCase = true))
        assertTrue(consoleDescription.contains("not synchronous stdout", ignoreCase = true))

        val commandSchemaDescription = requireNotNull(consoleTool.inputSchema.properties)
            .getValue("command")
            .jsonObject
            .getValue("description")
            .jsonPrimitive
            .contentOrNull
            .orEmpty()
        assertTrue(commandSchemaDescription.contains("ordinary Minecraft command", ignoreCase = true))
        assertTrue(commandSchemaDescription.contains("without a leading slash", ignoreCase = true))
        assertTrue(commandSchemaDescription.contains("not synchronous stdout", ignoreCase = true))

        assertTrue(MCAI_MCP_SERVER_INSTRUCTIONS.contains("Use power_actions for server stop and restart operations."))
        assertTrue(
            MCAI_MCP_SERVER_INSTRUCTIONS.contains(
                "Do not use console_send_command with stop or restart unless power_actions is unavailable and the user explicitly accepts that fallback.",
            ),
        )
        assertTrue(MCAI_MCP_SERVER_INSTRUCTIONS.contains("Use console_send_command for ordinary Minecraft commands without a leading slash."))
    }
}
