plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("afmDelivery") {
            id = "afm.delivery"
            implementationClass = "afm.delivery.AfmDeliveryPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    inputs.property("signingSmokeApk", providers.environmentVariable("AFM_SIGNING_SMOKE_APK").orElse(""))
    inputs.files(providers.environmentVariable("AFM_SIGNING_SMOKE_APK").map { listOf(file(it)) }.orElse(emptyList()))
        .withPropertyName("signingSmokeApkContents")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.files("../devenv.nix", "../devenv.lock", "../../devenv.nix", "../../devenv.lock", "../../bevy/devenv.nix", "../../godot/devenv.nix",
        "../../ci/setup-linux.sh", "../../ci/environment.sh", "../app/shared/build.gradle.kts", "../../release-version-base.txt")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<JavaExec>("prepareSpirit2") {
    group = "delivery"
    description = "Initialize only the pinned Spirit2 checkout in a trusted CI workspace"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "afm.delivery.PrepareSpirit2"
    args(layout.projectDirectory.dir("..").asFile.absolutePath)
}
