package afm.delivery

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.kotlin.dsl.*

class AfmDeliveryPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        val kmp = layout.projectDirectory.asFile.toPath()
        val spirit = kmp.parent.resolve("deps/spirit2")
        val stage = layout.buildDirectory.dir("ci").get().asFile.toPath()
        val release = providers.gradleProperty("afmRelease").orNull == "true"
        val androidMinSdk = extensions.getByType<VersionCatalogsExtension>().named("libs")
            .findVersion("android-minSdk").orElseThrow { DeliveryException("android-minSdk is required") }.requiredVersion
        if (androidMinSdk.toIntOrNull() == null) throw DeliveryException("android-minSdk must be a numeric API level")
        val version = if (release) {
            val count = providers.exec {
                workingDir(kmp.parent.toFile())
                commandLine("git", "rev-list", "--count", "HEAD")
            }.standardOutput.asText.get().trim()
            ReleaseVersions.release(kmp.parent, count)
        } else {
            ReleaseVersions.development(providers.environmentVariable("AFM_VERSION_CODE").orNull, providers.environmentVariable("AFM_VERSION_NAME").orNull)
        }
        extra["afmVersionCode"] = version.code
        extra["afmVersionName"] = version.name

        fun deliveryContext(): SourceContext {
            if (!release) throw DeliveryException("Delivery tasks require -PafmRelease=true")
            requireMainDelivery(System.getenv())
            return GitSources(kmp).context().also {
                if (it.version != version) throw DeliveryException("Source version changed since Gradle configuration")
            }
        }

        fun command(name: String, directory: Path, arguments: () -> List<String>) = tasks.register(name) {
            group = "delivery"
            notCompatibleWithConfigurationCache("Runs a native tool using the execution environment")
            doLast {
                val environment = sanitizedBuildEnvironment(System.getenv()) + ("CARGO_TARGET_DIR" to spirit.resolve("rust/target").toString())
                val result = SystemCommands().run(arguments(), directory, environment)
                val log = stage.resolve("logs/$name.log")
                Files.createDirectories(log.parent)
                Files.writeString(log, result.output)
                logger.lifecycle(result.output)
                if (result.exitCode != 0) throw DeliveryException("$name failed; see $log")
            }
        }

        val host = command("afmHostNative", spirit.resolve("rust")) {
            listOf("cargo", "build", "--locked", "--release", "-p", "spirit-ffi", "-p", "spirit-cli", "--features", "uniffi/cli")
        }
        val bindings = command("afmGenerateBindings", spirit.resolve("rust")) {
            val library = if (System.getProperty("os.name").startsWith("Mac")) "libspirit_ffi.dylib" else "libspirit_ffi.so"
            listOf(spirit.resolve("rust/target/release/uniffi-bindgen").toString(), "generate", "--library", spirit.resolve("rust/target/release/$library").toString(), "--language", "kotlin", "--out-dir", spirit.resolve("kmp/sdk/src/commonMain/kotlin").toString(), "--no-format")
        }
        bindings.configure { dependsOn(host) }
        val android = command("afmAndroidNative", spirit.resolve("rust")) {
            listOf("cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "-P", androidMinSdk, "-o", spirit.resolve("kmp/sdk/src/androidMain/jniLibs").toString(), "build", "--locked", "--release", "-p", "spirit-ffi")
        }
        android.configure {
            dependsOn(bindings)
            doFirst {
                if (System.getenv("ANDROID_NDK_HOME").isNullOrBlank() && System.getenv("ANDROID_NDK_ROOT").isNullOrBlank()) {
                    throw DeliveryException("The Android NDK environment is required")
                }
            }
        }
        tasks.register("afmPrepareNative") {
            group = "delivery"
            description = "Bootstrap Rust and UniFFI before configuring the consuming composite KMP build"
            dependsOn(bindings, android)
            notCompatibleWithConfigurationCache("Checks source provenance at execution")
            doLast { if (release) deliveryContext().write(stage.resolve("native-context.json")) }
        }

        val rustTests = command("afmRustTest", spirit.resolve("rust")) {
            listOf("cargo", "test", "--workspace", "--all-features", "--locked")
        }
        fun androidTask(name: String, launch: Boolean) = tasks.register(name) {
            group = "application"
            notCompatibleWithConfigurationCache("Selects a live Android device at execution time")
            doLast {
                val apk = providers.gradleProperty("afmApk").orNull?.let { kmp.resolve(it).normalize() }
                    ?: kmp.resolve("app/androidApp/build/outputs/apk/debug/androidApp-debug.apk")
                if (!Files.isRegularFile(apk)) throw DeliveryException("APK not found: $apk; run build-android first")
                val launcher = AndroidLauncher(kmp)
                val serial = launcher.selectDevice()
                launcher.install(serial, apk)
                logger.lifecycle("Installed $apk on $serial")
                if (launch) {
                    launcher.launch(serial)
                    logger.lifecycle("Started com.abysl.afm/.MainActivity on $serial")
                }
            }
        }
        androidTask("afmRunAndroid", true)
        androidTask("afmInstallAndroid", false)
        tasks.register("afmSelectAndroid") {
            group = "application"
            description = "Select or boot a device and write its serial for the next instrumentation invocation"
            notCompatibleWithConfigurationCache("Selects a live Android device at execution time")
            doLast {
                val target = providers.gradleProperty("afmDeviceOutput").orNull
                    ?: throw DeliveryException("afmDeviceOutput must name an empty temporary file")
                val output = kmp.resolve(target).normalize()
                if (Files.isSymbolicLink(output) || !Files.isRegularFile(output) || Files.size(output) != 0L) {
                    throw DeliveryException("afmDeviceOutput must be an empty regular file")
                }
                val serial = AndroidLauncher(kmp).selectDevice()
                Files.writeString(output, serial + "\n")
                logger.lifecycle("Selected Android device $serial")
            }
        }
        val verifyNative = tasks.register("afmVerifyPreparedNative") {
            group = "delivery"
            notCompatibleWithConfigurationCache("Checks native build provenance")
            doLast {
                if (SourceContext.read(stage.resolve("native-context.json")) != deliveryContext()) {
                    throw DeliveryException("Run afmPrepareNative for this exact source revision before afmCiBuild")
                }
            }
        }
        val buildLogicTests = gradle.includedBuild("afm-build-logic").task(":test")
        val sdkTests = gradle.includedBuild("spirit2").task(":sdk:jvmTest")
        val tests = listOf(verifyNative, rustTests, buildLogicTests, sdkTests, ":app:shared:jvmTest", ":app:shared:jsBrowserTest", ":app:shared:wasmJsBrowserTest")
        val reportSources = listOf(
            stage.resolve("logs") to "native-logs",
            kmp.resolve("app/shared/build/test-results") to "afm/test-results",
            kmp.resolve("app/shared/build/reports/tests") to "afm/reports",
            spirit.resolve("kmp/sdk/build/test-results") to "sdk/test-results",
            spirit.resolve("kmp/sdk/build/reports/tests") to "sdk/reports",
            kmp.resolve("build-logic/build/test-results/test") to "delivery/test-results",
        )
        val reports = tasks.register<Tar>("afmTestReports") {
            group = "delivery"
            dependsOn(tests)
            destinationDirectory.set(stage.resolve("reports").toFile())
            archiveFileName.set("test-reports.tar.gz")
            compression = Compression.GZIP
            reportSources.forEach { (source, destination) -> from(source) { into(destination) } }
            doFirst {
                reportSources.forEach { (source, _) ->
                    if (!Files.isDirectory(source)) throw DeliveryException("Missing test evidence: $source")
                }
            }
        }
        tasks.register("afmCiBuild") {
            group = "delivery"
            description = "Run tests and build unsigned Android and Linux artifacts without signing credentials"
            dependsOn(reports, ":app:androidApp:assembleRelease", ":app:androidApp:bundleRelease", ":app:desktopApp:packageDeb")
            notCompatibleWithConfigurationCache("Records verified source provenance")
            doLast {
                val context = deliveryContext()
                if (SourceContext.read(stage.resolve("native-context.json")) != context) throw DeliveryException("Native source context does not match")
                unsignedApk(kmp)
                linuxPackage(kmp, context.version)
                context.write(stage.resolve("context.json"))
            }
        }
        tasks.register("afmDeliveryPreflight") {
            group = "delivery"
            notCompatibleWithConfigurationCache("Reads protected credentials only during execution")
            doLast {
                deliveryContext()
                requireSigningCredentials(System.getenv())
                publisher()
            }
        }
        tasks.register("afmDeliver") {
            group = "delivery"
            description = "Sign and publish a verified bundle, or restore the existing immutable bundle"
            notCompatibleWithConfigurationCache("Signing and publication must never cache credentials or side effects")
            doLast {
                val context = deliveryContext()
                requireSigningCredentials(System.getenv())
                val publisher = publisher()
                val bundle = stage.resolve("bundle")
                if (!publisher.restore(bundle, context.source.afmCommit)) {
                    if (SourceContext.read(stage.resolve("context.json")) != context) throw DeliveryException("Build context does not match this checkout")
                    if (!Files.exists(bundle)) assembleBundle(kmp, stage, context)
                }
                val manifest = ReleaseManifest.read(bundle.resolve("delivery.json"))
                if (manifest.source != context.source || manifest.version != context.version) {
                    throw DeliveryException("Restored artifact provenance does not match the current source context")
                }
                logger.lifecycle(publisher.publish(bundle, context.source.afmCommit))
            }
        }
    }
}

