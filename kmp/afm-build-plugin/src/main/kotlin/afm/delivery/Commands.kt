package afm.delivery

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

class DeliveryException(message: String) : RuntimeException(message)

internal val signingSecretNames = setOf("AFM_KEYSTORE_BASE64", "AFM_KEYSTORE_PASSWORD", "AFM_KEY_ALIAS", "AFM_KEY_PASSWORD")
private val publicationSecretNames = setOf("AFM_FORGEJO_TOKEN", "GH_TOKEN", "GITHUB_TOKEN")

fun sanitizedBuildEnvironment(environment: Map<String, String>): Map<String, String> {
    val clean = environment.filterKeys { it !in signingSecretNames && it !in publicationSecretNames }
    return if (clean["ANDROID_NDK_HOME"].isNullOrBlank() && !clean["ANDROID_NDK_ROOT"].isNullOrBlank()) {
        clean + ("ANDROID_NDK_HOME" to clean.getValue("ANDROID_NDK_ROOT"))
    } else clean
}

data class CommandResult(val exitCode: Int, val output: String)

fun interface CommandRunner {
    fun run(command: List<String>, directory: Path, environment: Map<String, String>): CommandResult
}

class SystemCommands(private val timeout: Duration = Duration.ofMinutes(30)) : CommandRunner {
    override fun run(command: List<String>, directory: Path, environment: Map<String, String>): CommandResult {
        val output = Files.createTempFile("afm-command-", ".log")
        try {
            val builder = ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
            builder.environment().apply {
                clear()
                putAll(environment)
            }
            val process = builder.start()
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw DeliveryException("Build tool timed out")
                }
                return CommandResult(process.exitValue(), Files.readString(output))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw DeliveryException("Build tool was interrupted")
            } finally {
                if (process.isAlive) {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                }
            }
        } finally {
            Files.deleteIfExists(output)
        }
    }
}
