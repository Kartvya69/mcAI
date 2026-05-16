package dev.mcai

import java.util.logging.Level
import java.util.logging.Logger

object McAiLoggingControls {
    val routineLoggerNames: List<String> = listOf(
        "FeatureRegistry[Prompt]",
        "FeatureRegistry[Resource]",
        "FeatureRegistry[ResourceTemplate]",
        "FeatureRegistry[Tool]",
        "io.ktor",
        "io.ktor.server.Application",
        "io.modelcontextprotocol.kotlin.sdk.server",
        "io.modelcontextprotocol.kotlin.sdk.server.FeatureNotificationService",
        "io.modelcontextprotocol.kotlin.sdk.shared",
        "io.modelcontextprotocol.kotlin.sdk.shared.Protocol",
        "io.modelcontextprotocol.kotlin.sdk.types",
        "io.modelcontextprotocol.kotlin.sdk.server.KtorServerKt",
        "io.modelcontextprotocol.kotlin.sdk.server.ServerSessionRegistry",
        "io.modelcontextprotocol.kotlin.sdk.server.SessionNotificationJob",
    )

    fun apply(verbose: Boolean) {
        val julLevel = if (verbose) Level.INFO else Level.WARNING
        val slf4jSimpleLevel = if (verbose) "info" else "warn"

        routineLoggerNames.forEach { name ->
            Logger.getLogger(name).level = julLevel
            System.setProperty("org.slf4j.simpleLogger.log.$name", slf4jSimpleLevel)
            setLog4jLevel(name, verbose)
        }
    }

    private fun setLog4jLevel(name: String, verbose: Boolean) {
        runCatching {
            val levelClass = Class.forName("org.apache.logging.log4j.Level")
            val configuratorClass = Class.forName("org.apache.logging.log4j.core.config.Configurator")
            val level = levelClass.getField(if (verbose) "INFO" else "WARN").get(null)
            configuratorClass.getMethod("setLevel", String::class.java, levelClass).invoke(null, name, level)
        }
    }
}
