package afm.delivery

import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val githubResponseLimit = 1024 * 1024
private const val githubMetadataLimit = 128 * 1024
private const val githubTimeoutSeconds = 30L
private const val githubMaximumPages = 100
private const val githubMaximumRedirects = 5
private val githubCommitPattern = Regex("[0-9a-fA-F]{40}")
private val githubTagPattern = Regex("v0\\.1\\.([1-9][0-9]*)")
private val githubRepositoryPattern = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

class GitHubPublisher(
    private val repository: String,
    private val token: String,
    private val lockFile: Path,
    private val apiBase: URI = URI("https://api.github.com"),
) {
    private val apiOrigin = origin(apiBase, "GitHub API")
    private val repositoryPath = "repos/$repository"
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(githubTimeoutSeconds))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    init {
        if (!githubRepositoryPattern.matches(repository)) throw DeliveryException("GitHub repository is invalid")
        if (token.isBlank() || token.any(Char::isISOControl)) throw DeliveryException("GitHub token is invalid")
        if (apiBase.path.isNotEmpty() && apiBase.path != "/") throw DeliveryException("GitHub API base must not contain a path")
        if (apiBase.query != null || apiBase.fragment != null || apiBase.userInfo != null) throw DeliveryException("GitHub API base is unsafe")
    }

    fun publish(bundle: Path, expectedCommit: String): String {
        requireCommit(expectedCommit, "Expected commit")
        val prepared = prepareBundle(bundle, expectedCommit)
        lockFile.parent?.let(Files::createDirectories)
        try {
            FileChannel.open(lockFile, CREATE, WRITE).use { channel ->
                channel.lock().use {
                    if (!mainCommit().equals(expectedCommit, ignoreCase = true)) return "build is superseded because main moved"
                    val releases = releases()
                    val published = releases.filterNot { it.draft }
                    val currentVersion = prepared.manifest.version.code
                    val newer = published.managed().any { it.version > currentVersion }
                    if (newer) return "build is superseded by a newer published version"
                    val tagged = published.filter { it.tag == prepared.tag }
                    if (tagged.size > 1) throw DeliveryException("GitHub has multiple published releases for this version")
                    tagged.singleOrNull()?.let { release ->
                        val managed = release.managedVersion()
                            ?: throw DeliveryException("Published version is not an AFM prerelease")
                        if (managed != currentVersion) throw DeliveryException("Published release version is inconsistent")
                        verifyPublished(release, prepared, expectedCommit)
                        return "release is already published"
                    }
                    val draft = selectDraft(releases.filter { it.draft }, prepared, expectedCommit)
                    val activeDraft = draft ?: createDraft(prepared, expectedCommit)
                    synchronizeDraft(activeDraft, prepared, expectedCommit)
                    if (!publishDraft(activeDraft, prepared, expectedCommit)) return "draft is retained because main moved"
                    return "published ${prepared.tag}"
                }
            }
        } finally {
            deleteTree(prepared.temporary)
        }
    }

    private fun prepareBundle(bundle: Path, expectedCommit: String): PreparedBundle {
        if (!Files.isDirectory(bundle, NOFOLLOW_LINKS)) throw DeliveryException("Bundle must be a real directory")
        val manifestPath = bundle.resolve("delivery.json")
        ReleaseManifest.requireRegularFile(manifestPath, "manifest")
        if (Files.size(manifestPath) > githubResponseLimit) throw DeliveryException("Manifest exceeds size limit")
        val manifest = ReleaseManifest.read(manifestPath)
        ReleaseManifest.validate(manifest, expectedCommit)
        val verified = manifest.artifacts.associate { artifact ->
            val path = bundle.resolve(artifact.name)
            val (hash, size) = ReleaseManifest.sha256(path)
            if (!hash.equals(artifact.sha256, ignoreCase = true) || size != artifact.size) {
                throw DeliveryException("Artifact does not match manifest: ${artifact.name}")
            }
            artifact.name to Asset(artifact.name, path, hash.lowercase(), size)
        }
        val approved = manifest.artifacts.filter { it.name.endsWith(".apk") || it.name.endsWith(".deb") }
        if (approved.count { it.name.endsWith(".apk") } != 1 || approved.count { it.name.endsWith(".deb") } != 1) {
            throw DeliveryException("Manifest does not define one APK and one Debian package")
        }
        val files = approved.map { verified.getValue(it.name) }.sortedBy(Asset::name)
        val version = manifest.version.name
        val tag = "v$version"
        if (githubTagPattern.matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull() != manifest.version.code) {
            throw DeliveryException("Manifest version is not a GitHub prerelease version")
        }
        val temporary = Files.createTempDirectory(lockFile.parent ?: bundle, ".afm-github-")
        try {
            val sumsPath = temporary.resolve("SHA256SUMS")
            Files.writeString(sumsPath, files.joinToString("") { "${it.sha256}  ${it.name}\n" }, StandardCharsets.UTF_8)
            val metadataPath = temporary.resolve("release-metadata.json")
            Files.writeString(metadataPath, metadata(manifest, files), StandardCharsets.UTF_8)
            val publicAssets = files + listOf(asset(sumsPath), asset(metadataPath))
            return PreparedBundle(manifest, tag, "AFM development $version", publicAssets, temporary)
        } catch (error: Exception) {
            deleteTree(temporary)
            if (error is DeliveryException) throw error
            throw DeliveryException("Could not prepare public release assets")
        }
    }

    private fun metadata(manifest: ReleaseManifest, assets: List<Asset>): String = buildJsonObject {
        put("afm_commit", JsonPrimitive(manifest.source.afmCommit.lowercase()))
        put("spirit2_commit", JsonPrimitive(manifest.source.spirit2Commit.lowercase()))
        put("version", buildJsonObject {
            put("name", JsonPrimitive(manifest.version.name))
            put("code", JsonPrimitive(manifest.version.code))
        })
        put("application_id", JsonPrimitive(manifest.android.applicationId))
        put("certificate_sha256", JsonPrimitive(manifest.android.certificateSha256.lowercase()))
        put("artifacts", buildJsonArray {
            assets.forEach { asset ->
                add(buildJsonObject {
                    put("name", JsonPrimitive(asset.name))
                    put("sha256", JsonPrimitive(asset.sha256))
                    put("size", JsonPrimitive(asset.size))
                })
            }
        })
    }.toString()

    private fun asset(path: Path): Asset {
        val (hash, size) = ReleaseManifest.sha256(path)
        return Asset(path.fileName.toString(), path, hash.lowercase(), size)
    }

    private fun selectDraft(drafts: List<Release>, prepared: PreparedBundle, expectedCommit: String): Release? {
        val matching = drafts.filter { it.tag == prepared.tag }
        if (matching.size > 1) throw DeliveryException("GitHub has multiple drafts for this version")
        val draft = matching.singleOrNull() ?: return null
        validateDraft(draft, prepared, expectedCommit)
        return draft
    }

    private fun createDraft(prepared: PreparedBundle, expectedCommit: String): Release {
        val response = api(
            "POST",
            apiUri("$repositoryPath/releases"),
            buildJsonObject {
                put("tag_name", JsonPrimitive(prepared.tag))
                put("target_commitish", JsonPrimitive(expectedCommit.lowercase()))
                put("name", JsonPrimitive(prepared.title))
                put("body", JsonPrimitive(body(prepared.manifest)))
                put("draft", JsonPrimitive(true))
                put("prerelease", JsonPrimitive(true))
                put("make_latest", JsonPrimitive("false"))
            }.toString().toByteArray(StandardCharsets.UTF_8),
        )
        return release(response.body, requireUploadUrl = true).also { validateDraft(it, prepared, expectedCommit) }
    }

    private fun synchronizeDraft(draft: Release, prepared: PreparedBundle, expectedCommit: String) {
        val expected = prepared.assets.associateBy(Asset::name)
        if (draft.assets.any { it.name !in expected }) throw DeliveryException("Draft has unapproved assets")
        draft.assets.groupBy(ReleaseAsset::name).values.forEach { if (it.size != 1) throw DeliveryException("Draft has duplicate assets") }
        draft.assets.forEach { remote ->
            val local = expected.getValue(remote.name)
            if (remote.size != local.size || !matches(remote, local)) {
                val current = release(api("GET", apiUri("$repositoryPath/releases/${draft.id}")).body, requireUploadUrl = true)
                validateDraft(current, prepared, expectedCommit)
                val replacement = current.assets.singleOrNull { it.id == remote.id }
                    ?: throw DeliveryException("Draft asset changed before replacement")
                if (replacement.name != local.name || (replacement.size == local.size && matches(replacement, local))) {
                    throw DeliveryException("Draft asset changed before replacement")
                }
                api("DELETE", apiUri("$repositoryPath/releases/${draft.id}/assets/${remote.id}"))
            }
        }
        val refreshed = release(api("GET", apiUri("$repositoryPath/releases/${draft.id}")).body, requireUploadUrl = true)
        validateDraft(refreshed, prepared, expectedCommit)
        val present = refreshed.assets.associateBy(ReleaseAsset::name)
        prepared.assets.filter { local -> present[local.name]?.let { it.size == local.size && matches(it, local) } != true }
            .forEach { upload(refreshed, it) }
        val complete = release(api("GET", apiUri("$repositoryPath/releases/${draft.id}")).body, requireUploadUrl = true)
        validateDraft(complete, prepared, expectedCommit)
        verifyAssets(complete.assets, prepared.assets)
    }

    private fun publishDraft(draft: Release, prepared: PreparedBundle, expectedCommit: String): Boolean {
        val current = release(api("GET", apiUri("$repositoryPath/releases/${draft.id}")).body, requireUploadUrl = true)
        validateDraft(current, prepared, expectedCommit)
        verifyAssets(current.assets, prepared.assets)
        if (!mainCommit().equals(expectedCommit, ignoreCase = true)) return false
        api(
            "PATCH",
            apiUri("$repositoryPath/releases/${draft.id}"),
            buildJsonObject {
                put("tag_name", JsonPrimitive(prepared.tag))
                put("target_commitish", JsonPrimitive(expectedCommit.lowercase()))
                put("name", JsonPrimitive(prepared.title))
                put("body", JsonPrimitive(body(prepared.manifest)))
                put("draft", JsonPrimitive(false))
                put("prerelease", JsonPrimitive(true))
                put("make_latest", JsonPrimitive("false"))
            }.toString().toByteArray(StandardCharsets.UTF_8),
        )
        return true
    }

    private fun verifyPublished(release: Release, prepared: PreparedBundle, expectedCommit: String) {
        if (release.tag != prepared.tag || release.name != prepared.title || !release.prerelease) {
            throw DeliveryException("Published release does not match the AFM prerelease contract")
        }
        val source = publishedSource(release)
        if (!source.equals(expectedCommit, ignoreCase = true)) throw DeliveryException("Published version belongs to a different source commit")
        val resolved = resolveTag(release.tag)
        if (!resolved.equals(expectedCommit, ignoreCase = true)) throw DeliveryException("Published tag does not resolve to the expected commit")
        verifyAssets(release.assets, prepared.assets)
    }

    private fun publishedSource(release: Release): String {
        val metadata = release.assets.singleOrNull { it.name == "release-metadata.json" }
            ?: throw DeliveryException("Published AFM release has no metadata")
        val bytes = download(metadata, githubMetadataLimit)
        val root = parseObject(bytes, "Published release metadata")
        val source = root["afm_commit"]?.jsonPrimitive?.contentOrNull
            ?: throw DeliveryException("Published release metadata has no AFM commit")
        requireCommit(source, "Published AFM commit")
        return source
    }

    private fun verifyAssets(remote: List<ReleaseAsset>, expected: List<Asset>) {
        if (remote.size != expected.size || remote.map(ReleaseAsset::name).toSet().size != remote.size) {
            throw DeliveryException("Release assets do not match the AFM public allowlist")
        }
        val local = expected.associateBy(Asset::name)
        remote.forEach { asset ->
            val expectedAsset = local[asset.name] ?: throw DeliveryException("Release has an unapproved asset")
            if (asset.size != expectedAsset.size || !matches(asset, expectedAsset)) {
                throw DeliveryException("Release asset does not match the approved bytes: ${asset.name}")
            }
        }
    }

    private fun matches(remote: ReleaseAsset, local: Asset): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        downloadStream(remote) { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > local.size) throw DeliveryException("Downloaded release asset exceeds its declared size")
                digest.update(buffer, 0, count)
            }
        }
        return size == local.size && digest.digest().joinToString("") { "%02x".format(it) }.equals(local.sha256, ignoreCase = true)
    }

    private fun download(asset: ReleaseAsset, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        downloadStream(asset) { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw DeliveryException("Downloaded release metadata exceeds size limit")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun downloadStream(asset: ReleaseAsset, consume: (InputStream) -> Unit) {
        var uri = apiUri(asset.url.toString())
        var authenticated = true
        repeat(githubMaximumRedirects + 1) { attempt ->
            val request = if (authenticated) {
                HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(githubTimeoutSeconds))
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "afm-delivery-publisher")
                    .build()
            } else {
                HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(githubTimeoutSeconds)).build()
            }
            val response = send(request)
            when {
                response.statusCode() == 200 -> {
                    response.body().use(consume)
                    return
                }
                response.statusCode() in 300..399 && attempt < githubMaximumRedirects -> {
                    response.body().close()
                    val location = response.headers().firstValue("Location").orElseThrow { DeliveryException("Release asset redirect is missing a location") }
                    uri = publicUri(uri.resolve(location))
                    authenticated = false
                }
                else -> {
                    response.body().close()
                    throw DeliveryException("Release asset download failed with HTTP ${response.statusCode()}")
                }
            }
        }
        throw DeliveryException("Release asset redirect limit exceeded")
    }

    private fun upload(release: Release, asset: Asset) {
        val upload = uploadUri(release.uploadUrl, release.id, asset.name)
        val request = HttpRequest.newBuilder(upload)
            .timeout(Duration.ofSeconds(githubTimeoutSeconds))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "afm-delivery-publisher")
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofFile(asset.path))
            .build()
        val response = send(request)
        response.body().use { readLimited(it, githubResponseLimit) }
        if (response.statusCode() !in 200..299) throw DeliveryException("GitHub asset upload failed with HTTP ${response.statusCode()}")
    }

    private fun validateDraft(release: Release, prepared: PreparedBundle, expectedCommit: String) {
        if (!release.draft || release.tag != prepared.tag || release.targetCommit?.equals(expectedCommit, ignoreCase = true) != true ||
            release.name != prepared.title || !release.prerelease || release.uploadUrl == null) {
            throw DeliveryException("Draft release does not belong to this AFM source commit")
        }
    }

    private fun releases(): List<Release> {
        val collected = mutableListOf<Release>()
        var next: URI? = apiUri("$repositoryPath/releases?per_page=100&page=1")
        repeat(githubMaximumPages) {
            val page = next ?: return collected
            val response = api("GET", page)
            val entries = parseArray(response.body, "GitHub releases").map { release(it.toString().toByteArray(StandardCharsets.UTF_8), requireUploadUrl = false) }
            collected += entries
            next = nextPage(response.headers.entries.firstOrNull { it.key.equals("link", ignoreCase = true) }?.value?.firstOrNull())
            if (next == null && entries.size == 100) {
                val pageNumber = (it + 2)
                next = apiUri("$repositoryPath/releases?per_page=100&page=$pageNumber")
            }
        }
        throw DeliveryException("GitHub release pagination exceeds the limit")
    }

    private fun nextPage(link: String?): URI? {
        if (link.isNullOrBlank()) return null
        val entry = link.split(',').map(String::trim).singleOrNull { it.contains("rel=\"next\"") || it.contains("rel=next") } ?: return null
        val raw = entry.substringAfter('<', "").substringBefore('>', "")
        if (raw.isEmpty()) throw DeliveryException("GitHub pagination link is invalid")
        return apiUri(apiBase.resolve(raw).toString().removePrefix(apiBase.toString()).removePrefix("/"))
    }

    private fun List<Release>.managed(): List<ManagedRelease> = mapNotNull { release ->
        release.managedVersion()?.let { ManagedRelease(release, it) }
    }

    private fun Release.managedVersion(): Int? {
        if (name != "AFM development ${tag.removePrefix("v")}") return null
        return githubTagPattern.matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun mainCommit(): String {
        val root = parseObject(api("GET", apiUri("$repositoryPath/commits/main")).body, "GitHub main branch")
        val commit = root["sha"]?.jsonPrimitive?.contentOrNull ?: throw DeliveryException("GitHub main branch has no commit")
        requireCommit(commit, "GitHub main commit")
        return commit
    }

    private fun resolveTag(tag: String): String {
        var reference = parseObject(api("GET", apiUri("$repositoryPath/git/ref/tags/$tag")).body, "GitHub tag reference")
        var target = reference["object"]?.jsonObject ?: throw DeliveryException("GitHub tag reference is invalid")
        repeat(5) {
            val type = target["type"]?.jsonPrimitive?.contentOrNull ?: throw DeliveryException("GitHub tag object is invalid")
            val sha = target["sha"]?.jsonPrimitive?.contentOrNull ?: throw DeliveryException("GitHub tag object is invalid")
            requireCommit(sha, "GitHub tag object")
            if (type == "commit") return sha
            if (type != "tag") throw DeliveryException("GitHub tag does not resolve to a commit")
            reference = parseObject(api("GET", apiUri("$repositoryPath/git/tags/$sha")).body, "GitHub annotated tag")
            target = reference["object"]?.jsonObject ?: throw DeliveryException("GitHub annotated tag is invalid")
        }
        throw DeliveryException("GitHub annotated tag nesting exceeds the limit")
    }

    private fun body(manifest: ReleaseManifest): String = "AFM commit: ${manifest.source.afmCommit.lowercase()}\nSpirit2 commit: ${manifest.source.spirit2Commit.lowercase()}"

    private fun release(bytes: ByteArray, requireUploadUrl: Boolean): Release {
        val root = parseObject(bytes, "GitHub release")
        val id = root["id"]?.jsonPrimitive?.longOrNull ?: throw DeliveryException("GitHub release has no id")
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: throw DeliveryException("GitHub release has no tag")
        val name = root["name"]?.jsonPrimitive?.contentOrNull ?: throw DeliveryException("GitHub release has no name")
        val draft = root["draft"]?.jsonPrimitive?.booleanOrNull ?: throw DeliveryException("GitHub release has no draft state")
        val prerelease = root["prerelease"]?.jsonPrimitive?.booleanOrNull ?: throw DeliveryException("GitHub release has no prerelease state")
        val target = root["target_commitish"]?.jsonPrimitive?.contentOrNull
        val upload = root["upload_url"]?.jsonPrimitive?.contentOrNull
        if (requireUploadUrl && upload == null) throw DeliveryException("GitHub draft has no upload URL")
        val assets = (root["assets"] as? JsonArray ?: throw DeliveryException("GitHub release has no assets")).map { asset(it.jsonObject) }
        return Release(id, tag, name, draft, prerelease, target, upload, assets)
    }

    private fun asset(root: JsonObject): ReleaseAsset {
        val id = root["id"]?.jsonPrimitive?.longOrNull ?: throw DeliveryException("GitHub release asset has no id")
        val name = root["name"]?.jsonPrimitive?.contentOrNull ?: throw DeliveryException("GitHub release asset has no name")
        val size = root["size"]?.jsonPrimitive?.longOrNull ?: throw DeliveryException("GitHub release asset has no size")
        val url = root["url"]?.jsonPrimitive?.contentOrNull ?: throw DeliveryException("GitHub release asset has no API URL")
        if (size !in 0..2L * 1024 * 1024 * 1024) throw DeliveryException("GitHub release asset size is invalid")
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            throw DeliveryException("GitHub release asset URL is invalid")
        }
        return ReleaseAsset(id, name, size, uri)
    }

    private fun api(method: String, uri: URI, body: ByteArray? = null): ApiResponse {
        apiUri(uri.toString())
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(githubTimeoutSeconds))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "afm-delivery-publisher")
        if (body != null) builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body))
        else builder.method(method, HttpRequest.BodyPublishers.noBody())
        val response = send(builder.build())
        val bytes = response.body().use { readLimited(it, githubResponseLimit) }
        if (response.statusCode() !in 200..299) throw DeliveryException("GitHub API request failed with HTTP ${response.statusCode()}")
        return ApiResponse(bytes, response.headers().map())
    }

    private fun send(request: HttpRequest): HttpResponse<InputStream> = try {
        client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    } catch (_: Exception) {
        throw DeliveryException("GitHub network request failed")
    }

    private fun apiUri(path: String): URI {
        val uri = if (path.startsWith("http://") || path.startsWith("https://")) URI(path) else apiBase.resolve("/${path.trimStart('/')}")
        if (origin(uri, "GitHub API") != apiOrigin || uri.userInfo != null || uri.fragment != null) {
            throw DeliveryException("GitHub API URL is unsafe")
        }
        return uri
    }

    private fun uploadUri(raw: String?, releaseId: Long, name: String): URI {
        val template = raw?.substringBefore('{') ?: throw DeliveryException("GitHub draft has no upload URL")
        val base = URI(template)
        val expectedPath = "/repos/$repository/releases/$releaseId/assets"
        val uploadOrigin = origin(base, "GitHub upload")
        val allowed = if (apiOrigin.host == "api.github.com") Origin("https", "uploads.github.com", 443, false) else apiOrigin
        if (uploadOrigin != allowed || base.path != expectedPath || base.query != null || base.userInfo != null || base.fragment != null) {
            throw DeliveryException("GitHub upload URL is unsafe")
        }
        return URI(base.scheme, null, base.host, base.port, base.path, "name=${encode(name)}", null)
    }

    private fun publicUri(uri: URI): URI {
        val publicOrigin = origin(uri, "GitHub release asset")
        val permitted = if (apiOrigin.loopback) publicOrigin == apiOrigin else publicOrigin.scheme == "https" &&
            (publicOrigin.host == "github.com" || publicOrigin.host.endsWith(".github.com") || publicOrigin.host.endsWith(".githubusercontent.com"))
        if (!permitted || uri.userInfo != null || uri.fragment != null) throw DeliveryException("GitHub release asset URL is unsafe")
        return uri
    }

    private fun origin(uri: URI, label: String): Origin {
        val scheme = uri.scheme?.lowercase() ?: throw DeliveryException("$label URL has no scheme")
        val host = uri.host?.lowercase() ?: throw DeliveryException("$label URL has no host")
        val loopback = host == "127.0.0.1" || host == "::1"
        if (uri.userInfo != null || uri.fragment != null || (scheme != "https" && !(scheme == "http" && loopback))) {
            throw DeliveryException("$label URL is unsafe")
        }
        return Origin(scheme, host, if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80, loopback)
    }

    private fun parseObject(bytes: ByteArray, label: String): JsonObject = try {
        Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
    } catch (_: Exception) {
        throw DeliveryException("$label response is invalid")
    }

    private fun parseArray(bytes: ByteArray, label: String): JsonArray = try {
        Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonArray
    } catch (_: Exception) {
        throw DeliveryException("$label response is invalid")
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw DeliveryException("GitHub response exceeds size limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun requireCommit(value: String, label: String) {
        if (!githubCommitPattern.matches(value)) throw DeliveryException("$label must be a full hexadecimal commit")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private data class Asset(val name: String, val path: Path, val sha256: String, val size: Long)
    private data class PreparedBundle(val manifest: ReleaseManifest, val tag: String, val title: String, val assets: List<Asset>, val temporary: Path)
    private data class Release(val id: Long, val tag: String, val name: String, val draft: Boolean, val prerelease: Boolean, val targetCommit: String?, val uploadUrl: String?, val assets: List<ReleaseAsset>)
    private data class ReleaseAsset(val id: Long, val name: String, val size: Long, val url: URI)
    private data class ManagedRelease(val release: Release, val version: Int)
    private data class ApiResponse(val body: ByteArray, val headers: Map<String, List<String>>)
    private data class Origin(val scheme: String, val host: String, val port: Int, val loopback: Boolean)
}
