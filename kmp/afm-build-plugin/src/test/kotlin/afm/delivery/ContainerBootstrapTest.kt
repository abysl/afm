package afm.delivery

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContainerBootstrapTest {
    private val root = Path.of(System.getProperty("user.dir")).toAbsolutePath().parent.parent

    @Test
    fun `bootstrap stays shell only and refuses a developer host before installing packages`() {
        val setup = Files.readString(root.resolve("ci/setup-linux.sh"))
        assertFalse(setup.contains("python"))
        assertFalse(Files.exists(root.resolve("ci/package-release.py")))
        assertFalse(Files.exists(root.resolve("ci/build-release.sh")))
        val guardPosition = setup.indexOf("/.dockerenv")
        val installPosition = setup.indexOf("apt-get update")
        assertTrue(guardPosition >= 0)
        assertTrue(installPosition > guardPosition)
        assertTrue(setup.contains("printf 'y\\n%.0s' {1..100}"))
        assertTrue(setup.contains("7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee"))
        assertTrue(setup.contains("--default-toolchain 1.98.1"))
        assertTrue(setup.contains("cargo-ndk --version 4.1.2"))
        assertTrue(setup.contains("google-chrome-stable_current_amd64.deb"))
        listOf("ci/setup-linux.sh", "ci/environment.sh").forEach { script ->
            assertEquals(0, SystemCommands().run(listOf("bash", "-n", script), root, System.getenv()).exitCode)
        }
    }

    @Test
    fun `container environment keeps dependency output local and sandbox opt in explicit`() {
        val environment = Files.readString(root.resolve("ci/environment.sh"))
        assertTrue(environment.contains("CARGO_TARGET_DIR=\"\$AFM_ROOT/deps/spirit2/rust/target\""))
        assertTrue(environment.contains("AFM_CI_CONTAINER=1"))
        assertTrue(environment.contains("AFM_CI_CONTAINER=0"))
        assertTrue(environment.contains("CHROME_BIN=\"\$(command -v google-chrome)\""))
        val shared = Files.readString(root.resolve("kmp/app/shared/build.gradle.kts"))
        assertTrue(shared.contains("environmentVariable(\"AFM_CI_CONTAINER\").orNull == \"1\""))
        assertTrue(shared.contains("useChromeHeadlessNoSandbox()"))
        assertTrue(shared.contains("else useChromeHeadless()"))
    }
}
