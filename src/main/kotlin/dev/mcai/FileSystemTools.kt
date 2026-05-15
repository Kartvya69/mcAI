package dev.mcai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.RandomAccessFile
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.asSequence

class PathOutsideServerRootException(path: String) : IllegalArgumentException("Path is outside the Minecraft server root: $path")
class FileLimitExceededException(message: String) : IllegalArgumentException(message)

@Serializable
data class ReadFileRequest(val path: String, val encoding: String = "text", val offset: Long = 0, val length: Int? = null)

@Serializable
data class ReadManyFilesRequest(val paths: List<String>, val encoding: String = "text", val maxBytesPerFile: Int? = null)

@Serializable
data class WriteFileRequest(val path: String, val content: String, val encoding: String = "text", val createParents: Boolean = false)

@Serializable
data class EditFileRequest(val path: String, val oldText: String, val newText: String, val replaceAll: Boolean = false)

@Serializable
data class AppendFileRequest(val path: String, val content: String, val encoding: String = "text", val createParents: Boolean = false)

@Serializable
data class DownloadFileRequest(
    val url: String,
    val path: String,
    val overwrite: Boolean = true,
    val createParents: Boolean = false,
    val sha256: String? = null,
)

@Serializable
data class ListDirectoryRequest(val path: String = ".")

@Serializable
data class DirectoryTreeRequest(val path: String = ".", val maxDepth: Int = 10)

@Serializable
data class CreateDirectoryRequest(val path: String)

@Serializable
data class MoveRequest(val source: String, val destination: String, val overwrite: Boolean = true, val createParents: Boolean = false)

@Serializable
data class CopyRequest(val source: String, val destination: String, val overwrite: Boolean = true, val createParents: Boolean = false)

@Serializable
data class DeleteRequest(val path: String, val recursive: Boolean = false)

@Serializable
data class StatRequest(val path: String)

@Serializable
data class SearchFilesRequest(val path: String = ".", val query: String, val glob: String = "**/*", val maxResults: Int = 100)

@Serializable
data class FindPathsRequest(
    val query: String,
    val path: String = ".",
    val glob: String = "**/*",
    val maxResults: Int = 100,
    val includeDirectories: Boolean = false,
    val useIndex: Boolean = true,
)

@Serializable
data class SearchContentRequest(val path: String = ".", val query: String, val regex: Boolean = false, val glob: String = "**/*", val maxResults: Int = 100)

@Serializable
data class TailFileRequest(val path: String, val lines: Int = 100)

@Serializable
data class ConfigPropertiesGetRequest(val path: String, val key: String)

@Serializable
data class ConfigPropertiesSetRequest(val path: String, val key: String, val value: String)

@Serializable
data class ConfigPropertiesRemoveRequest(val path: String, val key: String)

@Serializable
data class ConfigPropertiesListRequest(val path: String)

@Serializable
data class ConfigJsonGetRequest(val path: String, val pointer: String)

@Serializable
data class ConfigJsonSetRequest(val path: String, val pointer: String, val value: JsonElement)

@Serializable
data class ConfigJsonRemoveRequest(val path: String, val pointer: String)

@Serializable
data class ConfigJsonAppendRequest(val path: String, val pointer: String, val value: JsonElement)

@Serializable
data class FileReadResult(val path: String, val encoding: String, val content: String, val bytesRead: Int, val offset: Long, val truncated: Boolean)

@Serializable
data class ManyFilesReadResult(val files: List<FileReadResult>)

@Serializable
data class FileWriteResult(val path: String, val bytesWritten: Int)

@Serializable
data class DownloadFileResult(
    val path: String,
    val url: String,
    val finalUrl: String,
    val statusCode: Int,
    val bytesWritten: Int,
    val sha256: String,
)

@Serializable
data class EditFileResult(val path: String, val replacements: Int)

@Serializable
data class DirectoryEntry(val name: String, val path: String, val type: String, val size: Long)

@Serializable
data class ListDirectoryResult(val path: String, val entries: List<DirectoryEntry>)

@Serializable
data class DirectoryTreeResult(val path: String, val entries: List<DirectoryEntry>)

@Serializable
data class CreateDirectoryResult(val path: String)

@Serializable
data class MoveResult(val source: String, val destination: String)

@Serializable
data class CopyResult(val source: String, val destination: String)

@Serializable
data class DeleteResult(val path: String, val deleted: Boolean)

@Serializable
data class StatResult(
    val path: String,
    val type: String,
    val size: Long,
    val lastModifiedMillis: Long,
    val createdMillis: Long,
    val readable: Boolean,
    val writable: Boolean,
)

@Serializable
data class SearchFileMatch(val path: String, val type: String, val size: Long)

@Serializable
data class SearchFilesResult(val matches: List<SearchFileMatch>)

@Serializable
data class FindPathMatch(val path: String, val type: String, val size: Long, val lastModifiedMillis: Long)

@Serializable
data class PathIndexFreshness(val source: String, val indexedAt: Long?, val isIndexReady: Boolean, val mayBeStale: Boolean)

@Serializable
data class FindPathsResult(val matches: List<FindPathMatch>, val freshness: PathIndexFreshness)

@Serializable
data class SearchContentMatch(val path: String, val lineNumber: Int, val lineText: String)

@Serializable
data class SearchContentResult(val matches: List<SearchContentMatch>)

@Serializable
data class TailFileResult(val path: String, val lines: List<String>)

