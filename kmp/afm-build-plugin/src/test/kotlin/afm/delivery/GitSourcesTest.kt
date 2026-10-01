package afm.delivery

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitSourcesTest {
    @TempDir lateinit var root: Path

    @Test
    fun `versions require a complete valid pair`() {
        assertEquals(ReleaseVersion("0.1.0", 1), ReleaseVersions.development(null, null))
        assertEquals(ReleaseVersion("0.1.42", 42), ReleaseVersions.development("42", "0.1.42"))
        listOf(null to "0.1.42", "42" to null, "0" to "0.1.0", "2100000001" to "0.1.0", "42" to "1.00.42").forEach { (code, name) ->
            assertThrows(DeliveryException::class.java) { ReleaseVersions.development(code, name) }
        }
    }

    @Test
    fun `release versions add the checked in base to all reachable commits`() {
        Files.writeString(root.resolve("release-version-base.txt"), "1752\n")
        val version = ReleaseVersions.release(root, "123")

        assertEquals(ReleaseVersion("0.1.1875", 1875), version)
    }

    @Test
    fun `release versions are stable across retries`() {
        Files.writeString(root.resolve("release-version-base.txt"), "1752")

        assertEquals(ReleaseVersions.release(root, "123"), ReleaseVersions.release(root, "123"))
    }

    @Test
    fun `release versions reject missing malformed and overflowing bases`() {
        assertThrows(DeliveryException::class.java) { ReleaseVersions.release(root, "1") }
        listOf("", "-1", "not-a-number").forEach { base ->
            Files.writeString(root.resolve("release-version-base.txt"), base)
            assertThrows(DeliveryException::class.java) { ReleaseVersions.release(root, "1") }
        }
        Files.writeString(root.resolve("release-version-base.txt"), "2100000000")
        assertThrows(DeliveryException::class.java) { ReleaseVersions.release(root, "1") }
    }

    @Test
    fun `source contexts persist AFM provenance`() {
        val context = SourceContext(ReleaseSource("a".repeat(40), "b".repeat(40)), ReleaseVersion("0.1.1753", 1753))
        val path = root.resolve("context.json")

        context.write(path)

        val json = Files.readString(path)
        assertTrue(json.contains("\"afm_commit\""))
        assertFalse(json.contains("\"atlas_commit\""))
        assertEquals(context, SourceContext.read(path))
    }

    @Test
    fun `only trusted main events can deliver`() {
        listOf("push", "manual").forEach { requireMainDelivery(mapOf("CI_PIPELINE_EVENT" to it, "CI_COMMIT_BRANCH" to "main")) }
        listOf("pull_request" to "main", "push" to "feature", "tag" to "main").forEach { (event, branch) ->
            assertThrows(DeliveryException::class.java) {
                requireMainDelivery(mapOf("CI_PIPELINE_EVENT" to event, "CI_COMMIT_BRANCH" to branch))
            }
        }
    }

    @Test
    fun `context checks exact pin clean state and complete history`() {
        val commit = "a".repeat(40)
        val dependency = "b".repeat(40)
        Files.writeString(root.resolve("release-version-base.txt"), "1752")
        val answers = ArrayDeque(listOf(root.toString(), commit, "false", "160000 commit $dependency\tdeps/spirit2", dependency, "", "", "123"))
        val calls = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _, _ ->
            calls += command
            CommandResult(0, answers.removeFirst())
        }
        val kmp = Files.createDirectories(root.resolve("kmp"))
        val context = GitSources(kmp, runner, mapOf("CI_COMMIT_SHA" to commit)).context()
        assertEquals(ReleaseSource(commit, dependency), context.source)
        assertEquals(ReleaseVersion("0.1.1875", 1875), context.version)
        assertEquals(listOf("git", "rev-list", "--count", "HEAD"), calls.last())
        assertTrue(answers.isEmpty())
    }

    @Test
    fun `shallow history and mismatched checkout fail before version assignment`() {
        val commit = "a".repeat(40)
        for (answers in listOf(listOf(root.toString(), "b".repeat(40)), listOf(root.toString(), commit, "true"))) {
            val remaining = ArrayDeque(answers)
            val runner = CommandRunner { _, _, _ -> CommandResult(0, remaining.removeFirst()) }
            assertThrows(DeliveryException::class.java) {
                GitSources(Files.createDirectories(root.resolve("kmp")), runner, mapOf("CI_COMMIT_SHA" to commit)).context()
            }
        }
    }

    @Test
    fun `delivery rejects an enclosing repository instead of AFM`() {
        val kmp = Files.createDirectories(root.resolve("afm/kmp"))
        val runner = CommandRunner { _, _, _ -> CommandResult(0, root.toString()) }
        val error = assertThrows(DeliveryException::class.java) { GitSources(kmp, runner).context() }
        assertTrue(error.message.orEmpty().contains("AFM Git root"))
    }

    @Test
    fun `tracked compatibility base never falls below the extraction floor`() {
        val repository = Path.of(System.getProperty("user.dir")).parent.parent
        assertTrue(Files.readString(repository.resolve("release-version-base.txt")).trim().toLong() >= 1752L)
    }

    @Test
    fun `bootstrap refuses to change an existing mismatched or dirty dependency`() {
        val kmp = root.resolve("kmp")
        val spirit = root.resolve("deps/spirit2")
        Files.createDirectories(kmp)
        Files.createDirectories(spirit)
        Files.writeString(spirit.resolve(".git"), "fixture")
        val commit = "a".repeat(40)
        val dependency = "b".repeat(40)
        val environment = mapOf("CI_PIPELINE_EVENT" to "push", "CI_COMMIT_BRANCH" to "main", "CI_COMMIT_SHA" to commit)
        for (state in listOf(listOf("c".repeat(40)), listOf(dependency, " M source.rs"))) {
            val outputs = ArrayDeque(listOf(root.toString(), commit, "160000 commit $dependency\tdeps/spirit2") + state)
            val calls = mutableListOf<List<String>>()
            val runner = CommandRunner { command, _, _ -> calls += command; CommandResult(0, outputs.removeFirst()) }
            assertThrows(DeliveryException::class.java) { GitSources(kmp, runner, environment).prepare() }
            assertFalse(calls.any { "submodule" in it })
        }
    }

    @Test
    fun `incomplete dependency is only cleaned in the explicit disposable CI mode`() {
        val kmp = root.resolve("kmp")
        val spirit = root.resolve("deps/spirit2")
        Files.createDirectories(kmp)
        Files.createDirectories(spirit)
        Files.writeString(spirit.resolve("failed-clone"), "partial")
        val commit = "a".repeat(40)
        val dependency = "b".repeat(40)
        val base = mapOf("CI_PIPELINE_EVENT" to "push", "CI_COMMIT_BRANCH" to "main", "CI_COMMIT_SHA" to commit)
        val blockedAnswers = ArrayDeque(listOf(root.toString(), commit, "160000 commit $dependency\tdeps/spirit2"))
        val blocked = CommandRunner { command, _, _ ->
            if ("submodule" in command) error("submodule update must not run")
            else CommandResult(0, blockedAnswers.removeFirst())
        }
        assertThrows(DeliveryException::class.java) { GitSources(kmp, blocked, base).prepare() }
        assertTrue(Files.exists(spirit.resolve("failed-clone")))
        val calls = mutableListOf<List<String>>()
        val runner = CommandRunner { command, directory, _ ->
            calls += command
            val output = when {
                command.contains("--show-toplevel") -> root.toString()
                command.contains("ls-tree") -> "160000 commit $dependency\tdeps/spirit2"
                command.takeLast(2) == listOf("rev-parse", "HEAD") && directory == spirit -> dependency
                command.takeLast(2) == listOf("rev-parse", "HEAD") -> commit
                else -> ""
            }
            CommandResult(0, output)
        }
        GitSources(kmp, runner, base + ("AFM_CI_CLEAN_SUBMODULE" to "1")).prepare()
        assertFalse(Files.exists(spirit))
        assertTrue(calls.contains(listOf("git", "submodule", "update", "--init", "--checkout", "--", "deps/spirit2")))
    }

    @Test
    fun `bootstrap initializes only the named dependency without force or recursion`() {
        val kmp = root.resolve("kmp")
        Files.createDirectories(kmp)
        val commit = "a".repeat(40)
        val dependency = "b".repeat(40)
        val environment = mapOf("CI_PIPELINE_EVENT" to "push", "CI_COMMIT_BRANCH" to "main", "CI_COMMIT_SHA" to commit)
        val outputs = ArrayDeque(listOf(root.toString(), commit, "160000 commit $dependency\tdeps/spirit2", "", dependency))
        val calls = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _, _ -> calls += command; CommandResult(0, outputs.removeFirst()) }
        GitSources(kmp, runner, environment).prepare()
        assertTrue(calls.contains(listOf("git", "submodule", "update", "--init", "--checkout", "--", "deps/spirit2")))
        assertFalse(calls.any { "--force" in it || "--recursive" in it })
    }

    @Test
    fun `build tools never inherit delivery secrets`() {
        val environment = sanitizedBuildEnvironment(mapOf("AFM_KEYSTORE_PASSWORD" to "secret", "AFM_FORGEJO_TOKEN" to "token", "GH_TOKEN" to "github", "GITHUB_TOKEN" to "github-alias", "ANDROID_NDK_ROOT" to "/ndk", "PATH" to "/tools"))
        assertFalse(environment.containsKey("AFM_KEYSTORE_PASSWORD"))
        assertFalse(environment.containsKey("AFM_FORGEJO_TOKEN"))
        assertFalse(environment.containsKey("GH_TOKEN"))
        assertFalse(environment.containsKey("GITHUB_TOKEN"))
        assertEquals("/ndk", environment["ANDROID_NDK_HOME"])
        assertEquals("/tools", environment["PATH"])
    }

    @Test
    fun `reachable count increases when main fast forwards to a branch that merged main`() {
        val runner = SystemCommands()
        val environment = System.getenv() + mapOf("GIT_EDITOR" to "true", "GIT_AUTHOR_NAME" to "AFM test", "GIT_AUTHOR_EMAIL" to "afm@example.invalid", "GIT_COMMITTER_NAME" to "AFM test", "GIT_COMMITTER_EMAIL" to "afm@example.invalid")
        fun git(vararg arguments: String): String {
            val result = runner.run(listOf("git", *arguments), root, environment)
            assertEquals(0, result.exitCode, result.output)
            return result.output.trim()
        }
        Files.writeString(root.resolve("release-version-base.txt"), "1752")
        git("init", "-b", "main")
        git("-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "root")
        git("branch", "topic")
        repeat(4) { git("-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "main $it") }
        val previous = git("rev-list", "--count", "HEAD").toInt()
        git("checkout", "topic")
        git("-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "topic")
        git("-c", "commit.gpgsign=false", "merge", "--no-edit", "main")
        git("checkout", "main")
        git("merge", "--ff-only", "topic")
        assertTrue(git("rev-list", "--first-parent", "--count", "HEAD").toInt() < previous)
        assertTrue(ReleaseVersions.release(root, git("rev-list", "--count", "HEAD")).code > 1752 + previous)
    }
}
