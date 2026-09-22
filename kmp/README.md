# Abysl File Manager

Kotlin Multiplatform client for Abysl File Manager, consuming the shared SDK from `../deps/spirit2/kmp`. The Rust `spirit-ffi` crate generates the low-level UniFFI bindings; Spirit2 owns the Kotlin SDK.

## Gradle workspace

| Project | Role |
|---|---|
| `:app:shared` | Compose Multiplatform UI and platform-neutral file-manager logic |
| `:app:androidApp` | Android application |
| `:app:desktopApp` | Desktop JVM application |
| `:app:webApp` | JavaScript and Wasm web application |

`app:shared` depends on the composite `../deps/spirit2/kmp` SDK. The SDK wraps generated JNA bindings with `suspend` store methods, a `BlobHash` value class, and `AutoCloseable` lifecycle management.

## Development commands

Run commands from this directory with `devenv shell -- <command>`, or use
`direnv allow` once and call the commands directly. The pinned Spirit2 dependency
must exist at `../deps/spirit2` at the revision selected by the parent repository;
these commands never clone, reset, or update it automatically.

The locked environment supplies Rust, JDK 25, Android SDK 36, NDK
26.3.11579264, the emulator and its system image, Node, Yarn, Binaryen,
Chromium on Linux, and Compose Desktop runtime libraries. The first shell/build
can take several minutes while dependencies are downloaded and compiled.

### Run, test, release matrix

| Surface | Run | Test | Local release artifact |
| --- | --- | --- | --- |
| Linux desktop | `run-desktop` | `test-desktop` | `release-desktop`: Debian installer with Java and native Spirit2 bundled |
| Spirit CLI | `run-cli [arguments...]` | `test-cli` | `release-cli`: optimized native executable in a `.tar.gz` archive |
| Android | `run-android` | `test-android` | `release-android`: release APK and AAB, unsigned unless signing is configured separately |
| Web JavaScript | `run-web` | `test-web` tests both browser targets | `release-web` builds both JS and Wasm production sites |
| Web Wasm | `run-web-wasm` | `test-web` | `release-web` |

Every release command builds files locally; none publishes, uploads, tags, or
installs a release. Desktop installers must be built on the target OS. Android
release files are not installable development APKs without signing; use
`run-android` for the normal debug install/update loop.

```sh
devenv shell -- run-desktop
devenv shell -- run-cli --help
devenv shell -- run-cli blob put ./sample.png
devenv shell -- run-android
devenv shell -- run-web
devenv shell -- release-desktop
devenv shell -- release-cli
devenv shell -- release-android
devenv shell -- release-web
```

`run-cli` runs Spirit2's CLI, not a separate AFM CLI. It defaults to help without
arguments and forwards all supplied arguments to Spirit. Other Gradle-backed
commands accept Gradle arguments, for example `test-desktop --max-workers=2`.

### Android device and emulator selection

`run-android` builds the debug APK, uses the single ready adb device when one is
connected, installs with `adb install -r`, and starts `com.abysl.afm/.MainActivity`.
If no devices are connected, it creates or reuses the project AVD and waits for
adb plus Android boot completion before installation. Multiple ready devices
require `ANDROID_SERIAL`; unauthorized/offline devices produce an error rather
than being silently bypassed. Enable USB debugging and authorize the computer
when using a phone. Emulator acceleration on Linux requires accessible `/dev/kvm`.

| Environment variable | Purpose |
| --- | --- |
| `ANDROID_SERIAL` | Explicit adb target; never silently substituted |
| `AFM_ANDROID_AVD` | AVD name, default `afm` |
| `AFM_ANDROID_SYSTEM_IMAGE` | Installed image ID; defaults to SDK 36 Google APIs for the host architecture |
| `AFM_ANDROID_NO_WINDOW=1` | Start the fallback emulator without its window |

The devenv Android module keeps AVDs under `.android/avd/` in this workspace.
Emulator diagnostics go to `.android/afm-emulator.log`. Startup is bounded;
failed startup cleans up only the emulator started by that invocation. A
successfully started emulator stays running for subsequent commands. No helper
wipes an AVD, uninstalls an app, or clears app data. A debug signing-key mismatch
fails explicitly; do not uninstall just to make a persistence check pass.

`build-android` only builds the debug APK. `install-android` builds and installs
without opening the activity. The launcher is Kotlin build logic; to install and
launch an already-built APK without rebuilding, run inside the shell:

```sh
./gradlew afmRunAndroid -PafmApk=path/to/app.apk --no-configuration-cache
```

APK paths are relative to the KMP workspace unless absolute. `afmInstallAndroid`
performs the same update without launching. `afmSelectAndroid` selects/boots a
device and writes its serial to an empty temporary file named by
`-PafmDeviceOutput=...`. `test-android` uses that file to export `ANDROID_SERIAL`
before its instrumentation Gradle invocation, then removes the file. This thin
shell handoff is necessary because Android device selection is read when Gradle
starts. Existing emulator reuse is reported explicitly; a headless instance does
not gain a window merely because another command reuses it.

