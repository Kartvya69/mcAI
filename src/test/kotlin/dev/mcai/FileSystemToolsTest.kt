package dev.mcai

import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemToolsTest {
    private fun service(rootName: String = "mcai-root"): FileSystemTools {
        val root = createTempDirectory(rootName)
        return FileSystemTools(root, McAiLimits(maxReadBytes = 1024, maxWriteBytes = 1024, maxDirectoryEntries = 100))
    }

    @Test
    fun `file operations stay jailed to the server root`() {
        val tools = service()

        assertFailsWith<PathOutsideServerRootException> {
            tools.readFile(ReadFileRequest("../outside.txt"))
        }
        assertFailsWith<PathOutsideServerRootException> {
            tools.writeFile(WriteFileRequest("/tmp/outside.txt", "bad"))
        }
    }

    @Test
    fun `reads and writes text and base64 binary with offsets`() {
        val tools = service()
        tools.writeFile(WriteFileRequest("plugins/example/config.yml", "alpha\nbeta\n", createParents = true))

        val read = tools.readFile(ReadFileRequest("plugins/example/config.yml", offset = 6, length = 4))

        assertEquals("text", read.encoding)
        assertEquals("beta", read.content)
        assertEquals(4, read.bytesRead)

        val payload = byteArrayOf(0, 1, 2, 3, 4)
        tools.writeFile(WriteFileRequest("world/data.bin", Base64.getEncoder().encodeToString(payload), encoding = "base64", createParents = true))

        val binary = tools.readFile(ReadFileRequest("world/data.bin", encoding = "base64", offset = 1, length = 3))

        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), binary.content)
    }

    @Test
    fun `edits appends copies moves deletes lists trees stats searches and tails`() {
        val tools = service()

        tools.writeFile(WriteFileRequest("plugins/a/config.txt", "alpha\nbeta\nalpha\n", createParents = true))
        val edit = tools.editFile(EditFileRequest("plugins/a/config.txt", oldText = "alpha", newText = "gamma", replaceAll = true))
        tools.appendFile(AppendFileRequest("plugins/a/config.txt", "delta\n"))
        tools.copy(CopyRequest("plugins/a/config.txt", "plugins/a/copy.txt"))
        tools.move(MoveRequest("plugins/a/copy.txt", "plugins/a/moved.txt"))

        assertEquals(2, edit.replacements)
        assertEquals("gamma\nbeta\ngamma\ndelta\n", tools.root.resolve("plugins/a/config.txt").readText())
        assertTrue(tools.root.resolve("plugins/a/moved.txt").exists())
        assertContains(tools.listDirectory(ListDirectoryRequest("plugins/a")).entries.map { it.name }, "moved.txt")
        assertContains(tools.directoryTree(DirectoryTreeRequest("plugins")).entries.map { it.path }, "a/config.txt")
        assertEquals("file", tools.stat(StatRequest("plugins/a/config.txt")).type)
        assertContains(tools.searchFiles(SearchFilesRequest("plugins", query = "config")).matches.map { it.path }, "a/config.txt")
        assertEquals(listOf("delta", "gamma"), tools.searchContent(SearchContentRequest("plugins", query = "gamma|delta", regex = true)).matches.map { it.lineText }.distinct().sorted())
        assertEquals(listOf("gamma", "delta"), tools.tailFile(TailFileRequest("plugins/a/config.txt", lines = 2)).lines)

        tools.delete(DeleteRequest("plugins/a/moved.txt"))
        assertFalse(tools.root.resolve("plugins/a/moved.txt").exists())
    }

    @Test
    fun `read and write limits are enforced`() {
        val tools = FileSystemTools(createTempDirectory("mcai-limits"), McAiLimits(maxReadBytes = 4, maxWriteBytes = 4))

        assertFailsWith<FileLimitExceededException> {
            tools.writeFile(WriteFileRequest("too-large.txt", "12345"))
        }

        tools.root.resolve("five.txt").writeText("12345")
        assertFailsWith<FileLimitExceededException> {
            tools.readFile(ReadFileRequest("five.txt"))
        }
    }

    @Test
    fun `downloads http content into the server root with parent creation and overwrite control`() {
        val tools = FileSystemTools(
            createTempDirectory("mcai-root"),
            McAiLimits(maxReadBytes = 1024, maxWriteBytes = 1024, maxDirectoryEntries = 100),
            McAiDownloadPolicy(trustedCidrs = listOf("127.0.0.0/8")),
        )
        withHttpServer("downloaded from http\n") { url ->
            val result = tools.downloadFile(
                DownloadFileRequest(
                    url = url,
                    path = "plugins/downloaded.txt",
                    createParents = true,
                    overwrite = false,
                ),
            )

            assertEquals("plugins/downloaded.txt", result.path)
            assertEquals(21, result.bytesWritten)
            assertEquals("downloaded from http\n", tools.root.resolve("plugins/downloaded.txt").readText())

            assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                tools.downloadFile(DownloadFileRequest(url, "plugins/downloaded.txt", overwrite = false))
            }
        }
    }

    @Test
    fun `download rejects unsupported schemes outside paths and oversized responses`() {
        val tools = FileSystemTools(
            createTempDirectory("mcai-download-limits"),
            McAiLimits(maxReadBytes = 1024, maxWriteBytes = 4),
            McAiDownloadPolicy(trustedCidrs = listOf("127.0.0.0/8")),
        )

        assertFailsWith<IllegalArgumentException> {
            tools.downloadFile(DownloadFileRequest("ftp://example.com/file.jar", "plugins/file.jar"))
        }

        withHttpServer("12345") { url ->
            assertFailsWith<FileLimitExceededException> {
                tools.downloadFile(DownloadFileRequest(url, "plugins/file.jar", createParents = true))
            }
        }

        withHttpServer("ok") { url ->
            assertFailsWith<PathOutsideServerRootException> {
                tools.downloadFile(DownloadFileRequest(url, "../file.jar"))
            }
        }
    }

    @Test
    fun `download blocks private network targets by default and cleans partial files`() {
        val tools = service("mcai-download-ssrf")

        withHttpServer("private") { url ->
            assertFailsWith<IllegalArgumentException> {
                tools.downloadFile(DownloadFileRequest(url, "plugins/private.txt", createParents = true))
            }
        }

        assertFalse(tools.root.resolve("plugins/private.txt").exists())
        assertFalse(hasDownloadTempFile(tools))
    }

    @Test
    fun `download rejects checksum mismatch before final move and cleans partial files`() {
        val tools = FileSystemTools(
            createTempDirectory("mcai-download-checksum"),
            McAiLimits(maxReadBytes = 1024, maxWriteBytes = 1024),
            McAiDownloadPolicy(trustedCidrs = listOf("127.0.0.0/8")),
        )

        withHttpServer("checksum") { url ->
            assertFailsWith<IllegalArgumentException> {
                tools.downloadFile(
                    DownloadFileRequest(
                        url = url,
                        path = "plugins/checksum.txt",
                        createParents = true,
                        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                    ),
                )
            }
        }

        assertFalse(tools.root.resolve("plugins/checksum.txt").exists())
        assertFalse(hasDownloadTempFile(tools))
    }

    @Test
    fun `download validates every redirect target against private network policy`() {
        val tools = FileSystemTools(
            createTempDirectory("mcai-download-redirect"),
            McAiLimits(maxReadBytes = 1024, maxWriteBytes = 1024),
            McAiDownloadPolicy(trustedHosts = listOf("localhost")),
        )

        withRedirectServer { url ->
            assertFailsWith<IllegalArgumentException> {
                tools.downloadFile(DownloadFileRequest(url, "plugins/redirect.txt", createParents = true))
            }
        }

        assertFalse(tools.root.resolve("plugins/redirect.txt").exists())
        assertFalse(hasDownloadTempFile(tools))
    }

    @Test
    fun `download read timeout aborts stalled responses and cleans partial files`() {
        val tools = FileSystemTools(
            createTempDirectory("mcai-download-timeout"),
            McAiLimits(maxReadBytes = 1024, maxWriteBytes = 1024),
            McAiDownloadPolicy(
                readTimeoutMillis = 100,
                requestTimeoutMillis = 1_000,
                trustedCidrs = listOf("127.0.0.0/8"),
            ),
        )

        withSlowHttpServer { url ->
            assertFailsWith<java.net.SocketTimeoutException> {
                tools.downloadFile(DownloadFileRequest(url, "plugins/slow.txt", createParents = true))
            }
        }

        assertFalse(tools.root.resolve("plugins/slow.txt").exists())
        assertFalse(hasDownloadTempFile(tools))
    }

    @Test
    fun `indexed path finder returns freshness metadata and omits excluded directories`() {
        val tools = service("mcai-index")
        tools.writeFile(WriteFileRequest("plugins/a/config.yml", "name: demo", createParents = true))
        tools.writeFile(WriteFileRequest("cache/ignored-config.yml", "ignored", createParents = true))
        tools.reconcilePathIndex()

        val result = tools.findPaths(FindPathsRequest(query = "config", useIndex = true))

        assertEquals("index", result.freshness.source)
        assertTrue(result.freshness.isIndexReady)
        assertFalse(result.freshness.mayBeStale)
        assertContains(result.matches.map { it.path }, "plugins/a/config.yml")
        assertFalse(result.matches.any { it.path == "cache/ignored-config.yml" })
    }

    @Test
    fun `indexed path finder follows file create move and delete operations`() {
        val tools = service("mcai-index-updates")
        tools.reconcilePathIndex()

        tools.writeFile(WriteFileRequest("plugins/live/created.txt", "ok", createParents = true))
        assertContains(
            tools.findPaths(FindPathsRequest(query = "created", useIndex = true)).matches.map { it.path },
            "plugins/live/created.txt",
        )

        tools.move(MoveRequest("plugins/live/created.txt", "plugins/live/renamed.txt"))
        assertContains(
            tools.findPaths(FindPathsRequest(query = "renamed", useIndex = true)).matches.map { it.path },
            "plugins/live/renamed.txt",
        )

        tools.delete(DeleteRequest("plugins/live/renamed.txt"))
        assertFalse(tools.findPaths(FindPathsRequest(query = "renamed", useIndex = true)).matches.any { it.path == "plugins/live/renamed.txt" })
    }

    @Test
    fun `properties config tools preserve comments while getting setting listing and removing keys`() {
        val tools = service("mcai-properties")
        tools.writeFile(
            WriteFileRequest(
                "server.properties",
                """
                # Minecraft server properties
                online-mode=false
                motd=Hello world
                """.trimIndent() + "\n",
            ),
        )

        assertEquals("false", tools.configPropertiesGet(ConfigPropertiesGetRequest("server.properties", "online-mode")).value)
        assertEquals(listOf("online-mode", "motd"), tools.configPropertiesList(ConfigPropertiesListRequest("server.properties")).entries.map { it.key })

        val setExisting = tools.configPropertiesSet(ConfigPropertiesSetRequest("server.properties", "online-mode", "true"))
        val setNew = tools.configPropertiesSet(ConfigPropertiesSetRequest("server.properties", "max-players", "20"))
        val removed = tools.configPropertiesRemove(ConfigPropertiesRemoveRequest("server.properties", "motd"))

        val text = tools.root.resolve("server.properties").readText()
        assertEquals("replaced", setExisting.action)
        assertEquals("created", setNew.action)
        assertTrue(removed.removed)
        assertContains(text, "# Minecraft server properties")
        assertContains(text, "online-mode=true")
        assertContains(text, "max-players=20")
        assertFalse(text.contains("motd=Hello world"))
    }

    @Test
    fun `json config tools support pointer get set remove and append operations`() {
        val tools = service("mcai-json")
        tools.writeFile(WriteFileRequest("config/settings.json", """{"enabled":false,"players":[{"name":"Alex"}]}""", createParents = true))

        val created = tools.configJsonSet(ConfigJsonSetRequest("config/settings.json", "/settings/maxPlayers", JsonPrimitive(20)))
        val appended = tools.configJsonAppend(ConfigJsonAppendRequest("config/settings.json", "/players", JsonPrimitive("Sam")))
        val removed = tools.configJsonRemove(ConfigJsonRemoveRequest("config/settings.json", "/enabled"))
        val sam = tools.configJsonGet(ConfigJsonGetRequest("config/settings.json", "/players/1"))

        val json = McpJson.parseToJsonElement(tools.root.resolve("config/settings.json").readText()).jsonObject
        assertEquals("created", created.action)
        assertEquals("appended", appended.action)
        assertEquals("removed", removed.action)
        assertEquals("Sam", sam.value?.jsonPrimitive?.content)
        assertEquals("20", json["settings"]?.jsonObject?.get("maxPlayers")?.jsonPrimitive?.content)
        assertFalse("enabled" in json)
    }

    @Test
    fun `structured config tools reject path traversal`() {
        val tools = service("mcai-config-jail")

        assertFailsWith<PathOutsideServerRootException> {
            tools.configPropertiesSet(ConfigPropertiesSetRequest("../server.properties", "online-mode", "true"))
        }
        assertFailsWith<PathOutsideServerRootException> {
            tools.configJsonSet(ConfigJsonSetRequest("/tmp/settings.json", "/enabled", JsonPrimitive(true)))
        }
    }

    private fun withHttpServer(body: String, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/file")
        } finally {
            server.stop(0)
        }
    }

    private fun withRedirectServer(block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${exchange.localAddress.port}/file")
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, -1)
            exchange.close()
        }
        server.createContext("/file") { exchange ->
            val bytes = "redirected".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://localhost:${server.address.port}/redirect")
        } finally {
            server.stop(0)
        }
    }

    private fun withSlowHttpServer(block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow") { exchange ->
            exchange.sendResponseHeaders(200, 8)
            exchange.responseBody.write("one".toByteArray())
            exchange.responseBody.flush()
            Thread.sleep(1_000)
            exchange.responseBody.use { it.write("two".toByteArray()) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/slow")
        } finally {
            server.stop(0)
        }
    }

    private fun hasDownloadTempFile(tools: FileSystemTools): Boolean =
        Files.walk(tools.root).use { stream ->
            stream.anyMatch { it.fileName.toString().contains(".mcai-download") }
        }
}
