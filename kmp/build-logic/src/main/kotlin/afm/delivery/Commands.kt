package afm.delivery

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

class DeliveryException(message: String) : RuntimeException(message)

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
