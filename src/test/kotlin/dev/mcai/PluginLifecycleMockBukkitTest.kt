package dev.mcai

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.Test
import kotlin.test.assertTrue

class PluginLifecycleMockBukkitTest {
    @BeforeEach
    fun setUp() {
        MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `plugin enable generates config and leaves MCP disabled without configured port`() {
        val plugin = MockBukkit.load(McAiPlugin::class.java)

        assertTrue(plugin.dataFolder.resolve("config.yml").exists())
        assertTrue(plugin.dataFolder.resolve("config.yml").readText().contains("port: null"))
        assertTrue(plugin.currentMcpServer == null)
    }
}
