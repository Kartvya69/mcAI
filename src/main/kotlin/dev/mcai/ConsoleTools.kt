package dev.mcai

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlin.io.path.exists

fun interface ConsoleCommandDispatcher {
    fun dispatch(command: String): Boolean
}

@Serializable
data class ConsoleCommandRequest(val command: String)

@Serializable
data class ConsoleCommandResult(val dispatched: Boolean, val capturedLines: List<String>)

class ConsoleTools(
    private val dispatcher: ConsoleCommandDispatcher,
    private val logTailer: LatestLogTailer,
    private val captureMillis: Long,
) {
    fun sendCommand(command: String): ConsoleCommandResult {
        require(command.isNotBlank()) { "command must not be blank" }
        val checkpoint = logTailer.checkpoint()
        val dispatched = dispatcher.dispatch(command)
        if (captureMillis > 0) Thread.sleep(captureMillis)
        return ConsoleCommandResult(dispatched = dispatched, capturedLines = logTailer.linesSince(checkpoint))
    }
}

class LatestLogTailer(private val logFile: Path) {
    fun checkpoint(): Long = if (logFile.exists()) Files.size(logFile) else 0L

    fun linesSince(checkpoint: Long, maxBytes: Int = 131_072): List<String> {
        if (!logFile.exists()) return emptyList()
        val size = Files.size(logFile)
        val start = checkpoint.coerceAtMost(size)
        val bytesToRead = (size - start).coerceAtMost(maxBytes.toLong()).toInt()
        if (bytesToRead <= 0) return emptyList()
        return RandomAccessFile(logFile.toFile(), "r").use { raf ->
            raf.seek(start)
            ByteArray(bytesToRead).also { raf.readFully(it) }
        }.toString(StandardCharsets.UTF_8)
            .lineSequence()
            .filter { it.isNotEmpty() }
            .toList()
    }
}

class BukkitConsoleCommandDispatcher(private val plugin: JavaPlugin) : ConsoleCommandDispatcher {
    override fun dispatch(command: String): Boolean {
        dispatchOnFolia(command)?.let { return it }
        if (Bukkit.isPrimaryThread()) return dispatchNow(command)

        val future = CompletableFuture<Boolean>()
        plugin.server.scheduler.runTask(plugin, Runnable {
            completeDispatch(future, command)
        })
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun dispatchOnFolia(command: String): Boolean? {
        return try {
            val method = plugin.server.javaClass.methods.firstOrNull {
                it.name == "getGlobalRegionScheduler" && it.parameterCount == 0
            } ?: return null
            val scheduler = method.invoke(plugin.server)
            val execute = scheduler.javaClass.methods.firstOrNull {
                it.name == "execute" && it.parameterCount == 2 && it.parameterTypes[0].isAssignableFrom(plugin.javaClass)
            } ?: return null
            val future = CompletableFuture<Boolean>()
            execute.invoke(scheduler, plugin, Runnable { completeDispatch(future, command) })
            future.get(10, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            null
        }
    }

    private fun completeDispatch(future: CompletableFuture<Boolean>, command: String) {
        try {
            future.complete(dispatchNow(command))
        } catch (throwable: Throwable) {
            future.completeExceptionally(throwable)
        }
    }

    private fun dispatchNow(command: String): Boolean =
        plugin.server.dispatchCommand(plugin.server.consoleSender, command)
}
