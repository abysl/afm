package afm.delivery

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.writeBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitHubPublisherTest {
    @TempDir
    lateinit var root: Path

    private lateinit var registry: Registry
    private lateinit var server: HttpServer
    private lateinit var publisher: GitHubPublisher
    private lateinit var bundle: Path

    @BeforeEach
    fun setUp() {
        registry = Registry()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { registry.handle(it) }
        server.start()
        publisher = GitHubPublisher("abysl/afm", "test-token", root.resolve("publisher.lock"), URI(origin()))
        bundle = root.resolve("bundle")
        createBundle(bundle)
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `publishes a complete draft only after every public asset is present`() {
        assertEquals("published v0.1.7", publisher.publish(bundle, COMMIT))
        val release = registry.releases.single()
        assertFalse(release.draft)
        assertTrue(release.prerelease)
        assertEquals("AFM development 0.1.7", release.name)
        assertEquals(setOf("afm.apk", "afm.deb", "SHA256SUMS", "release-metadata.json"), release.assets.map { it.name }.toSet())
        assertEquals("false", release.makeLatest)
        assertEquals("PATCH", registry.requests.last().method)
    }

    @Test
    fun `partial upload leaves the release as a draft and retry reuses it`() {
        registry.failUploads += "afm.deb"
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
        assertEquals(1, registry.releases.size)
        assertTrue(registry.releases.single().draft)
        assertFalse(registry.requests.any { it.method == "PATCH" })
        registry.failUploads.clear()
        assertEquals("published v0.1.7", publisher.publish(bundle, COMMIT))
        assertEquals(1, registry.releases.size)
        assertFalse(registry.releases.single().draft)
    }

    @Test
    fun `verified published retries do not mutate the release`() {
        assertEquals("published v0.1.7", publisher.publish(bundle, COMMIT))
        registry.requests.clear()
        assertEquals("release is already published", publisher.publish(bundle, COMMIT))
        assertFalse(registry.requests.any { it.method in setOf("POST", "PATCH", "DELETE") })
    }

    @Test
    fun `corrupt local assets are rejected before any network request`() {
        bundle.resolve("test-reports.tar.gz").writeBytes("corrupt".toByteArray())
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
        assertTrue(registry.requests.isEmpty())
    }

    @Test
    fun `conflicting published release refuses mutation`() {
        registry.seedPublished(version = 7, source = OTHER_COMMIT)
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
        assertFalse(registry.requests.any { it.method in setOf("POST", "PATCH", "DELETE") })
    }

    @Test
    fun `stale main and newer releases suppress publication`() {
        registry.main = OTHER_COMMIT
        assertEquals("build is superseded because main moved", publisher.publish(bundle, COMMIT))
        assertFalse(registry.requests.any { it.method == "POST" })
        registry.main = COMMIT
        registry.seedPublished(version = 8, source = COMMIT)
        assertEquals("build is superseded by a newer published version", publisher.publish(bundle, COMMIT))
        assertFalse(registry.requests.any { it.method == "POST" })
    }

    @Test
    fun `retry after main advances retains draft until the source is current again`() {
        registry.failUploads += "afm.deb"
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
        registry.failUploads.clear()
        registry.main = OTHER_COMMIT
        assertEquals("build is superseded because main moved", publisher.publish(bundle, COMMIT))
        assertTrue(registry.releases.single().draft)
        registry.main = COMMIT
        assertEquals("published v0.1.7", publisher.publish(bundle, COMMIT))
    }

    @Test
    fun `finds matching drafts through pagination`() {
        registry.seedDraft(version = 6, source = COMMIT)
        registry.seedDraft(version = 7, source = COMMIT)
        registry.forcePagination = true
        assertEquals("published v0.1.7", publisher.publish(bundle, COMMIT))
        assertEquals(2, registry.releases.size)
        assertFalse(registry.releases.single { it.tag == "v0.1.7" }.draft)
    }

    @Test
    fun `redirects do not forward tokens and unsafe release URLs are rejected`() {
        registry.redirectMain = true
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
        assertFalse(registry.redirectTargetHit)
        assertThrows(DeliveryException::class.java) {
            GitHubPublisher("abysl/afm", "token", root.resolve("other.lock"), URI("http://example.test"))
        }
        registry.redirectMain = false
        registry.unsafeUploadUrl = true
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
        assertTrue(registry.releases.single().draft)
        assertFalse(registry.requests.any { it.path.startsWith("/evil") })
    }

    private fun createBundle(path: Path) {
        Files.createDirectories(path)
        val contents = linkedMapOf(
            "afm.apk" to "signed apk".toByteArray(),
            "afm.deb" to "linux package".toByteArray(),
            "checksums.txt" to "private checksums".toByteArray(),
            "test-reports.tar.gz" to "private reports".toByteArray(),
        )
        contents.forEach { (name, bytes) -> path.resolve(name).writeBytes(bytes) }
        ReleaseManifest(
            ReleaseSource(COMMIT, SPIRIT_COMMIT),
            ReleaseVersion("0.1.7", 7),
            AndroidRelease("com.abysl.afm", CERTIFICATE),
            contents.map { (name, bytes) -> ReleaseArtifact(name, hash(bytes), bytes.size.toLong(), "application/octet-stream") },
        ).write(path.resolve("delivery.json"))
    }

    private fun origin(): String = "http://127.0.0.1:${server.address.port}"

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private inner class Registry {
        val releases = mutableListOf<StoredRelease>()
        val requests = mutableListOf<Request>()
        val failUploads = mutableSetOf<String>()
        val assetId = AtomicLong(100)
        var main = COMMIT
        var redirectMain = false
        var redirectTargetHit = false
        var forcePagination = false
        var unsafeUploadUrl = false

        fun handle(exchange: HttpExchange) {
            synchronized(requests) { requests += Request(exchange.requestMethod, exchange.requestURI.path) }
            val path = exchange.requestURI.path
            if (path == "/redirect-target") {
                redirectTargetHit = true
                respond(exchange, 200, "{}")
                return
            }
            if (path == "/repos/abysl/afm/commits/main" && exchange.requestMethod == "GET") {
                if (redirectMain) {
                    exchange.responseHeaders.add("Location", "${origin()}/redirect-target")
                    respond(exchange, 302, "")
                } else respond(exchange, 200, "{\"sha\":\"$main\"}")
                return
            }
            if (path == "/repos/abysl/afm/releases" && exchange.requestMethod == "GET") {
                releasesPage(exchange)
                return
            }
            if (path == "/repos/abysl/afm/releases" && exchange.requestMethod == "POST") {
                createRelease(exchange)
                return
            }
            if (path.startsWith("/repos/abysl/afm/releases/")) {
                releaseRoute(exchange)
                return
            }
            if (path.startsWith("/assets/") && exchange.requestMethod == "GET") {
                val id = path.substringAfterLast('/').toLongOrNull()
                val asset = releases.flatMap { it.assets }.singleOrNull { it.id == id }
                if (asset == null) respond(exchange, 404, "") else respond(exchange, 200, asset.bytes)
                return
            }
            if (path == "/repos/abysl/afm/git/ref/tags/v0.1.7" && exchange.requestMethod == "GET") {
                respond(exchange, 200, "{\"object\":{\"type\":\"commit\",\"sha\":\"$COMMIT\"}}")
                return
            }
            if (path.startsWith("/repos/abysl/afm/git/ref/tags/") && exchange.requestMethod == "GET") {
                val version = path.substringAfterLast('.').toIntOrNull() ?: 0
                val commit = releases.singleOrNull { it.tag == "v0.1.$version" }?.source ?: COMMIT
                respond(exchange, 200, "{\"object\":{\"type\":\"commit\",\"sha\":\"$commit\"}}")
                return
            }
            respond(exchange, 404, "")
        }

        fun seedDraft(version: Int, source: String) {
            releases += StoredRelease(nextReleaseId(), "v0.1.$version", "AFM development 0.1.$version", true, source)
        }

        fun seedPublished(version: Int, source: String) {
            val release = StoredRelease(nextReleaseId(), "v0.1.$version", "AFM development 0.1.$version", false, source)
            val sourceBundle = if (source == COMMIT && version == 7) bundle else null
            if (sourceBundle != null) release.assets += expectedAssets(release, sourceBundle)
            else release.assets += StoredAsset(assetId.incrementAndGet(), "release-metadata.json", "{\"afm_commit\":\"$source\"}".toByteArray())
            releases += release
        }

        private fun releasesPage(exchange: HttpExchange) {
            val page = exchange.requestURI.query?.substringAfter("page=")?.substringBefore('&')?.toIntOrNull() ?: 1
            val values = if (forcePagination && page == 1) releases.take(1) else if (forcePagination && page == 2) releases.drop(1) else releases
            if (forcePagination && page == 1 && releases.size > 1) {
                exchange.responseHeaders.add("Link", "<${origin()}/repos/abysl/afm/releases?per_page=100&page=2>; rel=\"next\"")
            }
            respond(exchange, 200, Json.encodeToString(JsonArray.serializer(), JsonArray(values.map { releaseJson(it) })))
        }

        private fun createRelease(exchange: HttpExchange) {
            val body = Json.parseToJsonElement(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)).jsonObject
            val tag = body["tag_name"]!!.jsonPrimitive.content
            val source = body["target_commitish"]!!.jsonPrimitive.content
            val release = StoredRelease(nextReleaseId(), tag, body["name"]!!.jsonPrimitive.content, true, source)
            releases += release
            respond(exchange, 201, Json.encodeToString(JsonObject.serializer(), releaseJson(release)))
        }

        private fun releaseRoute(exchange: HttpExchange) {
            val parts = exchange.requestURI.path.split('/').filter(String::isNotEmpty)
            val releaseId = parts.getOrNull(4)?.toLongOrNull()
            val release = releases.singleOrNull { it.id == releaseId } ?: run { respond(exchange, 404, ""); return }
            if (parts.size == 5 && exchange.requestMethod == "GET") {
                respond(exchange, 200, Json.encodeToString(JsonObject.serializer(), releaseJson(release)))
                return
            }
            if (parts.size == 5 && exchange.requestMethod == "PATCH") {
                val body = Json.parseToJsonElement(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)).jsonObject
                release.draft = body["draft"]!!.jsonPrimitive.booleanOrNull ?: true
                release.prerelease = body["prerelease"]!!.jsonPrimitive.content == "true"
                release.makeLatest = body["make_latest"]!!.jsonPrimitive.content
                respond(exchange, 200, Json.encodeToString(JsonObject.serializer(), releaseJson(release)))
                return
            }
            if (parts.size == 6 && parts[5] == "assets" && exchange.requestMethod == "POST") {
                val name = exchange.requestURI.query?.substringAfter("name=")?.substringBefore('&') ?: ""
                if (name in failUploads) {
                    respond(exchange, 500, "")
                } else {
                    release.assets += StoredAsset(assetId.incrementAndGet(), name, exchange.requestBody.readBytes())
                    respond(exchange, 201, "{}")
                }
                return
            }
            if (parts.size == 7 && parts[5] == "assets" && exchange.requestMethod == "DELETE") {
                val asset = parts[6].toLongOrNull()
                release.assets.removeIf { it.id == asset }
                respond(exchange, 204, "")
                return
            }
            respond(exchange, 404, "")
        }

        private fun expectedAssets(release: StoredRelease, path: Path): List<StoredAsset> {
            val manifest = ReleaseManifest.read(path.resolve("delivery.json"))
            val public = manifest.artifacts.filter { it.name.endsWith(".apk") || it.name.endsWith(".deb") }.sortedBy { it.name }
            val entries = public.map { StoredAsset(assetId.incrementAndGet(), it.name, path.resolve(it.name).toFile().readBytes()) }.toMutableList()
            val sums = public.joinToString("") { "${it.sha256.lowercase()}  ${it.name}\n" }.toByteArray()
            entries += StoredAsset(assetId.incrementAndGet(), "SHA256SUMS", sums)
            val metadata = buildJsonObject {
                put("afm_commit", JsonPrimitive(release.source.lowercase()))
                put("spirit2_commit", JsonPrimitive(manifest.source.spirit2Commit.lowercase()))
                put("version", buildJsonObject { put("name", JsonPrimitive(manifest.version.name)); put("code", JsonPrimitive(manifest.version.code)) })
                put("application_id", JsonPrimitive(manifest.android.applicationId))
                put("certificate_sha256", JsonPrimitive(manifest.android.certificateSha256.lowercase()))
                put("artifacts", buildJsonArray {
                    public.forEach { artifact -> add(buildJsonObject { put("name", JsonPrimitive(artifact.name)); put("sha256", JsonPrimitive(artifact.sha256.lowercase())); put("size", JsonPrimitive(artifact.size)) }) }
                })
            }.toString().toByteArray()
            entries += StoredAsset(assetId.incrementAndGet(), "release-metadata.json", metadata)
            return entries
        }

        private fun releaseJson(release: StoredRelease) = buildJsonObject {
            put("id", JsonPrimitive(release.id))
            put("tag_name", JsonPrimitive(release.tag))
            put("target_commitish", JsonPrimitive(release.source))
            put("name", JsonPrimitive(release.name))
            put("draft", JsonPrimitive(release.draft))
            put("prerelease", JsonPrimitive(release.prerelease))
            put("upload_url", JsonPrimitive(if (unsafeUploadUrl) "http://127.0.0.1:1/evil" else "${origin()}/repos/abysl/afm/releases/${release.id}/assets{?name,label}"))
            put("assets", buildJsonArray { release.assets.forEach { asset ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(asset.id))
                    put("name", JsonPrimitive(asset.name))
                    put("size", JsonPrimitive(asset.bytes.size))
                    put("url", JsonPrimitive("${origin()}/assets/${asset.id}"))
                    put("browser_download_url", JsonPrimitive("${origin()}/assets/${asset.id}"))
                })
            } })
        }

        private fun nextReleaseId(): Long = releases.maxOfOrNull { it.id }?.plus(1) ?: 1

        private fun respond(exchange: HttpExchange, status: Int, body: String) = respond(exchange, status, body.toByteArray())

        private fun respond(exchange: HttpExchange, status: Int, body: ByteArray) {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private data class StoredRelease(val id: Long, val tag: String, val name: String, var draft: Boolean, val source: String, var prerelease: Boolean = true, var makeLatest: String = "false", val assets: MutableList<StoredAsset> = mutableListOf())
    private data class StoredAsset(val id: Long, val name: String, val bytes: ByteArray)
    private data class Request(val method: String, val path: String)

    private companion object {
        const val COMMIT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_COMMIT = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SPIRIT_COMMIT = "cccccccccccccccccccccccccccccccccccccccc"
        val CERTIFICATE = "d".repeat(64)
    }
}
