package dev.mcai

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class McAiServerConfig(
    val host: String = "0.0.0.0",
    val port: Int? = null,
)

data class McAiAuthConfig(
    val token: String,
)

data class McAiLimits(
    val maxReadBytes: Int = 104_857_600,
    val maxWriteBytes: Int = 104_857_600,
    val maxDirectoryEntries: Int = 10_000,
    val commandCaptureMillis: Long = 1_500,
)

data class McAiDownloadPolicy(
    val connectTimeoutMillis: Int = 5_000,
    val requestTimeoutMillis: Int = 30_000,
    val readTimeoutMillis: Int = 30_000,
    val blockPrivateNetworks: Boolean = true,
    val trustedHosts: List<String> = emptyList(),
    val trustedCidrs: List<String> = emptyList(),
    val maxRedirects: Int = 5,
)

data class McAiPathIndexConfig(
    val reconciliationIntervalMillis: Long = 600_000,
    val excludeGlobs: List<String> = DEFAULT_PATH_INDEX_EXCLUDE_GLOBS,
)

data class McAiConfig(
    val server: McAiServerConfig = McAiServerConfig(),
    val auth: McAiAuthConfig,
    val limits: McAiLimits = McAiLimits(),
    val downloadPolicy: McAiDownloadPolicy = McAiDownloadPolicy(),
    val pathIndex: McAiPathIndexConfig = McAiPathIndexConfig(),
)

enum class McAiDisabledReason {
    NoPortConfigured,
    PortConflictsWithMinecraft,
}

data class McAiBindAddress(
    val host: String,
    val port: Int,
)

data class McAiStartupDecision(
    val enabled: Boolean,
    val minecraftPort: Int,
    val bind: McAiBindAddress? = null,
    val disabledReason: McAiDisabledReason? = null,
)

object McAiStartupDecider {
    fun decide(config: McAiConfig, minecraftPort: Int): McAiStartupDecision {
        val port = config.server.port
        if (port == null) {
            return McAiStartupDecision(
                enabled = false,
                minecraftPort = minecraftPort,
                disabledReason = McAiDisabledReason.NoPortConfigured,
            )
        }

        if (port == minecraftPort) {
            return McAiStartupDecision(
                enabled = false,
                minecraftPort = minecraftPort,
                disabledReason = McAiDisabledReason.PortConflictsWithMinecraft,
            )
        }

        return McAiStartupDecision(
            enabled = true,
            minecraftPort = minecraftPort,
            bind = McAiBindAddress(config.server.host, port),
        )
    }
}

class McAiConfigRepository(
    private val configFile: Path,
    private val tokenGenerator: () -> String = ::generateBearerToken,
) {
    fun load(): McAiConfig {
        val values = readConfigMap()
        val server = values.section("server")
        val auth = values.section("auth")
        val limits = values.section("limits")
        val downloadPolicy = values.section("downloadPolicy")
        val pathIndex = values.section("pathIndex")

        val config = McAiConfig(
            server = McAiServerConfig(
                host = server.string("host") ?: "0.0.0.0",
                port = server.int("port"),
            ),
            auth = McAiAuthConfig(
                token = auth.string("token")?.takeIf { it.isNotBlank() } ?: tokenGenerator(),
            ),
            limits = McAiLimits(
                maxReadBytes = limits.int("maxReadBytes") ?: 104_857_600,
                maxWriteBytes = limits.int("maxWriteBytes") ?: 104_857_600,
                maxDirectoryEntries = limits.int("maxDirectoryEntries") ?: 10_000,
                commandCaptureMillis = limits.long("commandCaptureMillis") ?: 1_500,
            ),
            downloadPolicy = McAiDownloadPolicy(
                connectTimeoutMillis = downloadPolicy.int("connectTimeoutMillis") ?: 5_000,
                requestTimeoutMillis = downloadPolicy.int("requestTimeoutMillis") ?: 30_000,
                readTimeoutMillis = downloadPolicy.int("readTimeoutMillis") ?: 30_000,
                blockPrivateNetworks = downloadPolicy.boolean("blockPrivateNetworks") ?: true,
                trustedHosts = downloadPolicy.stringList("trustedHosts"),
                trustedCidrs = downloadPolicy.stringList("trustedCidrs"),
                maxRedirects = downloadPolicy.int("maxRedirects") ?: 5,
            ),
            pathIndex = McAiPathIndexConfig(
                reconciliationIntervalMillis = pathIndex.long("reconciliationIntervalMillis") ?: 600_000,
                excludeGlobs = pathIndex.stringList("excludeGlobs").ifEmpty { DEFAULT_PATH_INDEX_EXCLUDE_GLOBS },
            ),
        )

        save(config)
        return config
    }

    private fun readConfigMap(): Map<String, Any?> {
        if (!configFile.exists()) return emptyMap()
        val loaded = Yaml().load<Any?>(configFile.readText())
        return loaded as? Map<String, Any?> ?: emptyMap()
    }

    private fun save(config: McAiConfig) {
        configFile.parent?.createDirectories()
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
        }
        val yaml = Yaml(options)
        val document = linkedMapOf(
            "server" to linkedMapOf(
                "host" to config.server.host,
                "port" to config.server.port,
            ),
            "auth" to linkedMapOf(
                "token" to config.auth.token,
            ),
            "limits" to linkedMapOf(
                "maxReadBytes" to config.limits.maxReadBytes,
                "maxWriteBytes" to config.limits.maxWriteBytes,
                "maxDirectoryEntries" to config.limits.maxDirectoryEntries,
                "commandCaptureMillis" to config.limits.commandCaptureMillis,
            ),
            "downloadPolicy" to linkedMapOf(
                "connectTimeoutMillis" to config.downloadPolicy.connectTimeoutMillis,
                "requestTimeoutMillis" to config.downloadPolicy.requestTimeoutMillis,
                "readTimeoutMillis" to config.downloadPolicy.readTimeoutMillis,
                "blockPrivateNetworks" to config.downloadPolicy.blockPrivateNetworks,
                "trustedHosts" to config.downloadPolicy.trustedHosts,
                "trustedCidrs" to config.downloadPolicy.trustedCidrs,
                "maxRedirects" to config.downloadPolicy.maxRedirects,
            ),
            "pathIndex" to linkedMapOf(
                "reconciliationIntervalMillis" to config.pathIndex.reconciliationIntervalMillis,
                "excludeGlobs" to config.pathIndex.excludeGlobs,
            ),
        )
        configFile.writeText(yaml.dump(document))
    }
}

private fun Map<String, Any?>.section(name: String): Map<String, Any?> =
    this[name] as? Map<String, Any?> ?: emptyMap()

private fun Map<String, Any?>.string(name: String): String? =
    this[name]?.toString()

private fun Map<String, Any?>.int(name: String): Int? =
    when (val value = this[name]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

private fun Map<String, Any?>.long(name: String): Long? =
    when (val value = this[name]) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

private fun Map<String, Any?>.boolean(name: String): Boolean? =
    when (val value = this[name]) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }

private fun Map<String, Any?>.stringList(name: String): List<String> =
    when (val value = this[name]) {
        is List<*> -> value.mapNotNull { it?.toString() }
        is String -> listOf(value)
        else -> emptyList()
    }

private fun generateBearerToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

val DEFAULT_PATH_INDEX_EXCLUDE_GLOBS: List<String> = listOf(
    ".git/**",
    ".gradle/**",
    "build/**",
    "cache/**",
    "libraries/**",
    "versions/**",
    "world*/region/**",
    "world*/entities/**",
)
