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

## QR mesh pairing

1. Open AFM on Android B and on fresh devices A and C (Android or desktop).
2. On B, select **Pair device**, grant camera permission, and scan C's displayed
   QR. B creates a mesh on its first scan; C joins that mesh.
3. On B, scan A's QR. Signed membership propagates until each lists the other
   two. A and C communicate directly without requiring B to remain running.
4. If a ticket expires or was used, select **Refresh QR** on the device displaying
   it. Tickets are exact Spirit `spirit1` tickets: single-use, valid for up to five
   minutes, and bearer secrets. Show them only to trusted devices.

Only Android offers **Pair device** and opens a bundled camera scanner; there is
no external scanning service. Permission/scanner launches stay disabled until
the in-flight result returns; denial and cancellation return to AFM safely. All
native targets display their QR and paired devices, including offline members.
Starting several independent meshes and then merging them is not supported. A
failed first enrollment can leave the scanner with a founder-only mesh; its
original receiver ticket is then withdrawn. Subsequent scans continue using that
mesh rather than replacing successful pairings. To join an existing mesh, have
one of its members scan the fresh device's ticket, not the other way around.

New meshes use a random `mesh1_...` ID independent of device identity. Members
need compatible Spirit versions to enroll in them. Existing legacy meshes retain
their signed IDs rather than silently migrating. The founder is not an always-on
coordinator; any member can enroll another fresh device.

Spirit exchanges authenticated ping/pong every five seconds while the native node
is running. Green/Online means a ping or pong arrived from that identity less than
60 seconds ago; red/Offline means none did. Membership gossip, attempted sends,
and connection errors do not reset this timer. Display state is refreshed about
once per second. Names may repeat; full node IDs distinguish peers.

Android retains one node in an Activity ViewModel across rotation and scanning,
using `noBackupFilesDir/afm-node` for identity and membership (not cloud backup).
Desktop uses `~/.spirit2/afm-node`, separate from the existing blob store directory.
Closing the owner shuts down the node; reopening retains its identity and mesh,
not cached online status. Blob storage remains open until that same owner closes,
even if pairing cannot open its node. Native actions already in progress complete
before node shutdown; cancelling a coroutine does not undo remote enrollment. There is no Android foreground service: process
suspension, force-stop, or app closure can stop heartbeats and eventually make the
device appear offline. Pairing does not implement file transfer.

See the [implementation plan](../../../plans/qr-mesh-pairing/plan.md) for automated
validation and the outstanding physical camera/cross-network acceptance checks.
