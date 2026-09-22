package afm.delivery

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AndroidSignerTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun signsVerifiedApkWithoutExposingSecrets() {
        val runner = FakeCommands()
        val input = unsignedApk()
        val output = root.resolve("signed.apk")

        val metadata = signer(runner).sign(input, output, 42, "1.0.42", environment())

        assertEquals(SigningMetadata("com.abysl.afm", 42, "1.0.42", "ab".repeat(32)), metadata)
        assertTrue(output.isRegularFile())
        assertEquals("unsigned", input.readBytes().decodeToString())
        val arguments = runner.calls.flatMap { it.command }
        listOf("AFM_KEYSTORE_BASE64", "AFM_KEYSTORE_PASSWORD", "AFM_KEY_PASSWORD").forEach { name ->
            assertFalse(arguments.contains(environment()[name]))
        }
        runner.calls.forEach { call ->
            assertFalse(call.environment.containsKey("AFM_KEYSTORE_BASE64"))
            assertFalse(call.environment.containsKey("AFM_KEY_ALIAS"))
            assertFalse(call.environment.containsKey("AFM_FORGEJO_TOKEN"))
            assertEquals("store-password", call.environment["AFM_KEYSTORE_PASSWORD"])
            assertEquals("key-password", call.environment["AFM_KEY_PASSWORD"])
        }
    }

    @Test
    fun makesPrivateExternalKeystoreAndCleansItAfterSuccessAndFailure() {
        listOf(null, "verify").forEach { failure ->
            val runner = FakeCommands(failure = failure)
            val output = root.resolve("$failure.apk")

            if (failure == null) {
                signer(runner).sign(unsignedApk(), output, 42, "1.0.42", environment())
            } else {
                assertThrows(DeliveryException::class.java) {
                    signer(runner).sign(unsignedApk(), output, 42, "1.0.42", environment())
                }
            }

            assertTrue(runner.keystores.isNotEmpty())
            assertTrue(runner.keystores.all { !it.exists() })
            assertTrue(runner.keystores.all { it.parent.parent != root })
            if (failure != null) assertFalse(output.exists())
        }
    }

    @Test
    fun rejectsMissingOrMalformedSecretsBeforeRunningTools() {
        listOf(
            environment().minus("AFM_KEY_PASSWORD"),
            environment().plus("AFM_KEYSTORE_BASE64" to "not base64"),
            environment().plus("AFM_KEYSTORE_BASE64" to "a2V5c3RvcmU"),
            environment().plus("AFM_KEYSTORE_BASE64" to "a2V5c3RvcmU=\n"),
        ).forEach { secrets ->
            val runner = FakeCommands()

            assertThrows(DeliveryException::class.java) {
                signer(runner).sign(unsignedApk(), root.resolve("signed.apk"), 42, "1.0.42", secrets)
            }

            assertTrue(runner.calls.isEmpty())
        }
    }

    @Test
    fun rejectsInvalidVersionsBeforeRunningTools() {
        listOf(0 to "1.0.0", 2_100_000_001 to "1.0.0", 42 to "1.0", 42 to "1.00.0").forEach { (code, name) ->
            val runner = FakeCommands()

            assertThrows(DeliveryException::class.java) {
                signer(runner).sign(unsignedApk(), root.resolve("$code-$name.apk"), code, name, environment())
            }

            assertTrue(runner.calls.isEmpty())
        }
    }

    @Test
    fun doesNotPublishOutputOnToolFailureOrTimeout() {
        listOf("verify", "timeout").forEach { failure ->
            val runner = FakeCommands(failure = failure)
            val output = root.resolve("$failure.apk")
            val error = assertThrows(DeliveryException::class.java) {
                signer(runner).sign(unsignedApk(), output, 42, "1.0.42", environment())
            }

            assertFalse(output.exists())
            assertFalse(error.message.orEmpty().contains("store-password"))
            assertFalse(error.message.orEmpty().contains("tool output"))
        }
    }

    @Test
    fun rejectsUnexpectedCertificateMetadataAndNativeLibraries() {
        listOf(
            FakeCommands(certificate = "not-a-digest"),
            FakeCommands(applicationId = "other.application"),
            FakeCommands(versionCode = "43"),
            FakeCommands(versionName = "1.0.43"),
            FakeCommands(nativeEntries = setOf("lib/arm64-v8a/libspirit_ffi.so")),
        ).forEachIndexed { index, runner ->
            val output = root.resolve("rejected-$index.apk")

            assertThrows(DeliveryException::class.java) {
                signer(runner).sign(unsignedApk(), output, 42, "1.0.42", environment())
            }

            assertFalse(output.exists())
        }
    }

    @Test
    fun discoversOnlyConfiguredExecutableBuildTools() {
        val tools = root.resolve("tools").also(Path::createDirectories)
        listOf("apksigner", "aapt2").forEach { name ->
            tools.resolve(name).writeText("")
            tools.resolve(name).toFile().setExecutable(true, true)
        }
        val runner = FakeCommands()

        signer(runner).sign(
            unsignedApk(),
            root.resolve("signed.apk"),
            42,
            "1.0.42",
            environment().plus("AFM_ANDROID_BUILD_TOOLS" to tools.toString()),
        )

        assertEquals(tools.resolve("apksigner").toString(), runner.calls.first().command.first())
    }

    private fun signer(runner: CommandRunner) = AndroidSigner(runner)

    private fun unsignedApk(): Path = root.resolve("unsigned-${Files.list(root).use { it.count() }}.apk").also {
        it.writeBytes("unsigned".encodeToByteArray())
    }

    private fun environment(): Map<String, String> = mapOf(
        "AFM_KEYSTORE_BASE64" to Base64.getEncoder().encodeToString("keystore".encodeToByteArray()),
        "AFM_KEYSTORE_PASSWORD" to "store-password",
        "AFM_KEY_ALIAS" to "release",
        "AFM_KEY_PASSWORD" to "key-password",
        "AFM_FORGEJO_TOKEN" to "forgejo-token",
        "AFM_ANDROID_BUILD_TOOLS" to buildTools().toString(),
    )

    private fun buildTools(): Path = root.resolve("build-tools").also { directory ->
        directory.createDirectories()
        listOf("apksigner", "aapt2").forEach { name ->
            directory.resolve(name).also { tool ->
                if (!tool.exists()) tool.writeText("")
                tool.toFile().setExecutable(true, true)
            }
        }
    }

    private data class Call(val command: List<String>, val environment: Map<String, String>)

    private class FakeCommands(
        private val certificate: String = "ab".repeat(32),
        private val applicationId: String = "com.abysl.afm",
        private val versionCode: String = "42",
        private val versionName: String = "1.0.42",
        private val nativeEntries: Set<String> = setOf(
            "lib/arm64-v8a/libspirit_ffi.so",
            "lib/x86_64/libspirit_ffi.so",
        ),
        private val failure: String? = null,
    ) : CommandRunner {
        val calls = mutableListOf<Call>()
        val keystores = mutableListOf<Path>()

        override fun run(command: List<String>, directory: Path, environment: Map<String, String>): CommandResult {
            calls += Call(command, environment)
            val operation = command[1]
            if (failure == "timeout") throw DeliveryException("Build tool timed out")
            if (failure == operation) return CommandResult(1, "tool output store-password")
            return when (operation) {
                "sign" -> {
                    val keystore = Path.of(command[command.indexOf("--ks") + 1])
                    keystores.add(keystore)
                    assertTrue(keystore.isRegularFile())
                    assertEquals(
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(keystore),
                    )
                    val signed = Path.of(command[command.indexOf("--out") + 1])
                    ZipOutputStream(Files.newOutputStream(signed)).use { archive ->
                        nativeEntries.forEach { entry ->
                            archive.putNextEntry(java.util.zip.ZipEntry(entry))
                            archive.write("native".encodeToByteArray())
                            archive.closeEntry()
                        }
                    }
                    CommandResult(0, "")
                }
                "verify" -> CommandResult(0, "Signer #1 certificate SHA-256 digest: $certificate\n")
                "dump" -> CommandResult(
                    0,
                    "package: name='$applicationId' versionCode='$versionCode' versionName='$versionName'\n",
                )
                else -> error("unexpected command: $command")
            }
        }
    }
}
