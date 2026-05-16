package dev.mcai

import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Path

open class McAiPlugin : JavaPlugin() {
    var currentMcpServer: KtorMcpHttpServer? = null
        private set
    private var currentFileSystemTools: FileSystemTools? = null

    override fun onEnable() {
        val config = McAiConfigRepository(dataFolder.toPath().resolve("config.yml")).load()
        McAiLoggingControls.apply(config.logging.verbose)
        val minecraftPort = runCatching { server.port }.getOrDefault(25565)
        val decision = McAiStartupDecider.decide(config, minecraftPort)

        if (!decision.enabled) {
            when (decision.disabledReason) {
                McAiDisabledReason.NoPortConfigured -> logger.info(
                    "mcAI MCP is disabled because server.port is not configured. " +
                        "Detected Minecraft gameplay port $minecraftPort; choose a different MCP HTTP port in plugins/mcAI/config.yml.",
                )
                McAiDisabledReason.PortConflictsWithMinecraft -> logger.severe(
                    "mcAI MCP is disabled because server.port equals the Minecraft gameplay port $minecraftPort. " +
                        "Choose a different MCP HTTP port in plugins/mcAI/config.yml.",
                )
                null -> logger.info("mcAI MCP is disabled.")
            }
            logger.info("mcAI bearer token is stored in plugins/mcAI/config.yml under auth.token.")
            return
        }

        val bind = decision.bind ?: return
        val root = minecraftServerRoot()
        val fs = FileSystemTools(root, config.limits, config.downloadPolicy, config.pathIndex)
        val console = ConsoleTools(
            dispatcher = BukkitConsoleCommandDispatcher(this),
            logTailer = LatestLogTailer(root.resolve("logs/latest.log")),
            captureMillis = config.limits.commandCaptureMillis,
        )
        val powerActions = PowerActions(
            executor = BukkitNativePowerActionExecutor(this),
            runner = BukkitPowerActionRunner(this),
        )
        val mcpServer = KtorMcpHttpServer(
            host = bind.host,
            port = bind.port,
            authToken = config.auth.token,
            mcpServerFactory = McAiMcpServerFactory(
                fs,
                console,
                powerActions,
                description.version,
                logger,
                verboseLogging = config.logging.verbose,
            ),
            logger = logger,
            verboseLogging = config.logging.verbose,
            webSocketEnabled = config.websocket.enabled,
        )

        try {
            mcpServer.start()
            currentMcpServer = mcpServer
            currentFileSystemTools = fs
            logger.info("mcAI MCP auth token config location: plugins/mcAI/config.yml -> auth.token")
        } catch (throwable: Throwable) {
            logger.severe("Failed to start mcAI MCP server: ${throwable.message}")
            mcpServer.stop()
            fs.close()
        }
    }

    override fun onDisable() {
        currentMcpServer?.stop()
        currentMcpServer = null
        currentFileSystemTools?.close()
        currentFileSystemTools = null
    }

    private fun minecraftServerRoot(): Path =
        server.worldContainer.toPath().toAbsolutePath().normalize()
}
