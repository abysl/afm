# Abysl File Manager

Kotlin Multiplatform client for Abysl File Manager, consuming the shared SDK from `../../spirit2/kmp`. The Rust `spirit-ffi` crate generates the low-level UniFFI bindings; Spirit2 owns the Kotlin SDK.

## Gradle workspace

| Project | Role |
|---|---|
| `:app:shared` | Compose Multiplatform UI and platform-neutral file-manager logic |
| `:app:androidApp` | Android application |
| `:app:desktopApp` | Desktop JVM application |
| `:app:webApp` | JavaScript and Wasm web application |

`app:shared` depends on the composite `../../spirit2/kmp` SDK. The SDK wraps generated JNA bindings with `suspend` store methods, a `BlobHash` value class, and `AutoCloseable` lifecycle management.

## Building

Run commands from this directory:

```text
direnv allow
generate-bindings
jvm-native
./gradlew :app:shared:jvmTest
./gradlew :app:desktopApp:run
./gradlew :app:androidApp:assembleDebug
```

The root `devenv.nix` supplies the Android SDK and NDK, Rust targets, JDK 25, Node, Yarn, Binaryen, and the Linux runtime libraries needed by Compose Desktop. It provides `jvm-test`, `unit-test`, `desktop`, `apk`, `install`, and `assemble` convenience commands.

## IntelliJ IDEA

Start IntelliJ from the project devenv so Gradle-run desktop applications inherit the Nix OpenGL runtime libraries:

```text
devenv shell -- idea .
```

Quit existing IntelliJ processes before using this command. The project-local IntelliJ settings use the Gradle wrapper and the devenv-provided Gradle JVM.

`generate-bindings` and the native build commands compile the sibling Rust workspace and write generated bindings or Android JNI libraries into ignored SDK directories. The JVM SDK package embeds the release native library as a JNA classpath resource; Android consumes the JNI library and JNA AAR.
