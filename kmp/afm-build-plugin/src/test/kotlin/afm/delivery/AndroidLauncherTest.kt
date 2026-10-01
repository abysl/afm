package afm.delivery

import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidLauncherTest {
    @Test
    fun selectsTheOnlyReadyPhysicalDevice() {
        val fixture = fixture(listOf(devices("physical-1 device product:phone")))

        assertEquals("physical-1", fixture.launcher.selectDevice())
        assertEquals(listOf(listOf("adb", "devices", "-l")), fixture.commands.calls)
        assertEquals(0, fixture.starter.starts.size)
    }

    @Test
    fun ignoresAdbDaemonStartupDiagnosticsWhenNoDeviceIsConnected() {
        val fixture = fixture(listOf(
            result(output = "* daemon not running; starting now at tcp:5037\n* daemon started successfully\nList of devices attached\n"),
            result(output = "Name: afm\n"),
            devices("emulator-5554 device"),
            result(output = "afm\nOK\n"),
            result(output = "1\n"),
        ))
        assertEquals("emulator-5554", fixture.launcher.selectDevice())
        assertEquals(1, fixture.starter.starts.size)
    }

    @Test
    fun interruptedStartupStopsOnlyItsOwnEmulator() {
        val fixture = fixture(listOf(devices(), result(output = "Name: afm\n"), devices()), sleepFailure = InterruptedException())
        try {
            assertThrows(DeliveryException::class.java) { fixture.launcher.selectDevice() }
            assertTrue(fixture.process.stopped)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun existingEmulatorTimeoutDoesNotStopSomeoneElsesProcess() {
        val fixture = fixture(listOf(devices("emulator-5554 device"), result(output = "0\n"), result(output = "0\n")), timeout = Duration.ofSeconds(2))
        assertThrows(DeliveryException::class.java) { fixture.launcher.selectDevice() }
        assertFalse(fixture.process.stopped)
        assertTrue(fixture.starter.starts.isEmpty())
    }

    @Test
    fun waitsForAnExistingEmulatorToBoot() {
        val fixture = fixture(
            listOf(
                devices("emulator-5554 device"),
                result(output = "0\n"),
                result(output = "1\n"),
            ),
        )

        assertEquals("emulator-5554", fixture.launcher.selectDevice())
        assertEquals(0, fixture.starter.starts.size)
        assertTrue(fixture.diagnostics.single().contains("Reusing existing emulator"))
    }

    @Test
    fun createsMissingAvdWithTheRequestedSystemImage() {
        val fixture = fixture(
            listOf(
                devices(),
                result(output = "Available Android Virtual Devices:\n"),
                devices("emulator-5554 device"),
                result(output = "afm-ci\nOK\n"),
                result(output = "1\n"),
            ),
            environment = mapOf(
                "AFM_ANDROID_AVD" to "afm-ci",
                "AFM_ANDROID_SYSTEM_IMAGE" to "system-images;android-36;google_apis;x86_64",
            ),
        )

        assertEquals("emulator-5554", fixture.launcher.selectDevice())
        assertEquals(
            listOf("avdmanager", "create", "avd", "--name", "afm-ci", "--package", "system-images;android-36;google_apis;x86_64", "--device", "pixel"),
            fixture.avdCreator.calls.single(),
        )
    }

    @Test
    fun explicitSerialWinsAndRejectsMissingOrOfflineSerial() {
        val selected = fixture(listOf(devices("one device", "two device")), environment = mapOf("ANDROID_SERIAL" to "two"))
        val missing = fixture(listOf(devices("one device")), environment = mapOf("ANDROID_SERIAL" to "two"))
        val offline = fixture(listOf(devices("one device", "two offline")), environment = mapOf("ANDROID_SERIAL" to "two"))

        assertEquals("two", selected.launcher.selectDevice())
        assertTrue(assertThrows(DeliveryException::class.java) { missing.launcher.selectDevice() }.message!!.contains("was not found"))
        assertTrue(assertThrows(DeliveryException::class.java) { offline.launcher.selectDevice() }.message!!.contains("offline devices: two"))
    }

    @Test
    fun rejectsAmbiguousAndUnreadyDevicesWithoutStartingAnEmulator() {
        val ambiguous = fixture(listOf(devices("one device", "two device")))
        val unready = fixture(listOf(devices("one unauthorized", "two offline")))

        assertTrue(assertThrows(DeliveryException::class.java) { ambiguous.launcher.selectDevice() }.message!!.contains("multiple ready devices: one, two"))
        assertTrue(assertThrows(DeliveryException::class.java) { unready.launcher.selectDevice() }.message!!.contains("unauthorized devices: one; offline devices: two"))
        assertEquals(0, ambiguous.starter.starts.size)
        assertEquals(0, unready.starter.starts.size)
    }

    @Test
    fun timesOutWaitingForAdbReadinessWithinTheBootDeadline() {
        val fixture = fixture(
            listOf(
                devices(),
                result(output = "Name: afm\n"),
                devices(),
                devices(),
            ),
            timeout = Duration.ofSeconds(2),
        )

        val error = assertThrows(DeliveryException::class.java) { fixture.launcher.selectDevice() }

        assertTrue(error.message!!.contains("timed out waiting for emulator adb readiness"))
        assertTrue(fixture.process.stopped)
        assertEquals(listOf(2000L, 2000L, 2000L, 1000L), fixture.commands.timeouts.map(Duration::toMillis))
    }

    @Test
    fun reportsEmulatorProcessDeathAndUnavailableTools() {
        val dead = fixture(
            listOf(devices(), result(output = "Name: afm\n")),
            process = FakeProcess(1),
        )
        val unavailableEmulator = fixture(
            listOf(devices(), result(output = "Name: afm\n")),
            startFailure = IOException("emulator"),
        )
        val unavailableAdb = fixture(listOf(IOException("adb")))

        assertTrue(assertThrows(DeliveryException::class.java) { dead.launcher.selectDevice() }.message!!.contains("emulator stopped with exit code 1"))
        assertTrue(assertThrows(DeliveryException::class.java) { unavailableEmulator.launcher.selectDevice() }.message!!.contains("starting emulator"))
        assertTrue(assertThrows(DeliveryException::class.java) { unavailableAdb.launcher.selectDevice() }.message!!.contains("adb devices failed: adb"))
    }

    @Test
    fun selectsOnlyTheAvdItStarted() {
        val fixture = fixture(
            listOf(
                devices(),
                result(output = "Name: afm\n"),
                devices("emulator-5554 device", "emulator-5556 device"),
                result(output = "other\nOK\n"),
                result(output = "afm\nOK\n"),
                result(output = "1\n"),
            ),
        )

        assertEquals("emulator-5556", fixture.launcher.selectDevice())
        assertEquals(listOf("adb", "-s", "emulator-5556", "emu", "avd", "name"), fixture.commands.calls[4])
    }

    @Test
    fun appliesHeadlessOnlyToNewEmulatorAndRemovesOnlyItsLibraryPath() {
        val environment = mapOf("AFM_ANDROID_NO_WINDOW" to "1", "LD_LIBRARY_PATH" to "/ndk/lib", "DISPLAY" to ":9")
        val fixture = fixture(
            listOf(
                devices(),
                result(output = "Name: afm\n"),
                devices("emulator-5554 device"),
                result(output = "afm\nOK\n"),
                result(output = "1\n"),
            ),
            environment = environment,
        )

        fixture.launcher.selectDevice()

        assertEquals(listOf("emulator", "-avd", "afm", "-no-window"), fixture.starter.starts.single().command)
        assertFalse("LD_LIBRARY_PATH" in fixture.starter.starts.single().environment)
        assertEquals(":9", fixture.starter.starts.single().environment["DISPLAY"])
        assertEquals("/ndk/lib", fixture.commands.environments.first()["LD_LIBRARY_PATH"])
    }

    @Test
    fun installAndLaunchAreTargetedAndRecognizeExitAndTextualFailures() {
        val success = fixture(listOf(result(), result(output = "Starting: Intent { cmp=com.abysl.afm/.MainActivity }")))

        success.launcher.install("serial", Path.of("/project/app.apk"))
        success.launcher.launch("serial")

        assertEquals(listOf("adb", "-s", "serial", "install", "-r", "/project/app.apk"), success.commands.calls[0])
        assertEquals(listOf("adb", "-s", "serial", "shell", "am", "start", "-W", "-n", "com.abysl.afm/.MainActivity"), success.commands.calls[1])
        val installFailure = fixture(listOf(result(exitCode = 1, output = "INSTALL_FAILED_UPDATE_INCOMPATIBLE")))
        val launchFailure = fixture(listOf(result(output = "Error type 3\nError: Activity class does not exist.")))
        assertTrue(assertThrows(DeliveryException::class.java) { installFailure.launcher.install("serial", Path.of("app.apk")) }.message!!.contains("INSTALL_FAILED"))
        assertTrue(assertThrows(DeliveryException::class.java) { launchFailure.launcher.launch("serial") }.message!!.contains("Error type 3"))
    }

    @Test
    fun cleansUpOnlyTheEmulatorItStartedAfterStartupFailure() {
        val failed = fixture(
            listOf(devices(), result(output = "Name: afm\n"), devices(), devices()),
            timeout = Duration.ofSeconds(2),
        )
        val successful = fixture(
            listOf(devices(), result(output = "Name: afm\n"), devices("emulator-5554 device"), result(output = "afm\nOK\n"), result(output = "1\n")),
        )

        assertThrows(DeliveryException::class.java) { failed.launcher.selectDevice() }
        assertTrue(failed.process.stopped)
        assertEquals("emulator-5554", successful.launcher.selectDevice())
        assertFalse(successful.process.stopped)
    }

    private fun fixture(
        results: List<Any>,
        environment: Map<String, String> = emptyMap(),
        process: FakeProcess = FakeProcess(),
        timeout: Duration = Duration.ofSeconds(4),
        startFailure: Exception? = null,
        sleepFailure: InterruptedException? = null,
    ): Fixture {
        val commands = FakeCommands(results)
        val clock = FakeClock()
        val starter = FakeStarter(process, startFailure)
        val avdCreator = FakeAvdCreator()
        val diagnostics = mutableListOf<String>()
        return Fixture(
            AndroidLauncher(
                projectRoot = Path.of("/project"),
                environment = environment,
                commandRunnerFactory = { duration -> commands.also { commands.timeouts += duration } },
                emulatorProcessStarter = starter,
                avdCreator = avdCreator,
                monotonicMilliseconds = clock::now,
                sleep = { duration -> if (sleepFailure != null) throw sleepFailure else clock.sleep(duration) },
                diagnostic = diagnostics::add,
                timeout = timeout,
                pollInterval = Duration.ofSeconds(1),
            ),
            commands,
            starter,
            avdCreator,
            process,
            diagnostics,
        )
    }

    private data class Fixture(
        val launcher: AndroidLauncher,
        val commands: FakeCommands,
        val starter: FakeStarter,
        val avdCreator: FakeAvdCreator,
        val process: FakeProcess,
        val diagnostics: List<String>,
    )

    private class FakeClock {
        private var value = 0L
        fun now(): Long = value
        fun sleep(milliseconds: Long) {
            value += milliseconds
        }
    }

    private class FakeCommands(results: List<Any>) : CommandRunner {
        private val results = results.toMutableList()
        val calls = mutableListOf<List<String>>()
        val timeouts = mutableListOf<Duration>()
        val environments = mutableListOf<Map<String, String>>()

        override fun run(command: List<String>, directory: Path, environment: Map<String, String>): CommandResult {
            calls += command
            environments += environment
            if (results.isEmpty()) error("unexpected command: $command")
            return when (val next = results.removeAt(0)) {
                is CommandResult -> next
                is Exception -> throw next
                else -> error("invalid fake result: $next")
            }
        }
    }

    private class FakeProcess(private var code: Int? = null) : EmulatorProcess {
        var stopped = false
        override fun exitCode(): Int? = code
        override fun stop() {
            stopped = true
            code = 143
        }
    }

    private class FakeStarter(private val process: FakeProcess, private val failure: Exception?) : EmulatorProcessStarter {
        data class Start(val command: List<String>, val environment: Map<String, String>)
        val starts = mutableListOf<Start>()

        override fun start(command: List<String>, directory: Path, environment: Map<String, String>, logPath: Path): EmulatorProcess {
            failure?.let { throw it }
            starts += Start(command, environment)
            return process
        }
    }

    private class FakeAvdCreator : AvdCreator {
        val calls = mutableListOf<List<String>>()
        override fun create(command: List<String>, directory: Path, environment: Map<String, String>, timeout: Duration): CommandResult {
            calls += command
            return result()
        }
    }

    private companion object {
        fun result(exitCode: Int = 0, output: String = "") = CommandResult(exitCode, output)
        fun devices(vararg entries: String) = result(output = "List of devices attached\n${entries.joinToString("\n")}\n")
    }
}
