package afm.delivery

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

@EnabledIfEnvironmentVariable(named = "AFM_SIGNING_SMOKE_APK", matches = ".+")
class AndroidSignerIntegrationTest {
    @TempDir lateinit var root: Path

    @Test
    fun `signs and verifies a real built APK with a disposable identity`() {
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        val password = UUID.randomUUID().toString()
        val keystore = root.resolve("test.keystore")
        val environment = sanitizedBuildEnvironment(System.getenv()) + ("AFM_TEST_KEY_PASSWORD" to password)
        val runner = SystemCommands(Duration.ofMinutes(3))
        val keytool = Path.of(System.getenv("JAVA_HOME"), "bin/keytool").toString()
        val generated = runner.run(listOf(keytool, "-genkeypair", "-noprompt", "-alias", "afm-test", "-keystore", keystore.toString(), "-storetype", "PKCS12", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=Disposable AFM test", "-storepass:env", "AFM_TEST_KEY_PASSWORD", "-keypass:env", "AFM_TEST_KEY_PASSWORD"), root, environment)
        assertEquals(0, generated.exitCode, "Disposable test key generation failed")
        val signing = environment + mapOf(
            "AFM_KEYSTORE_BASE64" to Base64.getEncoder().encodeToString(Files.readAllBytes(keystore)),
            "AFM_KEYSTORE_PASSWORD" to password,
            "AFM_KEY_ALIAS" to "afm-test",
            "AFM_KEY_PASSWORD" to password,
        )
        val output = root.resolve("signed.apk")
        val metadata = AndroidSigner(runner).sign(Path.of(System.getenv("AFM_SIGNING_SMOKE_APK")), output, 42, "1.0.42", signing)
        assertEquals("com.abysl.afm", metadata.applicationId)
        assertEquals(42, metadata.versionCode)
        assertEquals("1.0.42", metadata.versionName)
        assertTrue(Regex("[0-9a-f]{64}").matches(metadata.certificateSha256))
        assertTrue(Files.size(output) > 0)
    }
}
