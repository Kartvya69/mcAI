package dev.mcai

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigStartupTest {
    @Test
    fun `first load creates config file with generated bearer token and null port`() {
        val dir = createTempDirectory("mcai-config")
        val configFile = dir.resolve("config.yml")

        val config = McAiConfigRepository(configFile, tokenGenerator = { "generated-token" }).load()

        assertTrue(configFile.exists())
        assertEquals("0.0.0.0", config.server.host)
        assertEquals(null, config.server.port)
        assertEquals("generated-token", config.auth.token)
        assertEquals(true, config.downloadPolicy.blockPrivateNetworks)
        assertEquals(600_000, config.pathIndex.reconciliationIntervalMillis)
        assertTrue("cache/**" in config.pathIndex.excludeGlobs)
        assertTrue(configFile.readText().contains("token: generated-token"))
        assertTrue(configFile.readText().contains("downloadPolicy:"))
        assertTrue(configFile.readText().contains("pathIndex:"))
    }

    @Test
    fun `existing token is preserved and missing token is generated`() {
        val dir = createTempDirectory("mcai-config")
        val configFile = dir.resolve("config.yml")
        configFile.writeText(
            """
            server:
              host: "127.0.0.1"
              port: 25577
            auth:
              token: "existing-token"
            """.trimIndent(),
        )

        val config = McAiConfigRepository(configFile, tokenGenerator = { "generated-token" }).load()

        assertEquals("127.0.0.1", config.server.host)
        assertEquals(25577, config.server.port)
        assertEquals("existing-token", config.auth.token)
        assertNotEquals("generated-token", config.auth.token)
    }

    @Test
    fun `startup decision disables MCP when no port is configured`() {
        val config = McAiConfig(server = McAiServerConfig(port = null), auth = McAiAuthConfig("token"))

        val decision = McAiStartupDecider.decide(config, minecraftPort = 25565)

        assertFalse(decision.enabled)
        assertEquals(McAiDisabledReason.NoPortConfigured, decision.disabledReason)
        assertEquals(25565, decision.minecraftPort)
    }

    @Test
    fun `startup decision rejects gameplay port reuse`() {
        val config = McAiConfig(server = McAiServerConfig(port = 25565), auth = McAiAuthConfig("token"))

        val decision = McAiStartupDecider.decide(config, minecraftPort = 25565)

        assertFalse(decision.enabled)
        assertEquals(McAiDisabledReason.PortConflictsWithMinecraft, decision.disabledReason)
    }

    @Test
    fun `startup decision enables MCP for a configured distinct port`() {
        val config = McAiConfig(server = McAiServerConfig(host = "127.0.0.1", port = 25577), auth = McAiAuthConfig("token"))

        val decision = McAiStartupDecider.decide(config, minecraftPort = 25565)

        assertTrue(decision.enabled)
        assertNotNull(decision.bind)
        assertEquals("127.0.0.1", decision.bind.host)
        assertEquals(25577, decision.bind.port)
    }
}
