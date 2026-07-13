import groovy.json.JsonSlurper
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

private val registry = "ghcr.io"
private val repository = "beiyuge/anitorrent-maven"
private val version = "0.2.1-beiyuge.1"
private val manifestDigest = "sha256:302cbf5563a8021d3957b409f8af1815959cf306bb160c4707970496371d0101"
private val layerDigest = "sha256:c6eec0ad6fc9dce78c6268ed173efafe4f922d1e002dbfa86a96b07f8de9f443"
private val layerMediaType = "application/vnd.beiyuge.maven.repository.layer.v1+gzip"
private val manifestMediaType = "application/vnd.oci.image.manifest.v1+json"

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

private fun Path.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this).buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun openRegistryConnection(
    uri: URI,
    accept: String? = null,
    token: String? = null,
): HttpURLConnection = (uri.toURL().openConnection() as HttpURLConnection).apply {
    instanceFollowRedirects = true
    connectTimeout = 30_000
    readTimeout = 120_000
    requestMethod = "GET"
    accept?.let { setRequestProperty("Accept", it) }
    token?.let { setRequestProperty("Authorization", "Bearer $it") }
}

private fun readSuccessfulResponse(
    connection: HttpURLConnection,
    description: String,
    expectedDockerDigest: String? = null,
): ByteArray {
    try {
        val status = connection.responseCode
        if (status !in 200..299) {
            val details = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            error("$description failed with HTTP $status${details.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
        }
        expectedDockerDigest?.let { expected ->
            connection.getHeaderField("Docker-Content-Digest")?.let { actual ->
                check(actual == expected) {
                    "$description returned Docker-Content-Digest $actual, expected $expected"
                }
            }
        }
        return connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
}

private fun requestAnonymousPullToken(): String {
    val uri = URI.create(
        "https://$registry/token?service=$registry&scope=repository:$repository:pull",
    )
    val response = readSuccessfulResponse(openRegistryConnection(uri), "GHCR token request")
    val json = JsonSlurper().parseText(response.toString(StandardCharsets.UTF_8)) as Map<*, *>
    return (json["token"] ?: json["access_token"]) as? String
        ?: error("GHCR token response did not contain a token")
}

private fun downloadVerifiedRepository(targetDirectory: Path) {
    val token = requestAnonymousPullToken()
    val manifestUri = URI.create("https://$registry/v2/$repository/manifests/$manifestDigest")
    val manifestConnection = openRegistryConnection(manifestUri, manifestMediaType, token)
    val manifestBytes = readSuccessfulResponse(
        manifestConnection,
        "GHCR manifest download",
        manifestDigest,
    )
    val actualManifestDigest = "sha256:${manifestBytes.sha256()}"
    check(actualManifestDigest == manifestDigest) {
        "Anitorrent manifest digest mismatch: expected $manifestDigest, got $actualManifestDigest"
    }

    val manifest = JsonSlurper().parseText(manifestBytes.toString(StandardCharsets.UTF_8)) as Map<*, *>
    val layers = manifest["layers"] as? List<*> ?: error("GHCR manifest does not contain layers")
    val mavenLayer = layers
        .filterIsInstance<Map<*, *>>()
        .singleOrNull { it["mediaType"] == layerMediaType }
        ?: error("GHCR manifest does not contain exactly one $layerMediaType layer")
    check(mavenLayer["digest"] == layerDigest) {
        "Anitorrent layer digest mismatch in manifest: expected $layerDigest, got ${mavenLayer["digest"]}"
    }

    Files.createDirectories(targetDirectory.parent)
    val archive = Files.createTempFile(targetDirectory.parent, "anitorrent-maven-", ".tar.gz")
    val extraction = Files.createTempDirectory(targetDirectory.parent, "anitorrent-maven-extract-")
    try {
        val blobUri = URI.create("https://$registry/v2/$repository/blobs/$layerDigest")
        val blobConnection = openRegistryConnection(blobUri, token = token)
        try {
            val status = blobConnection.responseCode
            check(status in 200..299) { "GHCR Maven layer download failed with HTTP $status" }
            blobConnection.inputStream.use { input ->
                Files.newOutputStream(archive).use { output -> input.copyTo(output) }
            }
        } finally {
            blobConnection.disconnect()
        }

        val actualLayerDigest = "sha256:${archive.sha256()}"
        check(actualLayerDigest == layerDigest) {
            "Anitorrent layer digest mismatch: expected $layerDigest, got $actualLayerDigest"
        }

        GZIPInputStream(BufferedInputStream(Files.newInputStream(archive))).use { input ->
            extractTar(input, extraction)
        }
        val catalog = extraction.resolve(
            "io/github/beiyuge/anitorrent/catalog/$version/catalog-$version.toml",
        )
        check(Files.isRegularFile(catalog)) {
            "Downloaded Anitorrent Maven repository is missing $catalog"
        }
        Files.writeString(extraction.resolve(".ready"), "$manifestDigest\n$layerDigest\n")
        try {
            Files.move(extraction, targetDirectory, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(extraction, targetDirectory)
        }
    } finally {
        Files.deleteIfExists(archive)
        if (Files.exists(extraction)) {
            extraction.toFile().deleteRecursively()
        }
    }
}

private fun extractTar(input: InputStream, destination: Path) {
    val header = ByteArray(512)
    var pendingPath: String? = null
    var pendingPaxPath: String? = null
    while (true) {
        if (!input.readBlock(header)) error("Truncated tar header")
        if (header.all { it == 0.toByte() }) return

        val name = header.tarString(0, 100)
        val prefix = header.tarString(345, 155)
        val headerPath = if (prefix.isEmpty()) name else "$prefix/$name"
        val size = header.tarOctal(124, 12)
        val type = header[156].toInt().toChar()

        when (type) {
            'x' -> {
                val payload = input.readEntryBytes(size)
                pendingPaxPath = payload.toString(StandardCharsets.UTF_8)
                    .lineSequence()
                    .mapNotNull { record -> record.substringAfter(' ', "").takeIf { it.startsWith("path=") } }
                    .lastOrNull()
                    ?.removePrefix("path=")
                input.skipTarPadding(size)
                continue
            }
            'L' -> {
                pendingPath = input.readEntryBytes(size)
                    .toString(StandardCharsets.UTF_8)
                    .trimEnd('\u0000', '\n')
                input.skipTarPadding(size)
                continue
            }
        }

        val entryPath = pendingPaxPath ?: pendingPath ?: headerPath
        pendingPaxPath = null
        pendingPath = null
        val output = destination.safeResolve(entryPath)
        when (type) {
            '5' -> Files.createDirectories(output)
            '0', '\u0000' -> {
                Files.createDirectories(output.parent)
                Files.newOutputStream(output).use { file -> input.copyExactly(file, size) }
            }
            else -> input.skipExactly(size)
        }
        if (type == '5') input.skipExactly(size)
        input.skipTarPadding(size)
    }
}

private fun Path.safeResolve(entryPath: String): Path {
    val normalized = entryPath.replace('\\', '/').removePrefix("./")
    check(!normalized.startsWith('/')) { "Absolute tar entry is not allowed: $entryPath" }
    val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
    check(segments.none { it == ".." }) { "Parent traversal in tar entry: $entryPath" }
    check(segments.none { it.contains(':') }) { "Drive-qualified tar entry is not allowed: $entryPath" }
    return segments.fold(this) { path, segment -> path.resolve(segment) }.normalize().also {
        check(it.startsWith(normalize())) { "Tar entry escapes destination: $entryPath" }
    }
}

private fun ByteArray.tarString(offset: Int, length: Int): String = copyOfRange(offset, offset + length)
    .toString(StandardCharsets.UTF_8)
    .trimEnd('\u0000', ' ')

private fun ByteArray.tarOctal(offset: Int, length: Int): Long = tarString(offset, length)
    .trim()
    .ifEmpty { "0" }
    .toLong(8)

private fun InputStream.readBlock(buffer: ByteArray): Boolean {
    var offset = 0
    while (offset < buffer.size) {
        val read = read(buffer, offset, buffer.size - offset)
        if (read < 0) return false
        offset += read
    }
    return true
}

private fun InputStream.readEntryBytes(size: Long): ByteArray {
    check(size <= Int.MAX_VALUE) { "Tar metadata entry is too large: $size" }
    return ByteArray(size.toInt()).also { bytes ->
        var offset = 0
        while (offset < bytes.size) {
            val read = read(bytes, offset, bytes.size - offset)
            check(read >= 0) { "Truncated tar entry" }
            offset += read
        }
    }
}

private fun InputStream.copyExactly(output: OutputStream, size: Long) {
    var remaining = size
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        check(read >= 0) { "Truncated tar entry" }
        output.write(buffer, 0, read)
        remaining -= read
    }
}

private fun InputStream.skipExactly(size: Long) {
    var remaining = size
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        check(read >= 0) { "Truncated tar entry" }
        remaining -= read
    }
}

private fun InputStream.skipTarPadding(size: Long) {
    val padding = (512 - size % 512) % 512
    skipExactly(padding)
}

val digestDirectory = manifestDigest.removePrefix("sha256:")
val cacheRoot = gradle.gradleUserHomeDir.toPath()
    .resolve("caches/animeko/anitorrent")
val repositoryDirectory = cacheRoot.resolve(digestDirectory).resolve("repository")
val readyFile = repositoryDirectory.resolve(".ready")

if (!Files.isRegularFile(readyFile)) {
    check(!gradle.startParameter.isOffline) {
        "Anitorrent GHCR cache is missing at $repositoryDirectory; run Gradle once without --offline"
    }
    Files.createDirectories(cacheRoot)
    RandomAccessFile(cacheRoot.resolve("download.lock").toFile(), "rw").use { lockFile ->
        lockFile.channel.use { channel ->
            channel.lock().use {
                if (!Files.isRegularFile(readyFile)) {
                    downloadVerifiedRepository(repositoryDirectory)
                }
            }
        }
    }
}

extra["anitorrentGhcrMavenRepository"] = repositoryDirectory.toFile()

// Animeko still declares repositories in individual projects, so add the verified
// repository there as well as to dependencyResolutionManagement in settings.gradle.kts.
gradle.beforeProject {
    repositories.maven {
        name = "anitorrentGhcr"
        url = repositoryDirectory.toUri()
    }
}
