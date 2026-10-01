package afm.delivery

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.json.*

object ReleaseVersions {
    private const val maximumCode = 2_100_000_000L
    private val codePattern = Regex("[1-9][0-9]*")
    private val countPattern = Regex("[0-9]+")
    private val namePattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

    fun development(code: String?, name: String?): ReleaseVersion {
        if (code == null && name == null) return ReleaseVersion("0.1.0", 1)
        if (code == null || name == null) throw DeliveryException("AFM_VERSION_CODE and AFM_VERSION_NAME must be set together")
        val value = code.toIntOrNull()
        if (!codePattern.matches(code) || value == null || value !in 1..maximumCode.toInt()) {
            throw DeliveryException("AFM_VERSION_CODE must be in 1..$maximumCode")
        }
        if (!namePattern.matches(name)) throw DeliveryException("AFM_VERSION_NAME must be a numeric semantic version")
        return ReleaseVersion(name, value)
    }

    fun release(repository: Path, reachableCommitCount: String): ReleaseVersion {
        val base = releaseBase(repository)
        val count = reachableCommitCount.toLongOrNull()?.takeIf { countPattern.matches(reachableCommitCount) }
            ?: throw DeliveryException("Git reachable commit count is invalid")
        if (base > maximumCode || count > maximumCode - base) {
            throw DeliveryException("Release version code must be in 1..$maximumCode")
        }
        val code = base + count
        if (code !in 1..maximumCode) throw DeliveryException("Release version code must be in 1..$maximumCode")
        return ReleaseVersion("0.1.$code", code.toInt())
    }

    private fun releaseBase(repository: Path): Long {
        val path = repository.resolve("release-version-base.txt")
        val value = try {
            Files.readString(path).trim()
        } catch (_: Exception) {
            throw DeliveryException("release-version-base.txt is required")
        }
        return value.toLongOrNull()?.takeIf { countPattern.matches(value) }
            ?: throw DeliveryException("release-version-base.txt must contain a nonnegative integer")
    }
}

fun requireMainDelivery(environment: Map<String, String>) {
    if (environment["CI_PIPELINE_EVENT"] !in setOf("push", "manual") || environment["CI_COMMIT_BRANCH"] != "main") {
        throw DeliveryException("AFM delivery requires a trusted main push or main manual event")
    }
}

data class SourceContext(val source: ReleaseSource, val version: ReleaseVersion) {
    fun write(path: Path) {
        Files.createDirectories(path.parent)
        val json = buildJsonObject {
            put("afm_commit", source.afmCommit)
            put("spirit2_commit", source.spirit2Commit)
            put("version_name", version.name)
            put("version_code", version.code)
        }
        Files.writeString(path, json.toString() + "\n")
    }

    companion object {
        fun read(path: Path): SourceContext {
            val json = Json.parseToJsonElement(Files.readString(path)).jsonObject
            return SourceContext(
                ReleaseSource(json.getValue("afm_commit").jsonPrimitive.content, json.getValue("spirit2_commit").jsonPrimitive.content),
                ReleaseVersions.development(json.getValue("version_code").jsonPrimitive.content, json.getValue("version_name").jsonPrimitive.content),
            )
        }
    }
}

class GitSources(
    private val kmp: Path,
    private val commands: CommandRunner = SystemCommands(),
    private val environment: Map<String, String> = System.getenv(),
) {
    private val repository: Path by lazy {
        val actual = Path.of(git(kmp, "rev-parse", "--show-toplevel")).toRealPath()
        if (actual != kmp.toRealPath().parent) throw DeliveryException("Expected the AFM Git root directly above kmp; refusing an enclosing repository")
        actual
    }
    val spirit: Path by lazy { repository.resolve("deps/spirit2") }
    private val submodule = "deps/spirit2"

    private fun git(directory: Path, vararg arguments: String): String {
        val result = commands.run(listOf("git", *arguments), directory, sanitizedBuildEnvironment(environment))
        if (result.exitCode != 0) {
            val detail = result.output.trim().take(4096).ifEmpty { "no output" }
            throw DeliveryException("Git source verification failed for ${arguments.joinToString(" ")}: $detail")
        }
        return result.output.trim()
    }

    private fun head(): String {
        val commit = git(repository, "rev-parse", "HEAD")
        if (!Regex("[0-9a-f]{40}").matches(commit) || commit != environment["CI_COMMIT_SHA"]) {
            throw DeliveryException("Checkout does not match CI_COMMIT_SHA")
        }
        return commit
    }

    private fun pin(): String {
        val fields = git(repository, "ls-tree", "HEAD", submodule).split(Regex("\\s+"))
        if (fields.size != 4 || fields.take(2) != listOf("160000", "commit")) {
            throw DeliveryException("Spirit2 must be a pinned submodule")
        }
        return fields[2]
    }

    fun prepare() {
        requireMainDelivery(environment)
        head()
        val expected = pin()
        if (Files.exists(spirit.resolve(".git"))) {
            if (git(spirit, "rev-parse", "HEAD") != expected || git(spirit, "status", "--porcelain", "--untracked-files=no").isNotEmpty()) {
                throw DeliveryException("Refusing to update an existing dirty or mismatched Spirit2 checkout; use a fresh CI workspace")
            }
        } else {
            if (Files.exists(spirit, NOFOLLOW_LINKS)) {
                if (environment["AFM_CI_CLEAN_SUBMODULE"] != "1" || Files.isSymbolicLink(spirit)) {
                    throw DeliveryException("Pinned Spirit2 directory is incomplete; use a fresh CI workspace")
                }
                deleteTree(spirit)
            }
            git(repository, "submodule", "update", "--init", "--checkout", "--", submodule)
        }
        if (git(spirit, "rev-parse", "HEAD") != expected) throw DeliveryException("Spirit2 checkout does not match the gitlink")
    }

    fun context(): SourceContext {
        val commit = head()
        if (git(repository, "rev-parse", "--is-shallow-repository") != "false") {
            throw DeliveryException("Full Git history is required for monotonically increasing Android version codes")
        }
        val dependency = pin()
        if (git(spirit, "rev-parse", "HEAD") != dependency) throw DeliveryException("Spirit2 checkout does not match the pinned gitlink")
        if (git(repository, "status", "--porcelain", "--untracked-files=no").isNotEmpty() || git(spirit, "status", "--porcelain", "--untracked-files=no").isNotEmpty()) {
            throw DeliveryException("Delivery requires clean tracked source files")
        }
        return SourceContext(ReleaseSource(commit, dependency), ReleaseVersions.release(repository, git(repository, "rev-list", "--count", "HEAD")))
    }

    private fun deleteTree(path: Path) {
        Files.walk(path).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

object PrepareSpirit2 {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.size != 1) throw DeliveryException("Expected the KMP project directory")
        GitSources(Path.of(arguments.single())).prepare()
    }
}
