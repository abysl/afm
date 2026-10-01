package afm.delivery

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.Base64
import java.util.zip.ZipFile

private const val applicationId = "com.abysl.afm"
private const val buildToolsVersion = "36.0.0"
private const val maximumVersionCode = 2_100_000_000
private val numericVersion = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")
private val certificateDigest = Regex("certificate SHA-256 digest:\\s*([0-9A-Fa-f:]+)")
private val packageMetadata = Regex("package:\\s+name='([^']+)'\\s+versionCode='([^']+)'\\s+versionName='([^']+)'")
private val requiredNativeEntries = setOf(
    "lib/arm64-v8a/libspirit_ffi.so",
    "lib/x86_64/libspirit_ffi.so",
)
private val ownerDirectoryPermissions = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
)
private val ownerFilePermissions = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
)

data class SigningMetadata(
    val applicationId: String,
    val versionCode: Int,
    val versionName: String,
    val certificateSha256: String,
)

class AndroidSigner(private val commands: CommandRunner = SystemCommands(Duration.ofMinutes(3))) {
    fun sign(
        input: Path,
        output: Path,
        versionCode: Int,
        versionName: String,
        environment: Map<String, String>,
    ): SigningMetadata {
        validateVersion(versionCode, versionName)
        val keystore = decodeKeystore(environment)
        val source = input.toAbsolutePath().normalize()
        val target = output.toAbsolutePath().normalize()
        try {
            requireInput(source)
            requireOutput(source, target)
            val tools = findTools(environment)
            val toolEnvironment = toolEnvironment(environment)
            val temporaryDirectory = createPrivateDirectory()
            try {
                val keystorePath = temporaryDirectory.resolve("release.keystore")
                writePrivateKeystore(keystorePath, keystore)
                val signedApk = temporaryDirectory.resolve("signed.apk")
                runTool(
                    listOf(
                        tools.apksigner.toString(),
                        "sign",
                        "--ks",
                        keystorePath.toString(),
                        "--ks-key-alias",
                        requireSecret(environment, "AFM_KEY_ALIAS"),
                        "--ks-pass",
                        "env:AFM_KEYSTORE_PASSWORD",
                        "--key-pass",
                        "env:AFM_KEY_PASSWORD",
                        "--out",
                        signedApk.toString(),
                        source.toString(),
                    ),
                    temporaryDirectory,
                    toolEnvironment,
                )
                requireRegularFile(signedApk, "Android signing tool failed")
                val metadata = verify(signedApk, versionCode, versionName, environment)
                publish(signedApk, target)
                return metadata
            } finally {
                deleteTree(temporaryDirectory)
            }
        } finally {
            keystore.fill(0)
        }
    }

    fun verify(apk: Path, versionCode: Int, versionName: String, environment: Map<String, String>): SigningMetadata {
        validateVersion(versionCode, versionName)
        val source = apk.toAbsolutePath().normalize()
        requireRegularFile(source, "signed APK is unavailable")
        val tools = findTools(environment)
        val safeEnvironment = sanitizedBuildEnvironment(environment)
        val digest = verifyCertificate(source, tools, source.parent, safeEnvironment)
        verifyMetadata(source, tools, source.parent, safeEnvironment, versionCode, versionName)
        verifyNativeLibraries(source)
        return SigningMetadata(applicationId, versionCode, versionName, digest)
    }

    private fun verifyCertificate(
        signedApk: Path,
        tools: AndroidTools,
        directory: Path,
        environment: Map<String, String>,
    ): String {
        val output = runTool(
            listOf(tools.apksigner.toString(), "verify", "--verbose", "--print-certs", signedApk.toString()),
            directory,
            environment,
        )
        val match = certificateDigest.find(output) ?: throw DeliveryException("APK signer certificate digest is unavailable")
        val digest = match.groupValues[1].replace(":", "").lowercase()
        if (!digest.matches(Regex("[0-9a-f]{64}"))) {
            throw DeliveryException("APK signer certificate digest is invalid")
        }
        return digest
    }

    private fun verifyMetadata(
        signedApk: Path,
        tools: AndroidTools,
        directory: Path,
        environment: Map<String, String>,
        versionCode: Int,
        versionName: String,
    ) {
        val output = runTool(
            listOf(tools.aapt.toString(), "dump", "badging", signedApk.toString()),
            directory,
            environment,
        )
        val match = packageMetadata.find(output) ?: throw DeliveryException("APK metadata is invalid")
        val (_, foundApplicationId, foundVersionCode, foundVersionName) = match.groupValues
        if (
            foundApplicationId != applicationId ||
            foundVersionCode != versionCode.toString() ||
            foundVersionName != versionName
        ) {
            throw DeliveryException("signed APK has unexpected version metadata")
        }
    }

    private fun verifyNativeLibraries(signedApk: Path) {
        val entries = try {
            ZipFile(signedApk.toFile()).use { archive -> archive.entries().asSequence().map { it.name }.toSet() }
        } catch (_: IOException) {
            throw DeliveryException("signed APK is invalid")
        }
        if (!entries.containsAll(requiredNativeEntries)) {
            throw DeliveryException("signed APK is missing required native libraries")
        }
    }

    private fun runTool(command: List<String>, directory: Path, environment: Map<String, String>): String {
        val result = try {
            commands.run(command, directory, environment)
        } catch (_: Exception) {
            throw DeliveryException("Android signing tool failed")
        }
        if (result.exitCode != 0) throw DeliveryException("Android signing tool failed")
        return result.output
    }
}

