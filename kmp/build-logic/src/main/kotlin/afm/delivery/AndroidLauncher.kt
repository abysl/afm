package afm.delivery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

private const val applicationId = "com.abysl.afm"
private const val launcherActivity = ".MainActivity"
private const val defaultAvd = "afm"

interface EmulatorProcess {
    fun exitCode(): Int?
    fun stop()
}

fun interface EmulatorProcessStarter {
    fun start(command: List<String>, directory: Path, environment: Map<String, String>, logPath: Path): EmulatorProcess
}

fun interface AvdCreator {
    fun create(command: List<String>, directory: Path, environment: Map<String, String>, timeout: Duration): CommandResult
}

class AndroidLauncher(
    private val projectRoot: Path,
    private val environment: Map<String, String> = System.getenv(),
    private val commandRunnerFactory: (Duration) -> CommandRunner = { SystemCommands(it) },
    private val emulatorProcessStarter: EmulatorProcessStarter = ProcessEmulatorStarter,
    private val avdCreator: AvdCreator = ProcessAvdCreator,
    private val monotonicMilliseconds: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val diagnostic: (String) -> Unit = System.err::println,
    private val timeout: Duration = Duration.ofSeconds(180),
    private val pollInterval: Duration = Duration.ofSeconds(2),
) {
    fun selectDevice(): String {
        val devices = devices()
        if (devices.isNotEmpty()) return selectConnected(devices)
        val requested = environment["ANDROID_SERIAL"]
        if (!requested.isNullOrBlank()) {
            throw DeliveryException("ANDROID_SERIAL '$requested' was requested, but no adb devices are connected")
        }
        return startEmulator()
    }

    fun install(serial: String, apk: Path) {
        checkResult(
            run(listOf("adb", "-s", serial, "install", "-r", apk.toString()), "APK install failed for $apk on $serial"),
            "APK install failed for $apk on $serial",
        )
    }

    fun launch(serial: String) {
        val component = "$applicationId/$launcherActivity"
        checkResult(
            run(listOf("adb", "-s", serial, "shell", "am", "start", "-W", "-n", component), "launching AFM launcher activity failed on $serial"),
            "launching AFM launcher activity failed on $serial",
        )
    }

    private fun selectConnected(devices: List<Device>): String {
        val requested = environment["ANDROID_SERIAL"]
        if (!requested.isNullOrBlank()) {
            val selected = devices.find { it.serial == requested }
                ?: throw DeliveryException("ANDROID_SERIAL '$requested' was not found among connected devices: ${devices.joinToString(", ") { it.serial }}")
            if (selected.state != "device") throw DeliveryException(unreadyMessage(listOf(selected)))
            return waitForExistingEmulator(selected.serial)
        }
        val ready = devices.filter { it.state == "device" }
        return when (ready.size) {
            1 -> waitForExistingEmulator(ready.single().serial)
            0 -> throw DeliveryException(unreadyMessage(devices))
            else -> throw DeliveryException("multiple ready devices: ${ready.joinToString(", ") { it.serial }}; set ANDROID_SERIAL")
        }
    }

    private fun waitForExistingEmulator(serial: String): String {
        if (!serial.startsWith("emulator-")) return serial
        diagnostic("Reusing existing emulator $serial; waiting for Android boot completion")
        return waitForBoot(serial, deadline())
    }

    private fun startEmulator(): String {
        val avdName = environment["AFM_ANDROID_AVD"].orEmpty().ifBlank { defaultAvd }
        if (avdName !in availableAvds()) createAvd(avdName)
        val logPath = projectRoot.resolve(".android/afm-emulator.log")
        val command = buildList {
            add("emulator")
            add("-avd")
            add(avdName)
            if (noWindowRequested()) add("-no-window")
        }
        val emulatorEnvironment = environment - "LD_LIBRARY_PATH"
        val process = try {
            emulatorProcessStarter.start(command, projectRoot, emulatorEnvironment, logPath)
        } catch (error: Exception) {
            throw DeliveryException("starting emulator for AVD '$avdName' failed: ${error.message ?: error.javaClass.simpleName}")
        }
        try {
            return waitForStartedEmulator(process, logPath, avdName)
        } catch (_: InterruptedException) {
            process.stop()
            Thread.currentThread().interrupt()
            throw DeliveryException("Android emulator startup was interrupted")
        } catch (error: Exception) {
            process.stop()
            throw error
        }
    }

    private fun createAvd(avdName: String) {
        val systemImage = environment["AFM_ANDROID_SYSTEM_IMAGE"]?.takeIf { it.isNotBlank() }
            ?: throw DeliveryException("AVD '$avdName' does not exist and AFM_ANDROID_SYSTEM_IMAGE is not set")
        val command = listOf("avdmanager", "create", "avd", "--name", avdName, "--package", systemImage, "--device", "pixel")
        val result = try {
            avdCreator.create(command, projectRoot, environment, timeout)
        } catch (error: DeliveryException) {
            throw DeliveryException("creating project-managed AVD '$avdName' failed: ${error.message}")
        } catch (error: Exception) {
            throw DeliveryException("creating project-managed AVD '$avdName' failed: ${error.message ?: error.javaClass.simpleName}")
        }
        checkResult(result, "creating project-managed AVD '$avdName' failed")
    }

    private fun availableAvds(): Set<String> {
        val result = run(listOf("avdmanager", "list", "avd"), "listing Android Virtual Devices failed")
        checkResult(result, "listing Android Virtual Devices failed")
        return result.output.lineSequence()
            .map { it.trim() }
            .mapNotNull { line -> Regex("Name:\\s*(.+)").matchEntire(line)?.groupValues?.get(1)?.trim() }
            .toSet()
    }

    private fun waitForStartedEmulator(process: EmulatorProcess, logPath: Path, avdName: String): String {
        val deadline = deadline()
        while (true) {
            ensureRunning(process, logPath)
            ensureBeforeDeadline(deadline, "timed out waiting for emulator adb readiness for AVD '$avdName'; emulator log: $logPath")
            val matching = startedAvds(avdName, deadline)
            if (matching.size == 1) return waitForBoot(matching.single(), deadline, process, logPath)
            if (matching.size > 1) throw DeliveryException("multiple ready emulators for AVD '$avdName': ${matching.joinToString(", ")}")
            sleepUntilNextPoll(deadline)
        }
    }

    private fun startedAvds(avdName: String, deadline: Long): List<String> = devices(deadline)
        .asSequence()
        .filter { it.state == "device" && it.serial.startsWith("emulator-") }
        .mapNotNull { device ->
            val result = run(listOf("adb", "-s", device.serial, "emu", "avd", "name"), "reading AVD name for ${device.serial}", deadline)
            val name = result.output.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() && it != "OK" }
            device.serial.takeIf { result.exitCode == 0 && name == avdName }
        }
        .toList()

    private fun waitForBoot(serial: String, deadline: Long, process: EmulatorProcess? = null, logPath: Path? = null): String {
        while (true) {
            process?.let { ensureRunning(it, logPath!!) }
            val timeoutMessage = "timed out waiting for Android boot completion on $serial${logPath?.let { "; emulator log: $it" }.orEmpty()}"
            ensureBeforeDeadline(deadline, timeoutMessage)
            val result = run(listOf("adb", "-s", serial, "shell", "getprop", "sys.boot_completed"), "checking Android boot completion on $serial", deadline)
            if (result.exitCode == 0 && result.output.trim() == "1") return serial
            sleepUntilNextPoll(deadline)
        }
    }

    private fun devices(deadline: Long? = null): List<Device> {
        val result = run(listOf("adb", "devices", "-l"), "adb devices failed", deadline)
        checkResult(result, "adb devices failed")
        return result.output.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 2 && it[1] in setOf("device", "offline", "unauthorized", "bootloader", "recovery", "sideload", "rescue", "host", "connecting", "authorizing", "detached", "unknown", "no") }
            .map { Device(it[0], it[1]) }
            .toList()
    }

    private fun run(command: List<String>, action: String, deadline: Long? = null): CommandResult {
        val commandTimeout = deadline?.let {
            Duration.ofMillis((it - monotonicMilliseconds()).coerceAtLeast(1))
        } ?: timeout
        return try {
            commandRunnerFactory(commandTimeout).run(command, projectRoot, environment)
        } catch (error: DeliveryException) {
            throw DeliveryException("$action: ${error.message}")
        } catch (error: IOException) {
            throw DeliveryException("$action: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun checkResult(result: CommandResult, action: String) {
        val output = result.output.trim()
        val failure = output.lowercase()
        if (result.exitCode != 0 || "failure [" in failure || "error:" in failure) {
            throw DeliveryException("$action${if (output.isEmpty()) "" else ": $output"}")
        }
    }

    private fun unreadyMessage(devices: List<Device>): String {
        val grouped = devices.groupBy(Device::state)
        val details = buildList {
            listOf("unauthorized", "offline").forEach { state ->
                grouped[state]?.let { add("$state devices: ${it.joinToString(", ") { device -> device.serial }}") }
            }
            grouped.filterKeys { it !in setOf("unauthorized", "offline") }.forEach { (state, entries) ->
                add("$state devices: ${entries.joinToString(", ") { it.serial }}")
            }
        }
        return "no ready adb device: ${details.joinToString("; ")}. Authorize or reconnect the device, then retry."
    }

    private fun noWindowRequested(): Boolean = environment["AFM_ANDROID_NO_WINDOW"]?.lowercase() !in setOf(null, "", "0", "false", "no")

    private fun deadline(): Long = monotonicMilliseconds() + timeout.toMillis()

    private fun ensureBeforeDeadline(deadline: Long, message: String) {
        if (monotonicMilliseconds() >= deadline) throw DeliveryException(message)
    }

    private fun sleepUntilNextPoll(deadline: Long) {
        val remaining = deadline - monotonicMilliseconds()
        if (remaining <= 0) return
        sleep(minOf(pollInterval.toMillis(), remaining))
    }

    private fun ensureRunning(process: EmulatorProcess, logPath: Path) {
        process.exitCode()?.let { throw DeliveryException("emulator stopped with exit code $it; emulator log: $logPath") }
    }

    private data class Device(val serial: String, val state: String)
}

private object ProcessEmulatorStarter : EmulatorProcessStarter {
    override fun start(command: List<String>, directory: Path, environment: Map<String, String>, logPath: Path): EmulatorProcess {
        Files.createDirectories(logPath.parent)
        val process = ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logPath.toFile())
            .apply {
                this.environment().clear()
                this.environment().putAll(environment)
            }
            .start()
        return object : EmulatorProcess {
            override fun exitCode(): Int? = process.exitValueOrNull()
            override fun stop() {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }
}

private object ProcessAvdCreator : AvdCreator {
    override fun create(command: List<String>, directory: Path, environment: Map<String, String>, timeout: Duration): CommandResult {
        val output = Files.createTempFile("afm-avdmanager-", ".log")
        var running: Process? = null
        try {
            val process = ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .apply {
                    this.environment().clear()
                    this.environment().putAll(environment)
                }
                .start()
            running = process
            process.outputStream.bufferedWriter().use { it.write("no\n") }
            val completed = try {
                process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw DeliveryException("Android Virtual Device creation was interrupted")
            }
            if (!completed) {
                process.destroyForcibly()
                throw DeliveryException("Android Virtual Device creation timed out")
            }
            return CommandResult(process.exitValue(), Files.readString(output))
        } finally {
            running?.takeIf { it.isAlive }?.let { process ->
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
            Files.deleteIfExists(output)
        }
    }
}


private fun Process.exitValueOrNull(): Int? = if (isAlive) null else exitValue()