@Serializable
data class ConfigPropertyResult(val path: String, val key: String, val value: String?, val found: Boolean)

@Serializable
data class ConfigPropertyEntry(val key: String, val value: String)

@Serializable
data class ConfigPropertiesListResult(val path: String, val entries: List<ConfigPropertyEntry>)

@Serializable
data class ConfigPropertyMutationResult(val path: String, val key: String, val value: String? = null, val action: String, val removed: Boolean = false)

@Serializable
data class ConfigJsonValueResult(val path: String, val pointer: String, val value: JsonElement?, val found: Boolean)

@Serializable
data class ConfigJsonMutationResult(val path: String, val pointer: String, val action: String)

class FileSystemTools(
    root: Path,
    private val limits: McAiLimits,
    private val downloadPolicy: McAiDownloadPolicy = McAiDownloadPolicy(),
    private val pathIndexConfig: McAiPathIndexConfig = McAiPathIndexConfig(),
) : AutoCloseable {
    val root: Path = root.toAbsolutePath().normalize().also { it.createDirectories() }
    private val rootReal: Path = this.root.toRealPath()
    private val cidrAllowlist = downloadPolicy.trustedCidrs.map { CidrBlock.parse(it) }
    private val trustedHosts = downloadPolicy.trustedHosts.map { it.lowercase() }.toSet()
    private val json = Json { prettyPrint = true }
    private val indexEntries = ConcurrentHashMap<String, FindPathMatch>()
    private val watchService: WatchService? = runCatching { rootReal.fileSystem.newWatchService() }.getOrNull()
    private val watchKeys = ConcurrentHashMap<WatchKey, Path>()
    private val watchedDirectories = ConcurrentHashMap<Path, WatchKey>()
    private val indexExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mcai-path-index").apply { isDaemon = true }
    }
    private val watchExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mcai-path-index-watch").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    @Volatile
    private var pathIndexReady: Boolean = false

    @Volatile
    private var pathIndexMayBeStale: Boolean = true

    @Volatile
    private var pathIndexIndexedAt: Long? = null

    init {
        startPathIndex()
    }

    fun readFile(request: ReadFileRequest): FileReadResult {
        val file = resolveExisting(request.path)
        require(Files.isRegularFile(file)) { "Not a regular file: ${request.path}" }
        require(request.offset >= 0) { "offset must be >= 0" }

        val fileSize = Files.size(file)
        val available = (fileSize - request.offset).coerceAtLeast(0)
        val requested = request.length?.toLong() ?: available
        require(requested >= 0) { "length must be >= 0" }
        if (requested > limits.maxReadBytes) {
            throw FileLimitExceededException("Read exceeds maxReadBytes (${limits.maxReadBytes})")
        }

        val bytes = RandomAccessFile(file.toFile(), "r").use { raf ->
            raf.seek(request.offset.coerceAtMost(fileSize))
            val size = requested.coerceAtMost(available).toInt()
            ByteArray(size).also { raf.readFully(it) }
        }

        return FileReadResult(
            path = request.path,
            encoding = request.encoding,
            content = encode(bytes, request.encoding),
            bytesRead = bytes.size,
            offset = request.offset,
            truncated = requested < available,
        )
    }

    fun readManyFiles(request: ReadManyFilesRequest): ManyFilesReadResult =
        ManyFilesReadResult(
            request.paths.map {
                readFile(ReadFileRequest(path = it, encoding = request.encoding, length = request.maxBytesPerFile))
            },
        )

    fun writeFile(request: WriteFileRequest): FileWriteResult {
        val bytes = decode(request.content, request.encoding)
        ensureWriteLimit(bytes.size)
        val file = resolveForWrite(request.path)
        if (request.createParents) file.parent?.createDirectories()
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        file.parent?.let { updateIndexedPath(it) }
        updateIndexedPath(file)
        return FileWriteResult(request.path, bytes.size)
    }

    fun editFile(request: EditFileRequest): EditFileResult {
        require(request.oldText.isNotEmpty()) { "oldText must not be empty" }
        val file = resolveExisting(request.path)
        if (Files.size(file) > limits.maxReadBytes) {
            throw FileLimitExceededException("Read exceeds maxReadBytes (${limits.maxReadBytes})")
        }
        val original = file.readText()
        val replacements = countOccurrences(original, request.oldText)
        require(replacements > 0) { "oldText was not found in ${request.path}" }
        val updated = if (request.replaceAll) {
            original.replace(request.oldText, request.newText)
        } else {
            original.replaceFirst(request.oldText, request.newText)
        }
        ensureWriteLimit(updated.toByteArray(StandardCharsets.UTF_8).size)
        writeTextAtomically(file, updated)
        return EditFileResult(request.path, if (request.replaceAll) replacements else 1)
    }

    fun appendFile(request: AppendFileRequest): FileWriteResult {
        val bytes = decode(request.content, request.encoding)
        ensureWriteLimit(bytes.size)
        val file = resolveForWrite(request.path)
        if (request.createParents) file.parent?.createDirectories()
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
        file.parent?.let { updateIndexedPath(it) }
        updateIndexedPath(file)
        return FileWriteResult(request.path, bytes.size)
    }

    fun downloadFile(request: DownloadFileRequest): DownloadFileResult {
        val uri = URI.create(request.url)
        require(uri.scheme == "http" || uri.scheme == "https") { "Only http and https URLs are supported" }
        require(downloadPolicy.connectTimeoutMillis > 0) { "connectTimeoutMillis must be > 0" }
        require(downloadPolicy.readTimeoutMillis > 0) { "readTimeoutMillis must be > 0" }
        require(downloadPolicy.requestTimeoutMillis > 0) { "requestTimeoutMillis must be > 0" }
        require(downloadPolicy.maxRedirects >= 0) { "maxRedirects must be >= 0" }

        val destination = resolveForWrite(request.path)
        val parent = destination.parent ?: throw PathOutsideServerRootException(request.path)
        if (request.createParents) parent.createDirectories()
        if (!request.overwrite && destination.exists()) {
            throw java.nio.file.FileAlreadyExistsException(destination.toString())
        }

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(downloadPolicy.requestTimeoutMillis.toLong())
        val digest = MessageDigest.getInstance("SHA-256")
        val temp = Files.createTempFile(parent, ".${destination.fileName}.", ".mcai-download")
        var bytesWritten = 0
        var currentUri = uri
        var finalUri = uri
        var statusCode = 0
        try {
            for (redirectCount in 0..downloadPolicy.maxRedirects) {
                ensureRequestDeadline(deadlineNanos)
                validateDownloadTarget(currentUri)
                val connection = currentUri.toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = downloadPolicy.connectTimeoutMillis
                connection.readTimeout = remainingTimeoutMillis(deadlineNanos).coerceAtMost(downloadPolicy.readTimeoutMillis)
                connection.requestMethod = "GET"
                try {
                    statusCode = connection.responseCode
                    if (statusCode in 300..399) {
                        val location = connection.getHeaderField("Location")
                        require(!location.isNullOrBlank()) { "HTTP redirect missing Location header" }
                        if (redirectCount == downloadPolicy.maxRedirects) {
                            throw IllegalArgumentException("Too many HTTP redirects")
                        }
                        currentUri = currentUri.resolve(location)
                        require(currentUri.scheme == "http" || currentUri.scheme == "https") {
                            "Final URL scheme is not supported: ${currentUri.scheme}"
                        }
                        continue
                    }

                    require(statusCode in 200..299) { "HTTP download failed with status $statusCode" }
                    finalUri = currentUri

                    val contentLength = connection.getHeaderFieldLong("Content-Length", -1)
                    if (contentLength > limits.maxWriteBytes) {
                        throw FileLimitExceededException("Download exceeds maxWriteBytes (${limits.maxWriteBytes})")
                    }

                    connection.inputStream.use { input ->
                        Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                            val buffer = ByteArray(DEFAULT_DOWNLOAD_BUFFER_SIZE)
                            while (true) {
                                ensureRequestDeadline(deadlineNanos)
                                val read = input.read(buffer)
                                if (read < 0) break
                                bytesWritten += read
                                if (bytesWritten > limits.maxWriteBytes) {
                                    throw FileLimitExceededException("Download exceeds maxWriteBytes (${limits.maxWriteBytes})")
                                }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    break
                } finally {
                    connection.disconnect()
                }
            }

            val actualSha256 = digest.digest().toHex()
            request.sha256?.let { expected ->
                require(actualSha256.equals(expected, ignoreCase = true)) {
                    "SHA-256 mismatch: expected $expected but downloaded $actualSha256"
                }
            }

            val options = if (request.overwrite) {
                arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                arrayOf(StandardCopyOption.ATOMIC_MOVE)
            }
            Files.move(temp, destination, *options)
            destination.parent?.let { updateIndexedPath(it) }
            updateIndexedPath(destination)

            return DownloadFileResult(
                path = request.path,
                url = request.url,
                finalUrl = finalUri.toString(),
                statusCode = statusCode,
                bytesWritten = bytesWritten,
                sha256 = actualSha256,
            )
        } finally {
            temp.deleteIfExists()
        }
    }

    fun listDirectory(request: ListDirectoryRequest): ListDirectoryResult {
        val directory = resolveExisting(request.path)
        require(directory.isDirectory()) { "Not a directory: ${request.path}" }
        return Files.list(directory).use { stream ->
            ListDirectoryResult(
                path = request.path,
                entries = stream.asSequence()
                    .sortedBy { it.fileName.toString() }
                    .take(limits.maxDirectoryEntries)
                    .map { entry(directory, it) }
                    .toList(),
            )
        }
    }

    fun directoryTree(request: DirectoryTreeRequest): DirectoryTreeResult {
        val directory = resolveExisting(request.path)
        require(directory.isDirectory()) { "Not a directory: ${request.path}" }
        return Files.walk(directory, request.maxDepth).use { stream ->
            DirectoryTreeResult(
                path = request.path,
                entries = stream.asSequence()
                    .filter { it != directory }
                    .sortedBy { directory.relativize(it).toString() }
                    .take(limits.maxDirectoryEntries)
                    .map { entry(directory, it) }
                    .toList(),
            )
        }
    }

    fun createDirectory(request: CreateDirectoryRequest): CreateDirectoryResult {
        val directory = resolveForWrite(request.path)
        directory.createDirectories()
        updateIndexedPath(directory)
        return CreateDirectoryResult(request.path)
    }

    fun move(request: MoveRequest): MoveResult {
        val source = resolveExisting(request.source)
        val destination = resolveForWrite(request.destination)
        if (request.createParents) destination.parent?.createDirectories()
        val options = if (request.overwrite) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
        Files.move(source, destination, *options)
        removeIndexedPath(source)
        updateIndexedPath(destination)
        return MoveResult(request.source, request.destination)
    }

    fun copy(request: CopyRequest): CopyResult {
        val source = resolveExisting(request.source)
        val destination = resolveForWrite(request.destination)
        if (request.createParents) destination.parent?.createDirectories()
        val options = if (request.overwrite) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
        Files.copy(source, destination, *options)
        updateIndexedPath(destination)
        return CopyResult(request.source, request.destination)
    }

    fun delete(request: DeleteRequest): DeleteResult {
        val target = resolveExisting(request.path)
        require(target != rootReal) { "Refusing to delete the server root" }
        if (Files.isDirectory(target) && request.recursive) {
            Files.walk(target).use { stream ->
                stream.asSequence().sortedByDescending { it.nameCount }.forEach { Files.deleteIfExists(it) }
            }
        } else {
            Files.delete(target)
        }
        removeIndexedPath(target)
        return DeleteResult(request.path, true)
    }

    fun stat(request: StatRequest): StatResult {
        val target = resolveExisting(request.path)
        val attributes = Files.readAttributes(target, BasicFileAttributes::class.java)
        return StatResult(
            path = request.path,
            type = typeOf(target),
            size = attributes.size(),
            lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
            createdMillis = attributes.creationTime().toMillis(),
            readable = Files.isReadable(target),
            writable = Files.isWritable(target),
        )
    }

    fun searchFiles(request: SearchFilesRequest): SearchFilesResult {
        val base = resolveExisting(request.path)
        val matcher = FileSystems.getDefault().getPathMatcher("glob:${request.glob}")
        val query = request.query.lowercase()
        val matches = Files.walk(base).use { stream ->
            stream.asSequence()
                .filter { it != base }
                .filter { matcher.matches(base.relativize(it)) }
                .filter { base.relativize(it).toString().replace('\\', '/').lowercase().contains(query) }
                .take(request.maxResults)
                .map { SearchFileMatch(relative(base, it), typeOf(it), if (Files.isDirectory(it)) 0 else Files.size(it)) }
                .toList()
        }
        return SearchFilesResult(matches)
    }

    fun findPaths(request: FindPathsRequest): FindPathsResult {
        val base = resolveExisting(request.path)
        require(Files.isDirectory(base)) { "Not a directory: ${request.path}" }
        val baseRelative = relative(rootReal, base)
        val matcher = FileSystems.getDefault().getPathMatcher("glob:${request.glob}")
        val query = request.query.lowercase()
        val useReadyIndex = request.useIndex && pathIndexReady
        val matches = if (useReadyIndex) {
            indexEntries.values.asSequence()
        } else {
            liveFindPaths(base)
        }
            .filter { request.includeDirectories || it.type != "directory" }
            .filter { isUnderBase(it.path, baseRelative) }
            .filter { matcher.matches(Path.of(pathWithinBase(it.path, baseRelative))) }
            .filter { it.path.lowercase().contains(query) }
            .sortedWith(compareBy<FindPathMatch> { pathPriority(it.path) }.thenBy { it.path })
            .take(request.maxResults.coerceAtLeast(0))
            .toList()

        val freshness = PathIndexFreshness(
            source = if (useReadyIndex) "index" else "live",
            indexedAt = pathIndexIndexedAt,
            isIndexReady = pathIndexReady,
            mayBeStale = request.useIndex && (!pathIndexReady || pathIndexMayBeStale),
        )
        return FindPathsResult(matches, freshness)
    }

    fun searchContent(request: SearchContentRequest): SearchContentResult {
        val base = resolveExisting(request.path)
        val matcher = FileSystems.getDefault().getPathMatcher("glob:${request.glob}")
        val regex = if (request.regex) Regex(request.query) else null
        val matches = mutableListOf<SearchContentMatch>()
        Files.walk(base).use { stream ->
            stream.asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { matcher.matches(base.relativize(it)) }
                .forEach { file ->
                    if (matches.size >= request.maxResults) return@forEach
                    file.toFile().useLines { lines ->
                        lines.forEachIndexed { index, line ->
                            if (matches.size < request.maxResults && line.matchesQuery(request.query, regex)) {
                                matches += SearchContentMatch(relative(base, file), index + 1, line)
                            }
                        }
                    }
                }
        }
        return SearchContentResult(matches)
    }

    fun tailFile(request: TailFileRequest): TailFileResult {
        val file = resolveExisting(request.path)
        require(Files.isRegularFile(file)) { "Not a regular file: ${request.path}" }
        val lines = file.toFile().readLines().takeLast(request.lines.coerceAtLeast(0))
        return TailFileResult(request.path, lines)
    }

    fun configPropertiesGet(request: ConfigPropertiesGetRequest): ConfigPropertyResult {
        val (file, text) = readUtf8Config(request.path)
        val line = text.split('\n').firstOrNull { parsePropertyLine(it)?.key == request.key }
        val value = line?.let { parsePropertyLine(it)?.value }
        return ConfigPropertyResult(relative(rootReal, file), request.key, value, value != null)
    }

    fun configPropertiesList(request: ConfigPropertiesListRequest): ConfigPropertiesListResult {
        val (file, text) = readUtf8Config(request.path)
        val entries = text.split('\n').mapNotNull { line ->
            parsePropertyLine(line)?.let { ConfigPropertyEntry(it.key, it.value) }
        }
        return ConfigPropertiesListResult(relative(rootReal, file), entries)
    }

    fun configPropertiesSet(request: ConfigPropertiesSetRequest): ConfigPropertyMutationResult {
        require(request.key.isNotBlank()) { "key must not be blank" }
        require(!request.key.contains('\n') && !request.value.contains('\n')) { "properties keys and values must be single-line" }
        val (file, text) = readUtf8Config(request.path)
        val lines = text.split('\n').toMutableList()
        val existingIndex = lines.indexOfFirst { parsePropertyLine(it)?.key == request.key }
        val action = if (existingIndex >= 0) {
            val parsed = parsePropertyLine(lines[existingIndex]) ?: error("unreachable")
            lines[existingIndex] = lines[existingIndex].substring(0, parsed.valueStart) + request.value
            "replaced"
        } else {
            if (lines.lastOrNull() == "") {
                lines[lines.lastIndex] = "${request.key}=${request.value}"
                lines += ""
            } else {
                lines += "${request.key}=${request.value}"
            }
            "created"
        }
        writeTextAtomically(file, lines.joinToString("\n"))
        return ConfigPropertyMutationResult(relative(rootReal, file), request.key, request.value, action)
    }

    fun configPropertiesRemove(request: ConfigPropertiesRemoveRequest): ConfigPropertyMutationResult {
        val (file, text) = readUtf8Config(request.path)
        val lines = text.split('\n').toMutableList()
        val originalSize = lines.size
        lines.removeAll { parsePropertyLine(it)?.key == request.key }
        val removed = lines.size != originalSize
        if (removed) writeTextAtomically(file, lines.joinToString("\n"))
        return ConfigPropertyMutationResult(relative(rootReal, file), request.key, action = if (removed) "removed" else "missing", removed = removed)
    }

    fun configJsonGet(request: ConfigJsonGetRequest): ConfigJsonValueResult {
        val (file, element) = readJsonConfig(request.path)
        val value = getJsonPointer(element, parseJsonPointer(request.pointer))
        return ConfigJsonValueResult(relative(rootReal, file), request.pointer, value, value != null)
    }

    fun configJsonSet(request: ConfigJsonSetRequest): ConfigJsonMutationResult {
        val (file, element) = readJsonConfig(request.path)
        val mutation = setJsonPointer(element, parseJsonPointer(request.pointer), request.value)
        writeJsonConfig(file, mutation.element)
        return ConfigJsonMutationResult(relative(rootReal, file), request.pointer, mutation.action)
    }

    fun configJsonRemove(request: ConfigJsonRemoveRequest): ConfigJsonMutationResult {
        val (file, element) = readJsonConfig(request.path)
        val mutation = removeJsonPointer(element, parseJsonPointer(request.pointer))
        if (mutation.action == "removed") writeJsonConfig(file, mutation.element)
        return ConfigJsonMutationResult(relative(rootReal, file), request.pointer, mutation.action)
    }

    fun configJsonAppend(request: ConfigJsonAppendRequest): ConfigJsonMutationResult {
        val (file, element) = readJsonConfig(request.path)
        val mutation = appendJsonPointer(element, parseJsonPointer(request.pointer), request.value)
        writeJsonConfig(file, mutation.element)
        return ConfigJsonMutationResult(relative(rootReal, file), request.pointer, mutation.action)
    }

    fun reconcilePathIndex() {
        rebuildPathIndex()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        watchService?.close()
        indexExecutor.shutdownNow()
        watchExecutor.shutdownNow()
    }

    private fun startPathIndex() {
        indexExecutor.execute {
            runCatching { rebuildPathIndex() }
                .onFailure { pathIndexMayBeStale = true }
        }
        val intervalMillis = pathIndexConfig.reconciliationIntervalMillis.coerceAtLeast(1)
        indexExecutor.scheduleWithFixedDelay(
            { runCatching { rebuildPathIndex() }.onFailure { pathIndexMayBeStale = true } },
            intervalMillis,
            intervalMillis,
            TimeUnit.MILLISECONDS,
        )
        watchService?.let {
            watchExecutor.execute { watchPathChanges(it) }
        }
    }

    private fun rebuildPathIndex() {
        val rebuilt = LinkedHashMap<String, FindPathMatch>()
        registerWatchDirectory(rootReal)
        Files.walk(rootReal).use { stream ->
            stream.asSequence()
                .filter { it != rootReal }
                .filterNot { isExcludedFromIndex(rootRelativePath(it)) }
                .forEach { path ->
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) registerWatchDirectory(path)
                    rebuilt[rootRelativePath(path)] = indexEntry(path)
                }
        }
        indexEntries.clear()
        indexEntries.putAll(rebuilt)
        pathIndexReady = true
        pathIndexMayBeStale = false
        pathIndexIndexedAt = System.currentTimeMillis()
    }

    private fun watchPathChanges(service: WatchService) {
        while (!closed.get()) {
            val key = try {
                service.take()
            } catch (_: InterruptedException) {
                return
            } catch (_: ClosedWatchServiceException) {
                return
            }
            val directory = watchKeys[key]
            if (directory == null) {
                key.reset()
                continue
            }
            key.pollEvents().forEach { event ->
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    pathIndexMayBeStale = true
                    return@forEach
                }
                val context = event.context() as? Path ?: return@forEach
                val changed = directory.resolve(context).normalize()
                when (event.kind()) {
                    StandardWatchEventKinds.ENTRY_DELETE -> removeIndexedPath(changed)
                    StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY -> updateIndexedPath(changed)
                }
            }
            if (!key.reset()) {
                watchKeys.remove(key)
            }
        }
    }

    private fun registerWatchDirectory(directory: Path) {
        val service = watchService ?: return
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return
        if (isExcludedFromIndex(rootRelativePath(directory))) return
        watchedDirectories.computeIfAbsent(directory) {
            val key = directory.register(
                service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY,
            )
            watchKeys[key] = directory
            key
        }
    }

    private fun updateIndexedPath(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(root)) return
        val relativePath = rootRelativePath(normalized)
        if (relativePath.isBlank() || isExcludedFromIndex(relativePath)) return
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            removeIndexedPath(normalized)
            return
        }
        if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            registerWatchDirectory(normalized)
            Files.walk(normalized).use { stream ->
                stream.asSequence()
                    .filterNot { isExcludedFromIndex(rootRelativePath(it)) }
                    .forEach { indexEntries[rootRelativePath(it)] = indexEntry(it) }
            }
        } else {
            indexEntries[relativePath] = indexEntry(normalized)
        }
    }

    private fun removeIndexedPath(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        val relativePath = rootRelativePath(normalized)
        if (relativePath.isBlank()) return
        indexEntries.keys.removeIf { it == relativePath || it.startsWith("$relativePath/") }
    }

    private fun liveFindPaths(base: Path): Sequence<FindPathMatch> =
        Files.walk(base).use { stream ->
            stream.asSequence()
                .filter { it != base }
                .filterNot { isExcludedFromIndex(rootRelativePath(it)) }
                .map { indexEntry(it) }
                .toList()
                .asSequence()
        }

    private fun indexEntry(path: Path): FindPathMatch {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return FindPathMatch(
            path = rootRelativePath(path),
            type = typeOf(path),
            size = if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) 0 else attributes.size(),
            lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
        )
    }

    private fun isExcludedFromIndex(relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        val normalized = relativePath.replace('\\', '/')
        return pathIndexConfig.excludeGlobs.any { pattern ->
            val normalizedPattern = pattern.replace('\\', '/')
            val prefix = normalizedPattern.removeSuffix("/**")
            normalized == prefix ||
                FileSystems.getDefault().getPathMatcher("glob:$normalizedPattern").matches(Path.of(normalized))
        }
    }

    private fun isUnderBase(path: String, baseRelative: String): Boolean =
        baseRelative.isBlank() || path == baseRelative || path.startsWith("$baseRelative/")

    private fun pathWithinBase(path: String, baseRelative: String): String =
        if (baseRelative.isBlank()) path else path.removePrefix("$baseRelative/")

    private fun pathPriority(path: String): Int =
        when {
            path.startsWith("plugins/") -> 0
            path.startsWith("config/") -> 1
            path.startsWith("world/datapacks/") -> 2
            "/" !in path && (path.endsWith(".properties") || path.endsWith(".yml") || path.endsWith(".json")) -> 3
            path.startsWith("logs/") -> 4
            else -> 5
        }

    private fun validateDownloadTarget(uri: URI) {
        require(uri.scheme == "http" || uri.scheme == "https") { "Only http and https URLs are supported" }
        if (!downloadPolicy.blockPrivateNetworks) return
        val host = uri.host ?: throw IllegalArgumentException("Download URL host is required")
        if (host.lowercase() in trustedHosts) return
        val addresses = InetAddress.getAllByName(host)
        val blocked = addresses.firstOrNull { address ->
            cidrAllowlist.none { it.contains(address) } && address.isInternalAddress()
        }
        if (blocked != null) {
            throw IllegalArgumentException("Download target resolves to a blocked private/internal address: $host -> ${blocked.hostAddress}")
        }
    }

    private fun ensureRequestDeadline(deadlineNanos: Long) {
        if (System.nanoTime() > deadlineNanos) {
            throw java.net.SocketTimeoutException("Download exceeded requestTimeoutMillis (${downloadPolicy.requestTimeoutMillis})")
        }
    }

    private fun remainingTimeoutMillis(deadlineNanos: Long): Int {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        return remaining.coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun readUtf8Config(path: String): Pair<Path, String> {
        val file = resolveExisting(path)
        require(Files.isRegularFile(file)) { "Not a regular file: $path" }
        if (Files.size(file) > limits.maxReadBytes) {
            throw FileLimitExceededException("Read exceeds maxReadBytes (${limits.maxReadBytes})")
        }
        return file to file.readText()
    }

    private fun readJsonConfig(path: String): Pair<Path, JsonElement> {
        val (file, text) = readUtf8Config(path)
        return file to json.parseToJsonElement(text)
    }

    private fun writeJsonConfig(file: Path, element: JsonElement) {
        writeTextAtomically(file, json.encodeToString(JsonElement.serializer(), element) + "\n")
    }

    private fun writeTextAtomically(file: Path, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        ensureWriteLimit(bytes.size)
        val temp = Files.createTempFile(file.parent, ".${file.fileName}.", ".mcai-tmp")
        try {
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            file.parent?.let { updateIndexedPath(it) }
            updateIndexedPath(file)
        } finally {
            temp.deleteIfExists()
        }
    }

    private fun parsePropertyLine(line: String): ParsedPropertyLine? {
        val first = line.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return null
        if (line[first] == '#' || line[first] == '!') return null
        val separator = line.indexOfFirstUnescaped(first) { it == '=' || it == ':' }
        if (separator < 0) return null
        val key = line.substring(first, separator).trimEnd()
        if (key.isBlank()) return null
        var valueStart = separator + 1
        while (valueStart < line.length && line[valueStart].isWhitespace()) valueStart += 1
        return ParsedPropertyLine(key = key, value = line.substring(valueStart), valueStart = valueStart)
    }

    private fun parseJsonPointer(pointer: String): List<String> {
        if (pointer.isEmpty()) return emptyList()
        require(pointer.startsWith("/")) { "JSON pointer must be empty or start with /" }
        return pointer.drop(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
    }

    private fun getJsonPointer(element: JsonElement, tokens: List<String>): JsonElement? {
        var current = element
        for (token in tokens) {
            current = when (current) {
                is JsonObject -> current[token] ?: return null
                is JsonArray -> current.getOrNull(token.toIntOrNull() ?: return null) ?: return null
                else -> return null
            }
        }
        return current
    }

    private fun setJsonPointer(element: JsonElement, tokens: List<String>, value: JsonElement): JsonMutation {
        if (tokens.isEmpty()) return JsonMutation(value, "replaced")
        val head = tokens.first()
        val tail = tokens.drop(1)
        return when (element) {
            is JsonObject -> {
                val existing = element[head]
                val child = existing ?: JsonObject(emptyMap())
                val childMutation = setJsonPointer(child, tail, value)
                val action = if (tail.isEmpty() && existing == null) "created" else if (tail.isEmpty()) "replaced" else childMutation.action
                JsonMutation(JsonObject(element + (head to childMutation.element)), action)
            }
            is JsonArray -> {
                val index = head.toIntOrNull() ?: throw IllegalArgumentException("JSON array path segment must be an integer: $head")
                require(index in element.indices) { "JSON array index out of bounds: $index" }
                val childMutation = setJsonPointer(element[index], tail, value)
                JsonMutation(JsonArray(element.mapIndexed { i, child -> if (i == index) childMutation.element else child }), childMutation.action)
            }
            JsonNull -> setJsonPointer(JsonObject(emptyMap()), tokens, value)
            else -> throw IllegalArgumentException("Cannot set child value under JSON primitive at segment: $head")
        }
    }

    private fun removeJsonPointer(element: JsonElement, tokens: List<String>): JsonMutation {
        require(tokens.isNotEmpty()) { "Cannot remove the JSON document root" }
        val head = tokens.first()
        val tail = tokens.drop(1)
        return when (element) {
            is JsonObject -> {
                if (tail.isEmpty()) {
                    if (head !in element) return JsonMutation(element, "missing")
                    JsonMutation(JsonObject(element - head), "removed")
                } else {
                    val child = element[head] ?: return JsonMutation(element, "missing")
                    val childMutation = removeJsonPointer(child, tail)
                    if (childMutation.action != "removed") JsonMutation(element, childMutation.action) else JsonMutation(JsonObject(element + (head to childMutation.element)), "removed")
                }
            }
            is JsonArray -> {
                val index = head.toIntOrNull() ?: throw IllegalArgumentException("JSON array path segment must be an integer: $head")
                if (index !in element.indices) return JsonMutation(element, "missing")
                if (tail.isEmpty()) {
                    JsonMutation(JsonArray(element.filterIndexed { i, _ -> i != index }), "removed")
                } else {
                    val childMutation = removeJsonPointer(element[index], tail)
                    if (childMutation.action != "removed") JsonMutation(element, childMutation.action) else JsonMutation(JsonArray(element.mapIndexed { i, child -> if (i == index) childMutation.element else child }), "removed")
                }
            }
            else -> JsonMutation(element, "missing")
        }
    }

    private fun appendJsonPointer(element: JsonElement, tokens: List<String>, value: JsonElement): JsonMutation {
        if (tokens.isEmpty()) {
            require(element is JsonArray) { "JSON pointer target is not an array" }
            return JsonMutation(JsonArray(element + value), "appended")
        }
        val head = tokens.first()
        val tail = tokens.drop(1)
        return when (element) {
            is JsonObject -> {
                val child = element[head] ?: throw IllegalArgumentException("JSON pointer target does not exist: $head")
                val childMutation = appendJsonPointer(child, tail, value)
                JsonMutation(JsonObject(element + (head to childMutation.element)), childMutation.action)
            }
            is JsonArray -> {
                val index = head.toIntOrNull() ?: throw IllegalArgumentException("JSON array path segment must be an integer: $head")
                require(index in element.indices) { "JSON array index out of bounds: $index" }
                val childMutation = appendJsonPointer(element[index], tail, value)
                JsonMutation(JsonArray(element.mapIndexed { i, child -> if (i == index) childMutation.element else child }), childMutation.action)
            }
            else -> throw IllegalArgumentException("Cannot append under JSON primitive at segment: $head")
        }
    }

    private fun resolveExisting(path: String): Path {
        val resolved = resolveInsideRoot(path)
        if (!resolved.exists()) throw java.nio.file.NoSuchFileException(resolved.toString())
        val real = resolved.toRealPath()
        if (!real.startsWith(rootReal)) throw PathOutsideServerRootException(path)
        return real
    }

    private fun resolveForWrite(path: String): Path {
        val resolved = resolveInsideRoot(path)
        val parent = resolved.parent ?: throw PathOutsideServerRootException(path)
        validateNearestExistingAncestor(parent, path)
        if (parent.exists() && !parent.toRealPath().startsWith(rootReal)) throw PathOutsideServerRootException(path)
        if (resolved.exists() && !resolved.toRealPath().startsWith(rootReal)) throw PathOutsideServerRootException(path)
        return resolved
    }

    private fun validateNearestExistingAncestor(parent: Path, originalPath: String) {
        var current: Path? = parent
        while (current != null && !current.exists()) {
            current = current.parent
        }
        if (current == null || !current.toRealPath().startsWith(rootReal)) {
            throw PathOutsideServerRootException(originalPath)
        }
    }

    private fun resolveInsideRoot(path: String): Path {
        val relative = Path.of(path)
        if (relative.isAbsolute) throw PathOutsideServerRootException(path)
        val resolved = root.resolve(relative).normalize()
        if (!resolved.startsWith(root)) throw PathOutsideServerRootException(path)
        return resolved
    }

    private fun entry(base: Path, path: Path): DirectoryEntry =
        DirectoryEntry(path.name, relative(base, path), typeOf(path), if (Files.isDirectory(path)) 0 else Files.size(path))

    private fun relative(base: Path, path: Path): String =
        base.relativize(path).toString().replace('\\', '/')

    private fun rootRelativePath(path: Path): String =
        root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')

    private fun typeOf(path: Path): String =
        when {
            Files.isSymbolicLink(path) -> "symlink"
            Files.isDirectory(path) -> "directory"
            Files.isRegularFile(path) -> "file"
            else -> "other"
        }

    private fun ensureReadLimit(size: Int) {
        if (size > limits.maxReadBytes) throw FileLimitExceededException("Read exceeds maxReadBytes (${limits.maxReadBytes})")
    }

    private fun ensureWriteLimit(size: Int) {
        if (size > limits.maxWriteBytes) throw FileLimitExceededException("Write exceeds maxWriteBytes (${limits.maxWriteBytes})")
    }

    private fun encode(bytes: ByteArray, encoding: String): String =
        when (encoding.lowercase()) {
            "base64" -> Base64.getEncoder().encodeToString(bytes)
            "text" -> bytes.toString(StandardCharsets.UTF_8)
            else -> throw IllegalArgumentException("Unsupported encoding: $encoding")
        }

    private fun decode(content: String, encoding: String): ByteArray =
        when (encoding.lowercase()) {
            "base64" -> Base64.getDecoder().decode(content)
            "text" -> content.toByteArray(StandardCharsets.UTF_8)
            else -> throw IllegalArgumentException("Unsupported encoding: $encoding")
        }

    private fun countOccurrences(value: String, token: String): Int {
        var count = 0
        var index = value.indexOf(token)
        while (index >= 0) {
            count += 1
            index = value.indexOf(token, index + token.length)
        }
        return count
    }

    private fun String.matchesQuery(query: String, regex: Regex?): Boolean =
        regex?.containsMatchIn(this) ?: contains(query, ignoreCase = true)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val DEFAULT_DOWNLOAD_BUFFER_SIZE = 8192
    }
}