private data class AndroidTools(val apksigner: Path, val aapt: Path)

private fun validateVersion(versionCode: Int, versionName: String) {
    if (versionCode !in 1..maximumVersionCode) {
        throw DeliveryException("version code must be between 1 and $maximumVersionCode")
    }
    if (!numericVersion.matches(versionName)) {
        throw DeliveryException("version name must be a numeric semantic version")
    }
}

private fun decodeKeystore(environment: Map<String, String>): ByteArray {
    signingSecretNames.forEach { requireSecret(environment, it) }
    val encoded = requireSecret(environment, "AFM_KEYSTORE_BASE64")
    if (encoded.length % 4 != 0) throw DeliveryException("invalid keystore data")
    val keystore = try {
        Base64.getDecoder().decode(encoded)
    } catch (_: IllegalArgumentException) {
        throw DeliveryException("invalid keystore data")
    }
    if (keystore.isEmpty() || Base64.getEncoder().encodeToString(keystore) != encoded) {
        throw DeliveryException("invalid keystore data")
    }
    return keystore
}

private fun requireSecret(environment: Map<String, String>, name: String): String =
    environment[name]?.takeIf { it.isNotBlank() } ?: throw DeliveryException("missing required signing secrets")

private fun requireInput(input: Path) {
    requireRegularFile(input, "unsigned APK is unavailable")
}

private fun requireOutput(input: Path, output: Path) {
    val parent = output.parent ?: throw DeliveryException("output directory is unavailable")
    if (!Files.isDirectory(parent, NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
        throw DeliveryException("output directory is unavailable")
    }
    if (Files.exists(output, NOFOLLOW_LINKS)) {
        requireRegularFile(output, "output APK is unavailable")
        if (Files.isSameFile(input, output)) throw DeliveryException("input and output APK paths must differ")
    }
    if (input.toAbsolutePath().normalize() == output.toAbsolutePath().normalize()) {
        throw DeliveryException("input and output APK paths must differ")
    }
}

private fun requireRegularFile(path: Path, message: String) {
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
        throw DeliveryException(message)
    }
}

private fun findTools(environment: Map<String, String>): AndroidTools {
    val directory = (
        environment["AFM_ANDROID_BUILD_TOOLS"]?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: environment["ANDROID_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it, "build-tools", buildToolsVersion) }
            ?: throw DeliveryException("ANDROID_HOME or AFM_ANDROID_BUILD_TOOLS is required")
        ).toAbsolutePath().normalize()
    val apksigner = directory.resolve("apksigner")
    val aapt = listOf(directory.resolve("aapt2"), directory.resolve("aapt")).firstOrNull(::isExecutable)
        ?: throw DeliveryException("Android build tools are unavailable")
    if (!isExecutable(apksigner)) throw DeliveryException("Android build tools are unavailable")
    return AndroidTools(apksigner, aapt)
}

private fun isExecutable(path: Path): Boolean =
    Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && Files.isExecutable(path)

private fun toolEnvironment(environment: Map<String, String>): Map<String, String> = buildMap {
    putAll(sanitizedBuildEnvironment(environment))
    put("AFM_KEYSTORE_PASSWORD", requireSecret(environment, "AFM_KEYSTORE_PASSWORD"))
    put("AFM_KEY_PASSWORD", requireSecret(environment, "AFM_KEY_PASSWORD"))
}

private fun createPrivateDirectory(): Path {
    val directory = try {
        Files.createTempDirectory("afm-sign-", PosixFilePermissions.asFileAttribute(ownerDirectoryPermissions))
    } catch (_: Exception) {
        throw DeliveryException("could not create signing workspace")
    }
    try {
        Files.setPosixFilePermissions(directory, ownerDirectoryPermissions)
        if (Files.getPosixFilePermissions(directory) != ownerDirectoryPermissions) {
            throw DeliveryException("could not secure signing workspace")
        }
        return directory
    } catch (error: Exception) {
        deleteTree(directory)
        if (error is DeliveryException) throw error
        throw DeliveryException("could not secure signing workspace")
    }
}

private fun writePrivateKeystore(path: Path, contents: ByteArray) {
    try {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(ownerFilePermissions))
        Files.write(path, contents)
        Files.setPosixFilePermissions(path, ownerFilePermissions)
        if (Files.getPosixFilePermissions(path) != ownerFilePermissions) {
            throw DeliveryException("could not secure signing keystore")
        }
    } catch (error: Exception) {
        Files.deleteIfExists(path)
        if (error is DeliveryException) throw error
        throw DeliveryException("could not write signing keystore")
    }
}

private fun publish(source: Path, target: Path) {
    val parent = target.parent ?: throw DeliveryException("output directory is unavailable")
    val staged = try {
        Files.createTempFile(parent, ".${target.fileName}.", ".apk")
    } catch (_: IOException) {
        throw DeliveryException("could not publish signed APK")
    }
    try {
        Files.copy(source, staged, REPLACE_EXISTING)
        try {
            Files.move(staged, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staged, target, REPLACE_EXISTING)
        }
        requireRegularFile(target, "could not publish signed APK")
    } catch (error: Exception) {
        if (error is DeliveryException) throw error
        throw DeliveryException("could not publish signed APK")
    } finally {
        Files.deleteIfExists(staged)
    }
}

private fun deleteTree(directory: Path) {
    try {
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        })
    } catch (_: IOException) {
        throw DeliveryException("could not clean signing workspace")
    }
}