private val signingKeys = setOf("AFM_KEYSTORE_BASE64", "AFM_KEYSTORE_PASSWORD", "AFM_KEY_ALIAS", "AFM_KEY_PASSWORD")

fun sanitizedBuildEnvironment(environment: Map<String, String>): Map<String, String> {
    val clean = environment.filterKeys { it !in signingKeys && it != "AFM_FORGEJO_TOKEN" }
    return if (clean["ANDROID_NDK_HOME"].isNullOrBlank() && !clean["ANDROID_NDK_ROOT"].isNullOrBlank()) {
        clean + ("ANDROID_NDK_HOME" to clean.getValue("ANDROID_NDK_ROOT"))
    } else clean
}

private fun requireSigningCredentials(environment: Map<String, String>) {
    if (signingKeys.any { environment[it].isNullOrBlank() }) throw DeliveryException("Configure all four AFM signing secrets; there is no debug-signing fallback")
}

private fun publisher(): ForgejoPublisher {
    fun required(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: throw DeliveryException("$name is required")
    val cache = System.getenv("XDG_CACHE_HOME")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".cache")
    val lock = System.getenv("AFM_PUBLISH_LOCK")?.let(Path::of) ?: cache.resolve("afm-delivery/publish.lock")
    return ForgejoPublisher(URI(required("AFM_PACKAGE_BASE")), URI(required("AFM_REPOSITORY_API")), required("AFM_FORGEJO_TOKEN"), lock)
}