private data class ParsedPropertyLine(val key: String, val value: String, val valueStart: Int)

private data class JsonMutation(val element: JsonElement, val action: String)

private fun String.indexOfFirstUnescaped(startIndex: Int, predicate: (Char) -> Boolean): Int {
    var escaped = false
    for (index in startIndex until length) {
        val char = this[index]
        if (escaped) {
            escaped = false
            continue
        }
        if (char == '\\') {
            escaped = true
            continue
        }
        if (predicate(char)) return index
    }
    return -1
}

private fun InetAddress.isInternalAddress(): Boolean =
    isAnyLocalAddress ||
        isLoopbackAddress ||
        isSiteLocalAddress ||
        isLinkLocalAddress ||
        isMulticastAddress ||
        isCloudMetadataAddress() ||
        isUniqueLocalIpv6Address()

private fun InetAddress.isCloudMetadataAddress(): Boolean {
    val bytes = address
    return this is Inet4Address &&
        (
            bytes.contentEquals(byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte())) ||
                bytes.contentEquals(byteArrayOf(169.toByte(), 254.toByte(), 170.toByte(), 2)) ||
                bytes.contentEquals(byteArrayOf(100, 100, 100, 200.toByte()))
            )
}

private fun InetAddress.isUniqueLocalIpv6Address(): Boolean {
    if (this !is Inet6Address) return false
    val first = address.first().toInt() and 0xff
    return first and 0xfe == 0xfc
}

private data class CidrBlock(val network: BigInteger, val prefix: Int, val bits: Int) {
    fun contains(address: InetAddress): Boolean {
        val addressBits = when (address) {
            is Inet4Address -> 32
            is Inet6Address -> 128
            else -> return false
        }
        if (addressBits != bits) return false
        val value = BigInteger(1, address.address)
        val shift = bits - prefix
        return value.shiftRight(shift) == network.shiftRight(shift)
    }

    companion object {
        fun parse(value: String): CidrBlock {
            val parts = value.split("/", limit = 2)
            require(parts.size == 2) { "Trusted CIDR must include a prefix length: $value" }
            val address = InetAddress.getByName(parts[0])
            val bits = when (address) {
                is Inet4Address -> 32
                is Inet6Address -> 128
                else -> throw IllegalArgumentException("Unsupported CIDR address family: $value")
            }
            val prefix = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid CIDR prefix: $value")
            require(prefix in 0..bits) { "CIDR prefix out of range: $value" }
            return CidrBlock(BigInteger(1, address.address), prefix, bits)
        }
    }
}
