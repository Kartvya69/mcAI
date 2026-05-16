package dev.mcai

import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer
import kotlinx.serialization.Serializable

private const val MAX_POWER_ACTION_DELAY_SECONDS = 600

fun interface PowerActionTask {
    fun cancel()
}

interface PowerActionRunner {
    fun runNow(action: () -> Unit)

    fun runLater(delaySeconds: Int, action: () -> Unit): PowerActionTask
}

interface NativePowerActionExecutor {
    fun validateRestart() = Unit

    fun stop(reason: String?)

    fun restart(reason: String?)
}

object NoopNativePowerActionExecutor : NativePowerActionExecutor {
    override fun stop(reason: String?) = Unit

    override fun restart(reason: String?) = Unit
}

object NoopPowerActionRunner : PowerActionRunner {
    override fun runNow(action: () -> Unit) = action()

    override fun runLater(delaySeconds: Int, action: () -> Unit): PowerActionTask =
        PowerActionTask { }
}

@Serializable
data class PowerActionResult(
    val action: String,
    val scheduled: Boolean,
    val delaySeconds: Int,
    val replacedPendingAction: Boolean,
    val reason: String?,
    val message: String,
)

class PowerActions(
    private val executor: NativePowerActionExecutor,
    private val runner: PowerActionRunner,
) {
    private val lock = Any()
    private var nextToken = 0L
    private var pending: PendingPowerAction? = null

    fun perform(action: String, reason: String?, delaySeconds: Int): PowerActionResult {
        val normalizedAction = normalizeAction(action)
        val normalizedReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        require(delaySeconds in 0..MAX_POWER_ACTION_DELAY_SECONDS) {
            "delaySeconds must be between 0 and $MAX_POWER_ACTION_DELAY_SECONDS"
        }
        if (normalizedAction == "restart") {
            executor.validateRestart()
        }

        if (delaySeconds == 0) {
            val replaced = clearPendingAction()
            runner.runNow {
                execute(normalizedAction, normalizedReason)
            }
            return PowerActionResult(
                action = normalizedAction,
                scheduled = false,
                delaySeconds = 0,
                replacedPendingAction = replaced,
                reason = normalizedReason,
                message = "Invoked server $normalizedAction.",
            )
        }

        val replaced = scheduleDelayedAction(normalizedAction, normalizedReason, delaySeconds)
        return PowerActionResult(
            action = normalizedAction,
            scheduled = true,
            delaySeconds = delaySeconds,
            replacedPendingAction = replaced,
            reason = normalizedReason,
            message = "Scheduled server $normalizedAction in $delaySeconds seconds.",
        )
    }

    private fun normalizeAction(action: String): String {
        val normalized = action.trim().lowercase()
        require(normalized == "stop" || normalized == "restart") {
            "action must be one of: stop, restart"
        }
        return normalized
    }

    private fun execute(action: String, reason: String?) {
        when (action) {
            "stop" -> executor.stop(reason)
            "restart" -> executor.restart(reason)
            else -> error("Unsupported power action: $action")
        }
    }

    private fun clearPendingAction(): Boolean =
        synchronized(lock) {
            val current = pending ?: return@synchronized false
            current.task.cancel()
            pending = null
            nextToken += 1
            true
        }

    private fun scheduleDelayedAction(action: String, reason: String?, delaySeconds: Int): Boolean =
        synchronized(lock) {
            val current = pending
            current?.task?.cancel()
            pending = null
            val token = ++nextToken
            val task = runner.runLater(delaySeconds) {
                val shouldRun = synchronized(lock) {
                    if (pending?.token == token) {
                        pending = null
                        true
                    } else {
                        false
                    }
                }
                if (shouldRun) execute(action, reason)
            }
            pending = PendingPowerAction(token, task)
            current != null
        }

    private data class PendingPowerAction(val token: Long, val task: PowerActionTask)
}

class BukkitNativePowerActionExecutor(private val plugin: JavaPlugin) : NativePowerActionExecutor {
    override fun validateRestart() {
        val restartScript = restartScript()
        require(restartScript.value.isNotBlank()) { "settings.restart-script must not be blank for restart" }
        require(Files.isRegularFile(restartScript.path)) {
            "settings.restart-script '${restartScript.value}' does not exist. Create it under the Minecraft server root or change spigot.yml before using restart."
        }
    }

    override fun stop(reason: String?) {
        announce("stop", reason)
        plugin.server.shutdown()
    }

    override fun restart(reason: String?) {
        announce("restart", reason)
        plugin.server.spigot().restart()
    }

    private fun announce(action: String, reason: String?) {
        if (reason == null) return
        val message = "mcAI requested server $action: $reason"
        plugin.logger.warning(message)
        runCatching {
            plugin.server.broadcastMessage(message)
        }.onFailure { throwable ->
            plugin.logger.warning("Failed to broadcast mcAI power action reason: ${throwable.message}")
        }
    }

    private fun restartScript(): RestartScript {
        val value = plugin.server.spigot().config.getString("settings.restart-script", "./start.sh") ?: "./start.sh"
        val configuredPath = Path.of(value)
        val root = plugin.server.worldContainer.toPath().toAbsolutePath().normalize()
        val path = if (configuredPath.isAbsolute) configuredPath else root.resolve(configuredPath).normalize()
        return RestartScript(value, path)
    }

    private data class RestartScript(val value: String, val path: Path)
}

class BukkitPowerActionRunner(private val plugin: JavaPlugin) : PowerActionRunner {
    override fun runNow(action: () -> Unit) {
        plugin.server.globalRegionScheduler.execute(plugin, Runnable {
            action()
        })
    }

    override fun runLater(delaySeconds: Int, action: () -> Unit): PowerActionTask {
        val delayTicks = delaySeconds.toLong() * 20L
        val task = plugin.server.globalRegionScheduler.runDelayed(
            plugin,
            Consumer { action() },
            delayTicks,
        )
        return PowerActionTask {
            task.cancel()
        }
    }

}