private fun oneFile(directory: Path, glob: String, label: String): Path {
    if (!Files.isDirectory(directory)) throw DeliveryException("Missing $label output directory")
    val matches = Files.newDirectoryStream(directory, glob).use { it.toList() }
    if (matches.size != 1 || Files.isSymbolicLink(matches.single()) || !Files.isRegularFile(matches.single())) {
        throw DeliveryException("Expected exactly one regular $label")
    }
    return matches.single()
}

private fun unsignedApk(kmp: Path) = oneFile(kmp.resolve("app/androidApp/build/outputs/apk/release"), "*-unsigned.apk", "unsigned APK")
private fun linuxPackage(kmp: Path, version: ReleaseVersion) = oneFile(kmp.resolve("app/desktopApp/build/compose/binaries/main/deb"), "*_${version.name}_amd64.deb", "Linux package")

private fun assembleBundle(kmp: Path, stage: Path, context: SourceContext) {
    val bundle = stage.resolve("bundle")
    if (Files.exists(bundle)) throw DeliveryException("An unfinished local bundle exists; use a fresh CI workspace")
    val output = Files.createTempDirectory(stage, "bundle-")
    try {
        val version = context.version
        val apk = output.resolve("afm-${version.name}-android.apk")
        val signing = AndroidSigner().sign(unsignedApk(kmp), apk, version.code, version.name, System.getenv())
        val linux = output.resolve("afm-${version.name}-linux-x86_64.deb")
        Files.copy(linuxPackage(kmp, version), linux)
        val reports = output.resolve("test-reports.tar.gz")
        Files.copy(stage.resolve("reports/test-reports.tar.gz"), reports)
        val artifacts = mutableListOf(
            ReleaseManifest.artifact(apk, "application/vnd.android.package-archive"),
            ReleaseManifest.artifact(linux, "application/vnd.debian.binary-package"),
            ReleaseManifest.artifact(reports, "application/gzip"),
        )
        val checksums = output.resolve("checksums.txt")
        Files.writeString(checksums, artifacts.joinToString("") { "${it.sha256}  ${it.name}\n" })
        artifacts += ReleaseManifest.artifact(checksums, "text/plain")
        ReleaseManifest(context.source, version, AndroidRelease(signing.applicationId, signing.certificateSha256), artifacts).write(output.resolve("delivery.json"))
        Files.move(output, bundle)
    } finally {
        if (Files.exists(output)) output.toFile().deleteRecursively()
    }
}
