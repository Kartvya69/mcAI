package dev.mcai

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleToolsTest {
    @Test
    fun `console_send_command dispatches and returns captured latest log delta`() {
        val logFile = createTempDirectory("mcai-logs").resolve("logs/latest.log")
        logFile.parent.createDirectories()
        logFile.writeText("[00:00:00] [Server thread/INFO]: before\n")
        val dispatched = mutableListOf<String>()
        val tools = ConsoleTools(
            dispatcher = ConsoleCommandDispatcher { command ->
                dispatched += command
                logFile.writeText(logFile.readText() + "[00:00:01] [Server thread/INFO]: [Not Secure] <Server> mcAI smoke test\n")
                true
            },
            logTailer = LatestLogTailer(logFile),
            captureMillis = 0,
        )

        val result = tools.sendCommand("say mcAI smoke test")

        assertTrue(result.dispatched)
        assertEquals(listOf("say mcAI smoke test"), dispatched)
        assertContains(result.capturedLines.joinToString("\n"), "mcAI smoke test")
    }
}
