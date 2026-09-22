package afm.delivery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DevenvAliasesTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var kmpRoot: Path
    private lateinit var afmRoot: Path
    private lateinit var kmpScripts: Map<String, Path>
    private lateinit var rootScripts: Map<String, Path>
    private lateinit var fixture: AliasFixture

    @BeforeAll
    fun loadAliases() {
        assumeTrue(devenvAvailable(), "devenv is unavailable")
        kmpRoot = findKmpRoot()
        afmRoot = kmpRoot.parent
        kmpScripts = evaluatedScripts(kmpRoot)
        rootScripts = evaluatedScripts(afmRoot)
    }

    @AfterEach
    fun cleanFixture() {
        if (::fixture.isInitialized) {
            fixture.close()
        }
    }

    @Test
    fun `root derives every public KMP run release and test alias`() {
        val expected = kmpScripts.keys.filter(::isPublicKmpAlias).toSet()
        val actual = rootScripts.keys.filter(::isPublicKmpAlias).toSet()

        assertTrue(actual.containsAll(expected))
        assertFalse(actual.contains("ios-native"))
    }

    @Test
    fun `root dispatch enters KMP with runtime display and device variables`() {
        fixture = AliasFixture(temporaryDirectory, "afm")

        val result = fixture.dispatchRoot(rootScripts.getValue("run-desktop"), "--stacktrace")

        assertEquals(0, result.exitCode, result.output)
        assertEquals(
            listOf(
                fixture.afm.resolve("kmp").toString(),
                fixture.afm.toString(),
                "device-42",
                ":1",
                "wayland-1",
                "1",
                "shell",
                "--",
                "run-desktop",
                "--stacktrace",
            ),
            fixture.records().single(),
        )
    }

    @Test
    fun `root dispatch fails without its selected child directory`() {
        fixture = AliasFixture(temporaryDirectory, "missing-child")
        fixture.deleteKmpChild()

        val result = fixture.dispatchRoot(rootScripts.getValue("run-desktop"))

        assertNotEquals(0, result.exitCode)
        assertTrue(fixture.records().isEmpty())
    }

    @Test
    fun `root dispatch preserves child shell failures`() {
        fixture = AliasFixture(temporaryDirectory, "child-failure")

        val result = fixture.dispatchRoot(rootScripts.getValue("test-godot"), exitCode = 37)

        assertEquals(37, result.exitCode, result.output)
        assertEquals(listOf("shell", "--", "test-godot"), fixture.records().single().takeLast(3))
    }

    @Test
    fun `CLI defaults to help and forwards all user arguments`() {
        fixture = AliasFixture(temporaryDirectory, "cli")

        val defaultResult = fixture.dispatchKmp(kmpScripts.getValue("run-cli"))
        assertEquals(0, defaultResult.exitCode, defaultResult.output)
        assertEquals(
            listOf("cargo", "run", "--locked", "-p", "spirit-cli", "--", "--help"),
            fixture.records().last(),
        )

        fixture.clearRecords()
        val argumentsResult = fixture.dispatchKmp(kmpScripts.getValue("run-cli"), "store", "list")
        assertEquals(0, argumentsResult.exitCode, argumentsResult.output)
        assertEquals(
            listOf("cargo", "run", "--locked", "-p", "spirit-cli", "--", "store", "list"),
            fixture.records().last(),
        )
    }

    @Test
    fun `Android launch builds before it launches`() {
        fixture = AliasFixture(temporaryDirectory, "run-android")

        val result = fixture.dispatchKmp(kmpScripts.getValue("run-android"), "--info")

        assertEquals(0, result.exitCode, result.output)
        val gradleTasks = fixture.gradleRecords().map { record -> record.first { it.startsWith(":") || it.startsWith("afm") } }
        assertTrue(gradleTasks.indexOf(":app:androidApp:assembleDebug") < gradleTasks.indexOf("afmRunAndroid"))
        assertEquals("--info", fixture.gradleRecords().last().last())
    }

    @Test
    fun `web development aliases retain continuous rebuild and arguments`() {
        fixture = AliasFixture(temporaryDirectory, "web")

        val result = fixture.dispatchKmp(kmpScripts.getValue("run-web"), "--port=8080")

        assertEquals(0, result.exitCode, result.output)
        assertEquals(
            listOf(
                "gradlew",
                "-p",
                fixture.kmp.toString(),
                ":app:webApp:jsBrowserDevelopmentRun",
                "--continuous",
                "--port=8080",
            ),
            fixture.records().last(),
        )
    }

    @Test
    fun `Android instrumentation selects a device before a separate serial scoped test invocation`() {
        fixture = AliasFixture(temporaryDirectory, "test-android")

        val result = fixture.dispatchKmp(kmpScripts.getValue("test-android"), "--info")

        assertEquals(0, result.exitCode, result.output)
        val selections = fixture.gradleRecords().filter { record -> record.contains("afmSelectAndroid") }
        assertEquals(1, selections.size)
        val selectedDeviceOutput = selections.single().first { it.startsWith("-PafmDeviceOutput=") }.removePrefix("-PafmDeviceOutput=")
        assertFalse(Path.of(selectedDeviceOutput).exists())
        val instrumentation = fixture.gradleRecords().single { record -> record.contains(":app:shared:connectedAndroidTest") }
        assertTrue(instrumentation.contains("--info"))
        assertEquals(listOf("android-serial", "selected-device"), fixture.records().single { it.first() == "android-serial" })
        assertTrue(fixture.gradleRecords().indexOf(selections.single()) < fixture.gradleRecords().indexOf(instrumentation))
    }

    @Test
    fun `plumbing runs the non-device test sequence including alias tests`() {
        fixture = AliasFixture(temporaryDirectory, "test-plumbing")

        val result = fixture.dispatchKmp(kmpScripts.getValue("test-plumbing"))

        assertEquals(0, result.exitCode, result.output)
        assertEquals(
            listOf("test-rust", "test-sdk", "test-desktop", "test-launcher", "test-aliases"),
            fixture.records().map { it.first() },
        )
    }

    @Test
    fun `preparation errors stop aliases before Gradle runs`() {
        fixture = AliasFixture(temporaryDirectory, "preparation-error")

        val result = fixture.dispatchKmp(kmpScripts.getValue("run-desktop"), checkSpiritExit = 19)

        assertEquals(19, result.exitCode)
        assertEquals(listOf("check-spirit"), fixture.records().map { it.first() })
    }

    private fun isPublicKmpAlias(name: String): Boolean =
        name.startsWith("run-") || name.startsWith("release-") || name.startsWith("test-")

    private fun devenvAvailable(): Boolean =
        try {
            ProcessBuilder("devenv", "--version").redirectErrorStream(true).start().let { process ->
                process.inputStream.readAllBytes()
                process.waitFor() == 0
            }
        } catch (_: IOException) {
            false
        }

    private fun findKmpRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { candidate ->
                candidate.resolve("devenv.nix").exists() && candidate.resolve("build-logic").isDirectory()
            }
            ?: error("Cannot locate the AFM KMP directory from the Gradle test working directory")

    private fun evaluatedScripts(project: Path): Map<String, Path> {
        val process = ProcessBuilder("devenv", "info")
            .directory(project.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), output)
        return output.lineSequence()
            .mapNotNull { line -> scriptPattern.matchEntire(line) }
            .associate { match ->
                val name = match.groupValues[1]
                name to Path.of(match.groupValues[2]).resolve("bin").resolve(name)
            }
    }

    private class AliasFixture(
        temporaryDirectory: Path,
        name: String,
    ) {
        val repository = temporaryDirectory.resolve(name)
        val afm = repository.resolve("afm")
        val kmp = afm.resolve("kmp")
        private val mockBin = repository.resolve("mock-bin")
        private val state = repository.resolve("devenv-state")
        private val log = repository.resolve("dispatch.log")
        private val dollar = '$'

        init {
            afm.resolve("bevy").createDirectories()
            afm.resolve("godot").createDirectories()
            kmp.createDirectories()
            kmp.resolve("../deps/spirit2/rust").normalize().createDirectories()
            mockBin.createDirectories()
            state.createDirectories()
            writeMock("check-spirit", "exit \"\${AFM_CHECK_SPIRIT_EXIT:-0}\"")
            writeMock("cargo")
            listOf("test-rust", "test-sdk", "test-desktop", "test-launcher", "test-aliases").forEach(::writeMock)
            writeGradleMock()
            writeRootDevenvMock()
        }

        fun dispatchKmp(script: Path, vararg arguments: String, checkSpiritExit: Int = 0): Result =
            dispatch(script, kmp, mapOf("AFM_CHECK_SPIRIT_EXIT" to checkSpiritExit.toString()), *arguments)

        fun dispatchRoot(script: Path, vararg arguments: String, exitCode: Int = 0): Result =
            dispatch(
                script,
                afm,
                mapOf(
                    "AFM_ROOT_EXIT" to exitCode.toString(),
                    "ANDROID_SERIAL" to "device-42",
                    "DISPLAY" to ":1",
                    "WAYLAND_DISPLAY" to "wayland-1",
                    "__NV_PRIME_RENDER_OFFLOAD" to "1",
                ),
                *arguments,
            )

        fun records(): List<List<String>> =
            if (!log.exists()) {
                emptyList()
            } else {
                log.toFile().readBytes().decodeToString().split('\u0000')
                    .fold(mutableListOf(mutableListOf<String>())) { records, field ->
                        if (field.isEmpty()) {
                            if (records.last().isNotEmpty()) {
                                records.add(mutableListOf())
                            }
                        } else {
                            records.last().add(field)
                        }
                        records
                    }
                    .filter { it.isNotEmpty() }
                    .map { it.toList() }
            }

        fun clearRecords() {
            Files.deleteIfExists(log)
        }

        fun deleteKmpChild() {
            Files.walk(kmp).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }

        fun gradleRecords(): List<List<String>> = records().filter { it.firstOrNull() == "gradlew" }

        fun close() {
            if (repository.exists()) {
                Files.walk(repository).sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }

        private fun dispatch(script: Path, root: Path, values: Map<String, String>, vararg arguments: String): Result {
            val process = ProcessBuilder(listOf(script.toString()) + arguments)
                .directory(root.toFile())
                .redirectErrorStream(true)
            process.environment().apply {
                put("AFM_ALIAS_LOG", log.toString())
                put("DEVENV_ROOT", root.toString())
                put("DEVENV_STATE", state.toString())
                remove("ANDROID_SERIAL")
                remove("DISPLAY")
                remove("WAYLAND_DISPLAY")
                remove("__NV_PRIME_RENDER_OFFLOAD")
                put("PATH", "$mockBin${java.io.File.pathSeparator}${get("PATH").orEmpty()}")
                putAll(values)
            }
            val started = process.start()
            val output = started.inputStream.readAllBytes().decodeToString()
            return Result(started.waitFor(), output)
        }

        private fun writeMock(name: String, suffix: String = "") {
            mockBin.resolve(name).writeText(
                """
                #!/usr/bin/env bash
                set -eu
                printf '%s\0' '$name' "${dollar}@" '' >> "${dollar}AFM_ALIAS_LOG"
                $suffix
                """.trimIndent(),
            )
            mockBin.resolve(name).toFile().setExecutable(true)
        }

        private fun writeGradleMock() {
            kmp.resolve("gradlew").writeText(
                """
                #!/usr/bin/env bash
                set -eu
                printf '%s\0' 'gradlew' "${dollar}@" '' >> "${dollar}AFM_ALIAS_LOG"
                selection=false
                instrumentation=false
                output=
                for argument in "${dollar}@"; do
                  case "${dollar}argument" in
                    -PafmDeviceOutput=*) output="${dollar}{argument#-PafmDeviceOutput=}" ;;
                  esac
                  case "${dollar}argument" in
                    *afmSelectAndroid) selection=true ;;
                    *connectedAndroidTest) instrumentation=true ;;
                  esac
                done
                if [ "${dollar}selection" = true ]; then printf selected-device > "${dollar}output"; fi
                if [ "${dollar}instrumentation" = true ]; then printf '%s\0' 'android-serial' "${dollar}{ANDROID_SERIAL:-}" '' >> "${dollar}AFM_ALIAS_LOG"; fi
                exit "${dollar}{AFM_GRADLE_EXIT:-0}"
                """.trimIndent(),
            )
            kmp.resolve("gradlew").toFile().setExecutable(true)
        }

        private fun writeRootDevenvMock() {
            mockBin.resolve("devenv").writeText(
                """
                #!/usr/bin/env bash
                set -eu
                printf '%s\0' "${dollar}PWD" "${dollar}DEVENV_ROOT" "${dollar}{ANDROID_SERIAL:-}" "${dollar}{DISPLAY:-}" "${dollar}{WAYLAND_DISPLAY:-}" "${dollar}{__NV_PRIME_RENDER_OFFLOAD:-}" "${dollar}@" '' >> "${dollar}AFM_ALIAS_LOG"
                exit "${dollar}{AFM_ROOT_EXIT:-0}"
                """.trimIndent(),
            )
            mockBin.resolve("devenv").toFile().setExecutable(true)
        }
    }

    private data class Result(
        val exitCode: Int,
        val output: String,
    )

    private companion object {
        val scriptPattern = Regex("^- ([a-z0-9-]+): .* (/nix/store/\\S+)$")
    }
}
