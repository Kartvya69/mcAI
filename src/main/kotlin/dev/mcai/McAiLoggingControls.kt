package dev.mcai

import java.util.logging.Level
import java.util.logging.Logger

object McAiLoggingControls {
    val routineLoggerNames: List<String> = listOf(
        "io.ktor",
        "io.ktor.server.Application",
        "io.modelcontextprotocol.kotlin.sdk.server",
        "io.modelcontextprotocol.kotlin.sdk.shared",
        "io.modelcontextprotocol.kotlin.sdk.types",
        "io.modelcontextprotocol.kotlin.sdk.server.KtorServerKt",
    )

    fun apply(verbose: Boolean) {
        val julLevel = if (verbose) Level.INFO else Level.WARNING
        val slf4jSimpleLevel = if (verbose) "info" else "warn"

        routineLoggerNames.forEach { name ->
            Logger.getLogger(name).level = julLevel
            System.setProperty("org.slf4j.simpleLogger.log.$name", slf4jSimpleLevel)
        }
    }
}
