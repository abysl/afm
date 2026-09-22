# AFM app

`app` is the Compose Multiplatform client of the parent `afm` Gradle workspace.

| Gradle project | Role |
|---|---|
| `:app:shared` | Shared UI, store integration, and platform source sets |
| `:app:androidApp` | Android entry point |
| `:app:desktopApp` | Desktop JVM entry point |
| `:app:webApp` | JavaScript and Wasm web entry point |

The shared module consumes `blue.rae.spirit:spirit-sdk` from the pinned `../deps/spirit2/kmp` composite for JVM and Android. Web and iOS builds expose the UI without a native store because the current UniFFI Kotlin bindings use JNA.

Use the parent project's [run/test/release matrix](../README.md#run-test-release-matrix)
so native builds and binding generation run before the relevant Gradle tasks:

```sh
cd ..
devenv shell -- run-desktop
devenv shell -- run-android
devenv shell -- run-web
devenv shell -- test-plumbing
devenv shell -- release-desktop
```

The parent environment reuses Spirit2's native preparation, supplies the Android
emulator fallback, and configures the runtime dependencies required by Compose
Desktop. Browser launch commands run the UI without a native storage backend;
Android and JVM tests exercise AFM through Kotlin, UniFFI, and Rust.
