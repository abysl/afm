package afm.delivery

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val maximumArtifactBytes = 2L * 1024 * 1024 * 1024
private val commitPattern = Regex("[0-9a-fA-F]{40}")
private val hashPattern = Regex("[0-9a-fA-F]{64}")
private val versionPattern = Regex("1\\.0\\.([1-9][0-9]*)")
private val artifactNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")

data class ReleaseSource(val afmCommit: String, val spirit2Commit: String)

data class ReleaseVersion(val name: String, val code: Int)

data class AndroidRelease(val applicationId: String, val certificateSha256: String)

data class ReleaseArtifact(val name: String, val sha256: String, val size: Long, val mediaType: String)

data class ReleaseManifest(
    val source: ReleaseSource,
    val version: ReleaseVersion,
    val android: AndroidRelease,
    val artifacts: List<ReleaseArtifact>,
) {
    fun write(path: Path) = Companion.write(path, this)

    fun validate(expectedCommit: String? = null) = Companion.validate(this, expectedCommit)

    companion object {
        private val json = Json

        fun read(path: Path): ReleaseManifest {
            requireRegularFile(path, "manifest")
            return fromJson(Files.readString(path, StandardCharsets.UTF_8))
        }

        fun write(path: Path, manifest: ReleaseManifest) {
            validate(manifest)
            Files.writeString(path, toJson(manifest), StandardCharsets.UTF_8)
        }

        fun fromJson(value: String): ReleaseManifest = fromElement(
            try {
                json.parseToJsonElement(value)
            } catch (error: Exception) {
                throw DeliveryException("Manifest JSON is invalid")
            },
        )

        fun toJson(manifest: ReleaseManifest): String {
            validate(manifest)
            return json.encodeToString(JsonElement.serializer(), toElement(manifest))
        }

        fun validate(manifest: ReleaseManifest, expectedCommit: String? = null) {
            validCommit(manifest.source.afmCommit, "AFM commit")
            validCommit(manifest.source.spirit2Commit, "Spirit2 commit")
            if (expectedCommit != null && !manifest.source.afmCommit.equals(expectedCommit, ignoreCase = true)) {
                throw DeliveryException("Manifest AFM commit does not match expected commit")
            }
            val versionMatch = versionPattern.matchEntire(manifest.version.name)
                ?: throw DeliveryException("Manifest version is invalid")
            if (manifest.version.code !in 1..2_100_000_000 || versionMatch.groupValues[1].toIntOrNull() != manifest.version.code) {
                throw DeliveryException("Manifest version is invalid")
            }
            if (manifest.android.applicationId != "com.abysl.afm" || !hashPattern.matches(manifest.android.certificateSha256)) {
                throw DeliveryException("Manifest Android metadata is invalid")
            }
            if (manifest.artifacts.isEmpty()) {
                throw DeliveryException("Manifest has no artifacts")
            }
            val names = mutableSetOf<String>()
            var apkCount = 0
            var debCount = 0
            manifest.artifacts.forEach { artifact ->
                if (!artifactNamePattern.matches(artifact.name) || artifact.name == "delivery.json" || !names.add(artifact.name)) {
                    throw DeliveryException("Manifest artifact names must be unique safe basenames")
                }
                if (!hashPattern.matches(artifact.sha256) || artifact.size !in 1..maximumArtifactBytes || artifact.mediaType.isBlank()) {
                    throw DeliveryException("Manifest artifact is invalid: ${artifact.name}")
                }
                if (artifact.name.endsWith(".apk")) apkCount += 1
                if (artifact.name.endsWith(".deb")) debCount += 1
            }
            if (apkCount != 1 || debCount != 1 || !names.containsAll(setOf("checksums.txt", "test-reports.tar.gz"))) {
                throw DeliveryException("Manifest lacks required artifacts")
            }
        }

        fun artifact(path: Path, mediaType: String): ReleaseArtifact {
            if (mediaType.isBlank()) throw DeliveryException("Artifact media type is required")
            val name = path.fileName?.toString() ?: throw DeliveryException("Artifact name is required")
            if (!artifactNamePattern.matches(name) || name == "delivery.json") throw DeliveryException("Artifact name is unsafe: $name")
            val (sha256, size) = sha256(path)
            return ReleaseArtifact(name, sha256, size, mediaType)
        }

        fun sha256(path: Path): Pair<String, Long> {
            requireRegularFile(path, "artifact")
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > maximumArtifactBytes) throw DeliveryException("Artifact exceeds size limit: $path")
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } to size
        }

        fun requireRegularFile(path: Path, label: String) {
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw DeliveryException("$label is not a regular file: $path")
        }

        private fun fromElement(element: JsonElement): ReleaseManifest {
            val root = objectFields(element, setOf("schema_version", "source", "version", "android", "artifacts"), "manifest")
            when (root.int("schema_version")) {
                1 -> throw DeliveryException("Manifest schema version 1 is unsupported; expected 2")
                2 -> Unit
                else -> throw DeliveryException("Manifest schema version is invalid")
            }
            val source = objectFields(root.required("source"), setOf("afm_commit", "spirit2_commit"), "source")
            val version = objectFields(root.required("version"), setOf("name", "code"), "version")
            val android = objectFields(root.required("android"), setOf("application_id", "certificate_sha256"), "android")
            val artifacts = root.required("artifacts").let {
                if (it !is JsonArray) throw DeliveryException("Manifest artifacts are invalid")
                it.map { artifact ->
                    val fields = objectFields(artifact, setOf("name", "sha256", "size", "media_type"), "artifact")
                    ReleaseArtifact(fields.string("name"), fields.string("sha256"), fields.long("size"), fields.string("media_type"))
                }
            }
            return ReleaseManifest(
                ReleaseSource(source.string("afm_commit"), source.string("spirit2_commit")),
                ReleaseVersion(version.string("name"), version.int("code")),
                AndroidRelease(android.string("application_id"), android.string("certificate_sha256")),
                artifacts,
            ).also { validate(it) }
        }

        private fun toElement(manifest: ReleaseManifest): JsonObject = buildJsonObject {
            put("schema_version", JsonPrimitive(2))
            put("source", buildJsonObject {
                put("afm_commit", JsonPrimitive(manifest.source.afmCommit))
                put("spirit2_commit", JsonPrimitive(manifest.source.spirit2Commit))
            })
            put("version", buildJsonObject {
                put("name", JsonPrimitive(manifest.version.name))
                put("code", JsonPrimitive(manifest.version.code))
            })
            put("android", buildJsonObject {
                put("application_id", JsonPrimitive(manifest.android.applicationId))
                put("certificate_sha256", JsonPrimitive(manifest.android.certificateSha256))
            })
            put("artifacts", buildJsonArray {
                manifest.artifacts.forEach { artifact ->
                    add(buildJsonObject {
                        put("name", JsonPrimitive(artifact.name))
                        put("sha256", JsonPrimitive(artifact.sha256.lowercase()))
                        put("size", JsonPrimitive(artifact.size))
                        put("media_type", JsonPrimitive(artifact.mediaType))
                    })
                }
            })
        }

        private fun objectFields(element: JsonElement, expected: Set<String>, label: String): JsonObject {
            val fields = element as? JsonObject ?: throw DeliveryException("Manifest $label is invalid")
            if (fields.keys != expected) throw DeliveryException("Manifest $label has an unexpected structure")
            return fields
        }

        private fun JsonObject.required(key: String): JsonElement = this[key] ?: throw DeliveryException("Manifest is missing $key")

        private fun JsonObject.string(key: String): String = required(key).jsonPrimitive.let { primitive ->
            if (!primitive.isString) null else primitive.contentOrNull
        } ?: throw DeliveryException("Manifest $key is invalid")

        private fun JsonObject.int(key: String): Int = required(key).jsonPrimitive.let { primitive ->
            if (primitive.isString) null else primitive.intOrNull
        } ?: throw DeliveryException("Manifest $key is invalid")

        private fun JsonObject.long(key: String): Long = required(key).jsonPrimitive.let { primitive ->
            if (primitive.isString) null else primitive.longOrNull
        } ?: throw DeliveryException("Manifest $key is invalid")

        private fun validCommit(value: String, label: String) {
            if (!commitPattern.matches(value)) throw DeliveryException("$label must be a full hexadecimal commit")
        }
    }
}
