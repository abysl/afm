# AFM app

`app` is the Compose Multiplatform client of the parent `afm` Gradle workspace.

| Gradle project | Role |
|---|---|
| `:app:shared` | Shared UI, store integration, and platform source sets |
| `:app:androidApp` | Android entry point |
| `:app:desktopApp` | Desktop JVM entry point |
| `:app:webApp` | JavaScript and Wasm web entry point |

The shared module consumes `blue.rae.spirit:spirit-sdk` from the pinned `../deps/spirit2/kmp` composite for JVM and Android. Web and iOS builds expose the UI without a native store because the current UniFFI Kotlin bindings use JNA.

Run builds from the parent project:

```text
cd ..
direnv allow
unit-test
./gradlew :app:shared:jvmTest
desktop
./gradlew :app:desktopApp:run
apk
./gradlew :app:androidApp:assembleDebug
```

The parent development environment delegates native builds and binding generation to `../deps/spirit2/kmp`, then configures the runtime dependencies required by desktop Compose. Initialize the submodule from the AFM root with `git submodule update --init --recursive` before building.
