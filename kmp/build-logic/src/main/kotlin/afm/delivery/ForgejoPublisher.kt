package afm.delivery

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val manifestLimit = 1024 * 1024
private const val responseLimit = 1024 * 1024
private const val networkTimeoutMillis = 30_000

class ForgejoPublisher(
    private val packageBase: URI,
    private val repositoryApi: URI,
    private val token: String,
    private val lockFile: Path,
) {
    init {
        if (token.isBlank()) throw DeliveryException("Forgejo token is required")
        if (token.any(Char::isISOControl)) throw DeliveryException("Forgejo token contains invalid characters")
        validateEndpoints(packageBase, repositoryApi)
    }

    fun restore(bundle: Path, expectedCommit: String): Boolean {
        validateCommit(expectedCommit)
        val manifestBytes = fetch(packageUrl(expectedCommit, "delivery.json"), missingIsNull = true, limit = manifestLimit) ?: return false
        val manifest = parseManifest(manifestBytes, expectedCommit)
        val output = restoreDirectory(bundle)
        manifest.artifacts.forEach { rejectRestoreTarget(output, it.name) }
        rejectRestoreTarget(output, "delivery.json")
        val staging = Files.createTempDirectory(output, ".afm-restore-")
        try {
            manifest.artifacts.forEach { artifact ->
                download(artifactUrl(expectedCommit, artifact), staging.resolve(artifact.name), artifact)
            }
            Files.write(staging.resolve("delivery.json"), manifestBytes)
            manifest.artifacts.forEach { artifact ->
                Files.move(staging.resolve(artifact.name), output.resolve(artifact.name), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            Files.move(staging.resolve("delivery.json"), output.resolve("delivery.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (error: DeliveryException) {
            throw error
        } catch (error: Exception) {
            throw DeliveryException("Could not restore bundle: ${error.message}")
        } finally {
            Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
        return true
    }

    fun publish(bundle: Path, expectedCommit: String): String {
        validateCommit(expectedCommit)
        val local = validateBundle(bundle, expectedCommit)
        lockFile.parent?.let(Files::createDirectories)
        FileChannel.open(lockFile, CREATE, WRITE).use { channel ->
            channel.lock().use {
                val initialMain = readMainCommit()
                protectReleaseHistory(local.manifest)
                local.manifest.artifacts.forEach { artifact ->
                    uploadImmutable(artifactUrl(expectedCommit, artifact), bundle.resolve(artifact.name), artifact.sha256, artifact.size)
                }
                uploadImmutable(packageUrl(expectedCommit, "delivery.json"), local.manifestPath, local.manifestHash, local.manifestSize)
                if (!initialMain.equals(expectedCommit, ignoreCase = true)) {
                    return "immutable bundle published; latest was not promoted because main moved"
                }
                val latest = protectReleaseHistory(local.manifest)
                if (latest != null && latest.version.code > local.manifest.version.code) {
                    return "latest version code is newer; immutable bundle retained"
                }
                if (!readMainCommit().equals(expectedCommit, ignoreCase = true)) {
                    return "immutable bundle published; latest was not promoted because main moved"
                }
                replaceLatest(local.manifestPath, local.manifestHash, local.manifestSize)
                return "latest promoted"
            }
        }
    }

    private fun validateBundle(bundle: Path, expectedCommit: String): LocalBundle {
        if (!Files.isDirectory(bundle, NOFOLLOW_LINKS)) throw DeliveryException("Bundle must be a real directory")
        val manifestPath = bundle.resolve("delivery.json")
        ReleaseManifest.requireRegularFile(manifestPath, "manifest")
        if (Files.size(manifestPath) > manifestLimit) throw DeliveryException("Manifest exceeds size limit")
        val manifest = ReleaseManifest.read(manifestPath)
        ReleaseManifest.validate(manifest, expectedCommit)
        manifest.artifacts.forEach { artifact ->
            val path = bundle.resolve(artifact.name)
            val (hash, size) = ReleaseManifest.sha256(path)
            if (!hash.equals(artifact.sha256, ignoreCase = true) || size != artifact.size) {
                throw DeliveryException("Artifact does not match manifest: ${artifact.name}")
            }
        }
        val (manifestHash, manifestSize) = ReleaseManifest.sha256(manifestPath)
        return LocalBundle(manifest, manifestPath, manifestHash, manifestSize)
    }

    private fun latestManifest(): ReleaseManifest? {
        val bytes = fetch(packageUrl("latest", "delivery.json"), missingIsNull = true, limit = manifestLimit) ?: return null
        return parseManifest(bytes, null)
    }

    private fun parseManifest(bytes: ByteArray, expectedCommit: String?): ReleaseManifest = try {
        ReleaseManifest.fromJson(bytes.toString(StandardCharsets.UTF_8)).also { ReleaseManifest.validate(it, expectedCommit) }
    } catch (error: DeliveryException) {
        throw error
    } catch (error: Exception) {
        throw DeliveryException("Downloaded completion manifest is invalid")
    }

    private fun protectReleaseHistory(current: ReleaseManifest): ReleaseManifest? {
        val checkpoint = lockFile.resolveSibling("${lockFile.fileName}.state.json")
        val remote = latestManifest()
        val local = if (Files.exists(checkpoint, NOFOLLOW_LINKS)) {
            ReleaseManifest.requireRegularFile(checkpoint, "publication checkpoint")
            if (Files.size(checkpoint) > manifestLimit) throw DeliveryException("Publication checkpoint exceeds size limit")
            ReleaseManifest.read(checkpoint).also { it.validate() }
        } else null
        val identityUrl = packageUrl("identity", "android-signing.json")
        val identity = fetch(identityUrl, missingIsNull = true, limit = manifestLimit)
        if (identity != null) {
            val fields = try {
                kotlinx.serialization.json.Json.parseToJsonElement(identity.toString(StandardCharsets.UTF_8)).jsonObject
            } catch (_: Exception) {
                throw DeliveryException("Stored Android signing identity is invalid")
            }
            if (fields.keys != setOf("application_id", "certificate_sha256") || fields["application_id"]?.jsonPrimitive?.contentOrNull != current.android.applicationId || !fields["certificate_sha256"]?.jsonPrimitive?.contentOrNull.equals(current.android.certificateSha256, ignoreCase = true)) {
                throw DeliveryException("Android signing identity changed")
            }
            if (remote == null && local == null) {
                throw DeliveryException("Latest is unavailable and publication checkpoint is missing; recover the checkpoint from a verified release before publishing")
            }
        }
        val known = listOfNotNull(remote, local)
        known.forEach { requireSameCertificate(it, current) }
        val previous = known.maxByOrNull { it.version.code }
        if (previous != null && previous.version.code == current.version.code && !previous.source.afmCommit.equals(current.source.afmCommit, ignoreCase = true)) {
            throw DeliveryException("A different source commit already uses this Android version code")
        }
        val guarded = if (previous != null && previous.version.code > current.version.code) previous else current
        val temporary = Files.createTempFile(checkpoint.parent, ".afm-publication-", ".json")
        try {
            guarded.write(temporary)
            Files.move(temporary, checkpoint, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        if (identity == null) {
            val identityFile = Files.createTempFile(checkpoint.parent, ".afm-identity-", ".json")
            try {
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("application_id", kotlinx.serialization.json.JsonPrimitive(current.android.applicationId))
                    put("certificate_sha256", kotlinx.serialization.json.JsonPrimitive(current.android.certificateSha256.lowercase()))
                }.toString()
                Files.writeString(identityFile, payload)
                val (hash, size) = ReleaseManifest.sha256(identityFile)
                uploadImmutable(identityUrl, identityFile, hash, size)
            } finally {
                Files.deleteIfExists(identityFile)
            }
        }
        return previous
    }

    private fun requireSameCertificate(previous: ReleaseManifest?, current: ReleaseManifest) {
        if (previous != null && !previous.android.certificateSha256.equals(current.android.certificateSha256, ignoreCase = true)) {
            throw DeliveryException("Android signing certificate changed")
        }
    }

    private fun readMainCommit(): String {
        val bytes = fetch(repositoryUrl("branches/main"), missingIsNull = false, limit = manifestLimit)!!
        val commit = try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
            val nested = root["commit"] as? JsonObject ?: throw DeliveryException("Main branch response has no commit")
            nested["id"]?.jsonPrimitive?.contentOrNull ?: throw DeliveryException("Main branch response has no commit")
        } catch (error: DeliveryException) {
            throw error
        } catch (error: Exception) {
            throw DeliveryException("Main branch response is invalid")
        }
        validateCommit(commit)
        return commit
    }

    private fun uploadImmutable(url: URI, path: Path, expectedHash: String, expectedSize: Long) {
        when (verifyRemote(url, expectedHash, expectedSize)) {
            RemoteState.MATCH -> return
            RemoteState.MISSING -> Unit
        }
        val connection = request("PUT", url, path)
        try {
            if (connection.responseCode !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED, HttpURLConnection.HTTP_CONFLICT)) {
                throw DeliveryException("PUT $url failed with HTTP ${connection.responseCode}")
            }
            readLimited(connection.responseStream(), responseLimit)
        } finally {
            connection.disconnect()
        }
        if (verifyRemote(url, expectedHash, expectedSize) != RemoteState.MATCH) {
            throw DeliveryException("Remote immutable bytes differ: $url")
        }
    }

    private fun replaceLatest(path: Path, expectedHash: String, expectedSize: Long) {
        val url = packageUrl("latest", "delivery.json")
        val deletion = request("DELETE", url)
        try {
            if (deletion.responseCode !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_NO_CONTENT, HttpURLConnection.HTTP_NOT_FOUND)) {
                throw DeliveryException("DELETE $url failed with HTTP ${deletion.responseCode}")
            }
            readLimited(deletion.responseStream(), responseLimit)
        } finally {
            deletion.disconnect()
        }
        val put = request("PUT", url, path)
        try {
            if (put.responseCode !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED)) {
                throw DeliveryException("PUT $url failed with HTTP ${put.responseCode}")
            }
            readLimited(put.responseStream(), responseLimit)
        } finally {
            put.disconnect()
        }
        if (verifyRemote(url, expectedHash, expectedSize) != RemoteState.MATCH) throw DeliveryException("Latest manifest differs after upload")
    }

    private fun download(url: URI, destination: Path, artifact: ReleaseArtifact) {
        val connection = request("GET", url)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw DeliveryException("GET $url failed with HTTP ${connection.responseCode}")
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            connection.inputStream.use { input ->
                Files.newOutputStream(destination, CREATE, WRITE).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > artifact.size) throw DeliveryException("Downloaded artifact exceeds declared size: ${artifact.name}")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hash.equals(artifact.sha256, ignoreCase = true) || size != artifact.size) {
                throw DeliveryException("Downloaded bytes do not match manifest: ${artifact.name}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyRemote(url: URI, expectedHash: String, expectedSize: Long): RemoteState {
        val connection = request("GET", url)
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return RemoteState.MISSING
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw DeliveryException("GET $url failed with HTTP ${connection.responseCode}")
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > expectedSize) throw DeliveryException("Remote artifact exceeds declared size: $url")
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            return if (hash.equals(expectedHash, ignoreCase = true) && size == expectedSize) RemoteState.MATCH else throw DeliveryException("Remote bytes differ: $url")
        } finally {
            connection.disconnect()
        }
    }

    private fun fetch(url: URI, missingIsNull: Boolean, limit: Int): ByteArray? {
        val connection = request("GET", url)
        try {
            if (missingIsNull && connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw DeliveryException("GET $url failed with HTTP ${connection.responseCode}")
            return readLimited(connection.inputStream, limit)
        } finally {
            connection.disconnect()
        }
    }

    private fun request(method: String, uri: URI, upload: Path? = null): HttpURLConnection = try {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = networkTimeoutMillis
        connection.readTimeout = networkTimeoutMillis
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "token $token")
        connection.setRequestProperty("User-Agent", "afm-delivery-publisher")
        if (upload != null) {
            val size = Files.size(upload)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(size)
            Files.newInputStream(upload).use { input -> connection.outputStream.use { output -> input.copyTo(output) } }
        }
        val status = connection.responseCode
        if (status in 300..399) {
            connection.disconnect()
            throw DeliveryException("Redirects are not allowed: $uri")
        }
        connection
    } catch (error: DeliveryException) {
        throw error
    } catch (error: Exception) {
        throw DeliveryException("$method $uri request failed")
    }

    private fun packageUrl(version: String, name: String): URI = append(packageBase, "$version/$name")

    private fun artifactUrl(version: String, artifact: ReleaseArtifact): URI = packageUrl(version, "${artifact.sha256.lowercase()}-${artifact.name}")

    private fun repositoryUrl(path: String): URI = append(repositoryApi, path)

    private fun append(base: URI, path: String): URI = URI(base.toString().trimEnd('/') + "/" + path.split('/').joinToString("/") { segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20") })

    private fun restoreDirectory(path: Path): Path {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, NOFOLLOW_LINKS)) throw DeliveryException("Restore bundle path must be a real directory")
        } else {
            Files.createDirectories(path)
        }
        return path
    }

    private fun rejectRestoreTarget(output: Path, name: String) {
        val target = output.resolve(name)
        if (Files.isSymbolicLink(target) || Files.exists(target, NOFOLLOW_LINKS) && !Files.isRegularFile(target, NOFOLLOW_LINKS)) {
            throw DeliveryException("Restore target is unsafe: $name")
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        input.use {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var size = 0
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                size += count
                if (size > limit) throw DeliveryException("Response exceeds size limit")
                bytes.write(buffer, 0, count)
            }
            return bytes.toByteArray()
        }
    }

    private fun HttpURLConnection.responseStream(): InputStream = if (responseCode in 200..299) inputStream else errorStream ?: InputStream.nullInputStream()

    private fun validateEndpoints(packageBase: URI, repositoryApi: URI) {
        val packageOrigin = endpointOrigin(packageBase)
        val repositoryOrigin = endpointOrigin(repositoryApi)
        if (packageOrigin != repositoryOrigin) throw DeliveryException("Package and repository endpoints must share an origin")
    }

    private fun endpointOrigin(uri: URI): Origin {
        val scheme = uri.scheme?.lowercase() ?: throw DeliveryException("Endpoint scheme is required")
        val host = uri.host?.lowercase() ?: throw DeliveryException("Endpoint host is required")
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) throw DeliveryException("Endpoint must not contain credentials, query, or fragment")
        val loopback = host == "127.0.0.1" || host == "::1"
        if (scheme != "https" && !(scheme == "http" && loopback)) throw DeliveryException("Endpoints require HTTPS except literal loopback tests")
        return Origin(scheme, host, if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80)
    }

    private fun validateCommit(value: String) {
        if (!Regex("[0-9a-fA-F]{40}").matches(value)) throw DeliveryException("Expected commit must be a full hexadecimal commit")
    }

    private data class LocalBundle(val manifest: ReleaseManifest, val manifestPath: Path, val manifestHash: String, val manifestSize: Long)
    private data class Origin(val scheme: String, val host: String, val port: Int)
    private enum class RemoteState { MATCH, MISSING }
}
