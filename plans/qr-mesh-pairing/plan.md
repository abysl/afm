# AFM QR mesh pairing implementation plan

## Requested outcome

Android node B scans node C and node A. All three retain membership in one
mesh, display their own node-ticket QR code and the other devices, and exchange
ping/pong every five seconds while the app's node is running. A device is green
only when a ping or pong from that identity arrived less than 60 seconds ago;
otherwise it is red. Only Android offers the camera-based Pair device button.

This is an explicitly requested pairing slice of the first-file-transfer roadmap,
not completion of that roadmap's foundation or file-transfer acceptance gates.
Browser/iOS remain UI-only. Background Android service delivery and file transfer
are out of scope; stopped apps eventually appear offline.

## Implementation sequence

1. Inspect the pinned Spirit2 SDK, pairing protocol, membership propagation,
   heartbeat events and existing tests. Preserve the existing dependency pin
   unless a separately reviewed/published dependency change is necessary.
2. Put reusable contracts, existing node models, pairing sessions, ticket lifecycle
   and received-heartbeat presence into Spirit2's KMP library. A pure KMP `mesh`
   module lets AFM's common UI consume them without bringing JNA into browser or
   iOS targets; the native `sdk` implements the same contract for Android/JVM.
   AFM owns only UI, camera scanning, persistent app directories and lifecycle
   wiring. Ensure scanning two separate nodes grows one mesh rather than replacing
   the first pairing; do not add an AFM network protocol or heartbeat timer.
3. Add the shared device panel: local ticket QR, peer identities, accessible
   online/offline indicators, pending/error states. Use monotonic received-event
   timestamps, never sent messages or indirect membership gossip, for presence.
4. Add an Android-only bundled QR camera scanner, permission/cancellation/error
   handling, and validation before pairing. Keep camera access scoped to scanning;
   do not require a network scanner service or camera access at app startup.
5. Test presence thresholds, identity deduplication, invalid/self/repeated scans,
   pairing failures, lifecycle cleanup and the three-node scan order. Build the
   Android and JVM consumers after native preparation. Record unavailable device
   and camera acceptance checks rather than treating compilation as runtime proof.
6. Review the changes and open focused PRs with commands/results and manual test
   steps. Split dependency work from AFM if necessary; publish dependency commits
   before moving the AFM gitlink. Do not publish release artifacts or alter signing.

## Confirmed design and review boundaries

- Three focused implementation PRs: [Spirit2 #3](https://github.com/abysl/spirit-library2/pull/3)
  corrects presence; [Spirit2 #4](https://github.com/abysl/spirit-library2/pull/4)
  adds reusable KMP sessions; [AFM #7](https://github.com/abysl/afm/pull/7)
  integrates the app and published dependency pin. Merge dependencies before AFM.
  Documentation is reviewed separately in each repository, not mixed into these
  implementation diffs. No release/signing changes.
- Spirit2's `spirit1` ticket is an existing five-minute, single-use bearer
  enrollment secret, not just reachability information. Display it only to
  trusted devices. The scanner admits the displayed device to its mesh using
  the native authenticated handshake; no extra approval protocol is invented.
- Fresh apps do not independently create meshes at startup. B creates one on its
  first eligible scan, then admits C and A into it. Independent existing meshes
  cannot merge. A failed first enrollment may leave B with an empty persistent
  mesh, while no remote membership is fabricated. Invalid syntax and scanning
  the currently displayed self ticket are rejected before creating a mesh.
- Reuse persisted native identity and membership; UI presence starts offline after
  process restart until an actual new heartbeat is received.
- One app-owned node is shared across screens and configuration changes. Network
  work runs off the UI thread and is shut down when its owner is destroyed.
- QR payloads are the exact native ticket, not a new AFM URL or incompatible
  envelope. Display only validated generated codes with a quiet zone.

## Acceptance checks

- Start isolated nodes A, B, C; B pairs with C then A. Each eventually lists both
  other nodes, and A/C exchange heartbeats without B forwarding their liveness.
- Repeat with A then C, duplicate scans, malformed and self tickets, and a failed
  remote node. Existing successful pairings survive a later failure.
- Stop C: A and B remain green until their last received C heartbeat reaches
  60 seconds, then turn red. Restart C using its original directory: membership
  survives and fresh traffic restores green.
- On Android, opening Pair device requests camera permission only as needed,
  cancel/deny returns safely, and one decoded QR triggers one pairing attempt.
- Linux displays its QR and devices but no Pair device camera button. Android
  rotation does not create a second owner for the same native directory.

## Validation record

AFM consumes published Spirit revision `35db362d37f5793473587cc71f946361cb30377f`.
The prerequisite reviews record passing native node/FFI tests, virtual-time
session tests and three-node local SDK membership/no-introducer/restart coverage.

From `kmp/`, these integrated checks passed:

```sh
devenv shell -- prepare-native
devenv shell -- android-native
devenv shell -- ./gradlew :app:shared:jvmTest :app:desktopApp:classes :app:androidApp:assembleDebug :app:shared:compileKotlinJs :app:shared:compileKotlinWasmJs --no-configuration-cache
```

- Nine AFM JVM tests passed: native-store regression, pure QR matrix checks,
  screenshot capture of the real Compose renderer decoded back to its exact
  ticket, expired QR hiding, narrow-window guidance, peer identity/status labels,
  and no desktop camera button. Rendering preserves a four-module white quiet
  zone and integer-pixel modules.
- Both Android native ABIs built and the debug APK assembled. The merged manifest
  keeps the camera optional and overrides the bundled scanner to `fullSensor`.
- Installed/launched that APK on an API-36 x86_64 emulator using `afmRunAndroid`.
  Observed live node identity and QR, click-time permission prompt, denial/retry,
  portrait camera startup and sensor rotation to landscape, Back cancellation,
  and return with the same node identity and no added membership. No app crash
  was observed. The local SDK root and installed image selection needed to be
  aligned for emulator startup; this is not a source-build change.
- `git diff --check` passed. No release artifact was signed or published.

Still unverified: physical-camera decoding, three physical A/B/C devices across
real networks, installed Linux-package portability, and iOS runtime. JS/Wasm were
compiled, not claimed to support native pairing. No Android foreground service
is included; process suspension/termination can stop heartbeats. The earlier
roadmap's broader acceptance gates remain open.
