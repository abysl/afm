# AFM app

`app` is the Compose Multiplatform client of the parent `afm` Gradle workspace.

| Gradle project | Role |
|---|---|
| `:app:shared` | Shared UI, store integration, and platform source sets |
| `:app:androidApp` | Android entry point |
| `:app:desktopApp` | Desktop JVM entry point |
| `:app:webApp` | JavaScript and Wasm web entry point |

The shared module consumes `blue.rae.spirit:spirit-mesh` for portable node and
pairing-session contracts, and `blue.rae.spirit:spirit-sdk` for JVM and Android
native nodes/stores. Both come from the pinned `deps/spirit2/kmp` composite
(relative to the repository root).
Spirit owns pairing, ticket expiry, membership, and heartbeat presence; AFM owns
presentation, the Android camera, data-directory selection, and app lifecycle.
Web and iOS expose the UI without a native backend because UniFFI uses JNA.

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

## Groups and device invitations

Select **New group** and enter a name (1–128 UTF-8 bytes, no control characters).
Existing `AFM mesh` membership appears as a group; new installs start empty.
To join, select **Join a group** and show your single-use QR or ticket to a member.
Members select the intended group and scan or paste the ticket to add your device.
An invitation is not tied to a group; share it only with trusted members.
Pings run every five seconds; presence lasts 60 seconds.
Peers need compatible Spirit versions. Older builds refuse migrated node
directories; prerelease users cannot downgrade. In-flight actions finish before
shutdown; cancelling does not undo an enrollment.

See the [implementation plan](../../plans/multimesh-groups/plan.md).