### Web development and current backend boundary

The web run commands use Gradle's development server with `--continuous` to
rebuild Kotlin changes and reload the browser. Open `http://localhost:8080`
for JS or `http://localhost:8081` for Wasm; the commands do not open a browser
automatically. This is browser live reload, not a promise of preserving UI state
across recompilation. Stop with Ctrl+C.

**Web currently runs the UI only.** The generated UniFFI Kotlin bindings use
JNA, so the Rust storage backend is available on JVM desktop and Android, not
in JavaScript/Wasm browsers. The web smoke test verifies that storage reports
unavailable; it is not a native-storage or file-transfer test. A future browser
backend is separate work.

### Additional tests and preparation

| Command | Scope |
| --- | --- |
| `test-rust` | Complete Spirit2 Rust workspace, including core, CLI, node, and FFI tests |
| `test-sdk` | Kotlin SDK JVM tests against generated UniFFI and the native library |
| `test-desktop` | AFM JVM tests, including binary/empty-byte storage and reopening through the full native stack |
| `test-android` | AFM instrumentation, including the same native storage contract on the selected phone/emulator |
| `test-web` | Shared/browser tests for JS and Wasm in headless Chromium |
| `test-launcher` | Kotlin Android selection, boot, timeout, install, and launch tests with fake devices/processes |
| `test-aliases` | Kotlin tests of generated root/KMP scripts, forwarding, device handoff, and failures |
| `test-plumbing` | Rust, SDK JVM, AFM JVM, launcher, and alias tests; device/browser tests remain explicit |
| `prepare-native` | Spirit2 release native library and matching generated Kotlin bindings |
| `android-native` | JNI libraries for both arm64-v8a and x86_64 |

Native commands prepare the Rust library and bindings before Gradle; no manual
copying is needed. `prepare-native` delegates to Gradle's `afmGenerateBindings`,
which invokes Cargo and UniFFI directly; `android-native` uses `afmAndroidNative`.
Generated bindings, native binaries, AVDs, and build output remain ignored. Kotlin tests
produce reports under the owning Gradle module's `build/reports/tests/`;
Android connected-test reports are under `app/shared/build/reports/androidTests/`.
Run `devenv info` to list all scripts and their descriptions.

Compatibility names remain: `desktop` → `run-desktop`, `apk` → `build-android`,
`install` → `install-android`, and `unit-test`/`jvm-test` → `test-desktop`.
`generate-bindings` and `jvm-native` both delegate to `prepare-native`.
`assemble` builds the supported desktop, Android, JS, and Wasm outputs without
implicitly requesting unsupported iOS tests on Linux.

### Artifact locations

Paths are relative to this directory:

| Output | Location |
| --- | --- |
| Debug APK | `app/androidApp/build/outputs/apk/debug/androidApp-debug.apk` |
| Release APK | `app/androidApp/build/outputs/apk/release/` |
| Release AAB | `app/androidApp/build/outputs/bundle/release/` |
| Linux installer/runtime image | `app/desktopApp/build/compose/binaries/main/` |
| CLI archive | `build/release/cli/` |
| JS production site | `app/webApp/build/dist/js/productionExecutable/` |
| Wasm production site | `app/webApp/build/dist/wasmJs/productionExecutable/` |

Serve an extracted production site with an HTTP server; opening `index.html`
directly as a `file:` URL is not a supported launch path.

## CI delivery

The new delivery pipeline is implemented by the `afm.delivery` Gradle plugin
in [build-logic](build-logic/README.md), not Python orchestration. It exposes
`afmPrepareNative`, `afmCiBuild`, `afmDeliveryPreflight`, and `afmDeliver`.
Both CI and developer preparation invoke Cargo and UniFFI directly. Launcher and
alias tests now run through Kotlin/JUnit, and AFM contains no Python scripts.
Existing alias names remain available; custom APK selection now uses `-PafmApk`
instead of the removed helper's positional APK argument.

See the [delivery design](../wiki/design/delivery.md) for the trusted-main task
sequence, stable Android signing secrets, immutable Forgejo artifacts, and the
later GitHub Releases/Obtainium sync. Live publication requires provisioning the
protected secrets; a successful local package build is not evidence of upload.

## IntelliJ IDEA

Start IntelliJ from the project devenv so Gradle-run desktop applications inherit the Nix OpenGL runtime libraries:

```text
devenv shell -- idea .
```

Quit existing IntelliJ processes before using this command. The project-local IntelliJ settings use the Gradle wrapper and the devenv-provided Gradle JVM.

`generate-bindings` and the native build commands compile the sibling Rust workspace and write generated bindings or Android JNI libraries into ignored SDK directories. The JVM SDK package embeds the release native library as a JNA classpath resource; Android consumes the JNI library and JNA AAR.
