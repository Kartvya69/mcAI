package dev.mcai

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
        assertTrue(server.tools.getValue("power_actions").tool.description?.contains("restart preflights settings.restart-script") == true)
    }
}
