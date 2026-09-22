package afm.delivery

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ForgejoPublisherTest {
    @TempDir
    lateinit var root: Path

    private lateinit var registry: Registry
    private lateinit var server: HttpServer
    private lateinit var publisher: ForgejoPublisher
    private lateinit var bundle: Path

    @BeforeEach
    fun setUp() {
        registry = Registry()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> registry.handle(exchange) }
        server.start()
        val origin = "http://127.0.0.1:${server.address.port}"
        publisher = ForgejoPublisher(URI("$origin/packages"), URI("$origin/api/v1/repos/abysl/afm"), "test-token", root.resolve("publisher.lock"))
        bundle = root.resolve("bundle")
        createBundle(bundle)
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `publishes and restores a verified bundle`() {
        assertEquals("latest promoted", publisher.publish(bundle, COMMIT))
        val manifest = ReleaseManifest.read(bundle.resolve("delivery.json"))
        manifest.artifacts.forEach { artifact ->
            assertTrue(registry.files.containsKey(COMMIT to "${artifact.sha256}-${artifact.name}"))
        }
        val restored = root.resolve("restored")
        assertTrue(publisher.restore(restored, COMMIT))
        assertArrayEquals(bundle.resolve("delivery.json").toFile().readBytes(), restored.resolve("delivery.json").toFile().readBytes())
        manifest.artifacts.forEach { artifact ->
            assertArrayEquals(bundle.resolve(artifact.name).toFile().readBytes(), restored.resolve(artifact.name).toFile().readBytes())
        }
    }

    @Test
    fun `missing completion manifest returns false without creating a bundle`() {
        val output = root.resolve("missing")
        assertFalse(publisher.restore(output, COMMIT))
        assertFalse(Files.exists(output))
    }

    @Test
    fun `partial retry accepts rebuilt content under new content addressed names`() {
        registry.failedPutNames += "afm.deb"
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
        val first = ReleaseManifest.read(bundle.resolve("delivery.json"))
        val oldApk = first.artifacts.first { it.name == "afm.apk" }
        assertTrue(registry.files.containsKey(COMMIT to "${oldApk.sha256}-${oldApk.name}"))
        registry.failedPutNames.clear()
        rebuildBundle(bundle)
        assertEquals("latest promoted", publisher.publish(bundle, COMMIT))
        val rebuilt = ReleaseManifest.read(bundle.resolve("delivery.json"))
        val rebuiltApk = rebuilt.artifacts.first { it.name == "afm.apk" }
        assertFalse(oldApk.sha256.equals(rebuiltApk.sha256, ignoreCase = true))
        assertTrue(registry.files.containsKey(COMMIT to "${rebuiltApk.sha256}-${rebuiltApk.name}"))
    }

    @Test
    fun `immutable conflicting bytes fail`() {
        val artifact = ReleaseManifest.read(bundle.resolve("delivery.json")).artifacts.first { it.name == "afm.apk" }
        registry.conflicts[COMMIT to "${artifact.sha256}-${artifact.name}"] = "different".toByteArray()
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
        assertFalse(registry.files.containsKey("latest" to "delivery.json"))
    }

    @Test
    fun `stale main publishes immutable files but not latest`() {
        registry.branch = OTHER_COMMIT
        assertEquals("immutable bundle published; latest was not promoted because main moved", publisher.publish(bundle, COMMIT))
        assertTrue(registry.files.containsKey(COMMIT to "delivery.json"))
        assertFalse(registry.files.containsKey("latest" to "delivery.json"))
    }

    @Test
    fun `newer latest version cannot be downgraded`() {
        registry.files["latest" to "delivery.json"] = manifestBytes(version = 8)
        assertEquals("latest version code is newer; immutable bundle retained", publisher.publish(bundle, COMMIT))
        assertArrayEquals(manifestBytes(version = 8), registry.files["latest" to "delivery.json"])
    }

    @Test
    fun `failed latest replacement retains signer and version guards`() {
        publisher.publish(bundle, COMMIT)
        registry.failedPutKeys += "latest" to "delivery.json"
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
        assertFalse(registry.files.containsKey("latest" to "delivery.json"))
        registry.failedPutKeys.clear()
        val original = ReleaseManifest.read(bundle.resolve("delivery.json"))
        original.copy(android = original.android.copy(certificateSha256 = "e".repeat(64))).write(bundle.resolve("delivery.json"))
        registry.requests.clear()
        registry.branch = OTHER_COMMIT
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
        assertFalse(registry.requests.any { it.method == "PUT" || it.method == "DELETE" })
        original.copy(source = original.source.copy(afmCommit = OTHER_COMMIT), version = ReleaseVersion("1.0.6", 6)).write(bundle.resolve("delivery.json"))
        assertEquals("latest version code is newer; immutable bundle retained", publisher.publish(bundle, OTHER_COMMIT))
        assertFalse(registry.files.containsKey("latest" to "delivery.json"))
        original.write(bundle.resolve("delivery.json"))
        registry.branch = COMMIT
        assertEquals("latest promoted", publisher.publish(bundle, COMMIT))
    }

    @Test
    fun `lost checkpoint during latest outage fails closed`() {
        publisher.publish(bundle, COMMIT)
        registry.files.remove("latest" to "delivery.json")
        Files.delete(root.resolve("publisher.lock.state.json"))
        registry.requests.clear()
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
        assertFalse(registry.requests.any { it.method == "PUT" || it.method == "DELETE" })
    }

    @Test
    fun `stale main still enforces the signing identity`() {
        registry.files["latest" to "delivery.json"] = manifestBytes(certificate = "e".repeat(64))
        registry.branch = OTHER_COMMIT
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
        assertFalse(registry.requests.any { it.method == "PUT" || it.method == "DELETE" })
    }

    @Test
    fun `certificate changes are rejected before immutable uploads`() {
        registry.files["latest" to "delivery.json"] = manifestBytes(certificate = "e".repeat(64))
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
        assertFalse(registry.requests.any { it.method == "PUT" || it.method == "DELETE" })
    }

    @Test
    fun `delivery manifests use AFM provenance and reject schema one`() {
        val manifest = ReleaseManifest.read(bundle.resolve("delivery.json"))
        val json = ReleaseManifest.toJson(manifest)

        assertTrue(json.contains("\"schema_version\":2"))
        assertTrue(json.contains("\"afm_commit\""))
        assertFalse(json.contains("\"atlas_commit\""))
        val legacy = json
            .replace("\"schema_version\":2", "\"schema_version\":1")
            .replace("\"afm_commit\"", "\"atlas_commit\"")
        val error = assertThrows(DeliveryException::class.java) {
            ReleaseManifest.fromJson(legacy)
        }
        assertEquals("Manifest schema version 1 is unsupported; expected 2", error.message)
    }

    @Test
    fun `invalid token is rejected without exposing its value`() {
        val secret = "private-token\ninvalid-header"
        val error = assertThrows(DeliveryException::class.java) {
            ForgejoPublisher(URI("https://forge.example/packages"), URI("https://forge.example/api"), secret, root.resolve("lock"))
        }
        assertFalse(error.message.orEmpty().contains("private-token"))
    }

    @Test
    fun `unsafe endpoint names and local files are rejected`() {
        assertThrows(DeliveryException::class.java) {
            ForgejoPublisher(URI("http://example.test/packages"), URI("http://example.test/api"), "token", root.resolve("lock"))
        }
        val unsafe = bundle.resolve("delivery.json")
        unsafe.writeText(unsafe.readText().replace("afm.apk", "../escape.apk"))
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
        assertTrue(registry.requests.isEmpty())
        createBundle(bundle)
        val original = bundle.resolve("afm.apk")
        val target = root.resolve("outside.apk")
        target.writeBytes(original.toFile().readBytes())
        Files.delete(original)
        Files.createSymbolicLink(original, target)
        assertThrows(DeliveryException::class.java) { publisher.publish(bundle, COMMIT) }
    }

    @Test
    fun `corrupt restoration leaves no completion marker`() {
        publisher.publish(bundle, COMMIT)
        val artifact = ReleaseManifest.read(bundle.resolve("delivery.json")).artifacts.first { it.name == "afm.apk" }
        registry.files[COMMIT to "${artifact.sha256}-${artifact.name}"] = "corrupt".toByteArray()
        val restored = root.resolve("corrupt")
        assertThrows(DeliveryException::class.java) { publisher.restore(restored, COMMIT) }
        assertFalse(Files.exists(restored.resolve("delivery.json")))
    }

    private fun createBundle(path: Path, version: Int = 7) {
        Files.createDirectories(path)
        val contents = linkedMapOf(
            "afm.apk" to "signed apk\n".toByteArray(),
            "afm.deb" to "linux package\n".toByteArray(),
            "checksums.txt" to "checksums\n".toByteArray(),
            "test-reports.tar.gz" to "reports\n".toByteArray(),
        )
        contents.forEach { (name, bytes) -> path.resolve(name).writeBytes(bytes) }
        val manifest = ReleaseManifest(
            ReleaseSource(COMMIT, SPIRIT_COMMIT),
            ReleaseVersion("1.0.$version", version),
            AndroidRelease("com.abysl.afm", CERTIFICATE),
            contents.map { (name, bytes) -> ReleaseArtifact(name, hash(bytes), bytes.size.toLong(), "application/octet-stream") },
        )
        ReleaseManifest.write(path.resolve("delivery.json"), manifest)
    }

    private fun rebuildBundle(path: Path) {
        val before = ReleaseManifest.read(path.resolve("delivery.json"))
        val artifacts = before.artifacts.map { artifact ->
            val bytes = path.resolve(artifact.name).toFile().readBytes() + "rebuilt".toByteArray()
            path.resolve(artifact.name).writeBytes(bytes)
            artifact.copy(sha256 = hash(bytes), size = bytes.size.toLong())
        }
        ReleaseManifest.write(path.resolve("delivery.json"), before.copy(artifacts = artifacts))
    }

    private fun manifestBytes(version: Int = 7, certificate: String = CERTIFICATE): ByteArray {
        val existing = ReleaseManifest.read(bundle.resolve("delivery.json"))
        return ReleaseManifest.toJson(existing.copy(version = ReleaseVersion("1.0.$version", version), android = existing.android.copy(certificateSha256 = certificate))).toByteArray()
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Registry {
        val files = ConcurrentHashMap<Pair<String, String>, ByteArray>()
        val conflicts = ConcurrentHashMap<Pair<String, String>, ByteArray>()
        val failedPutNames = ConcurrentHashMap.newKeySet<String>()
        val failedPutKeys = ConcurrentHashMap.newKeySet<Pair<String, String>>()
        val requests = mutableListOf<Request>()
        @Volatile var branch = COMMIT

        fun handle(exchange: HttpExchange) {
            synchronized(requests) { requests += Request(exchange.requestMethod, exchange.requestURI.path) }
            val path = exchange.requestURI.path.split('/').filter(String::isNotEmpty)
            if (exchange.requestMethod == "GET" && path == listOf("api", "v1", "repos", "abysl", "afm", "branches", "main")) {
                respond(exchange, 200, "{\"commit\":{\"id\":\"$branch\"}}".toByteArray())
                return
            }
            if (path.size != 3 || path.first() != "packages") {
                respond(exchange, 404)
                return
            }
            val key = path[1] to path[2]
            when (exchange.requestMethod) {
                "GET" -> files[key]?.let { respond(exchange, 200, it) } ?: respond(exchange, 404)
                "PUT" -> {
                    val body = exchange.requestBody.readBytes()
                    when {
                        key in failedPutKeys || failedPutNames.any { key.second == it || key.second.endsWith("-$it") } -> respond(exchange, 500)
                        conflicts.remove(key)?.let { conflict -> files[key] = conflict; true } == true -> respond(exchange, 409)
                        files.containsKey(key) -> respond(exchange, 409)
                        else -> { files[key] = body; respond(exchange, 201) }
                    }
                }
                "DELETE" -> if (files.remove(key) == null) respond(exchange, 404) else respond(exchange, 204)
                else -> respond(exchange, 405)
            }
        }

        private fun respond(exchange: HttpExchange, status: Int, body: ByteArray = ByteArray(0)) {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private data class Request(val method: String, val path: String)

    private companion object {
        const val COMMIT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_COMMIT = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SPIRIT_COMMIT = "cccccccccccccccccccccccccccccccccccccccc"
        val CERTIFICATE = "d".repeat(64)
    }
}
