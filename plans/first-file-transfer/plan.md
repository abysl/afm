# Spirit2 and AFM first file-transfer roadmap

## Status and scope

Foundation work is in progress. The run/test/release command matrix and the
CI delivery pipeline are implemented: trusted-main Woodpecker runs publish signed
Android and Linux development prereleases to GitHub (first `v0.1.1765`). QR mesh
pairing has landed separately. The full foundation stage is not yet accepted:
physical-device installation, in-place update and persistence evidence, and
Android instrumentation in CI remain open. The file-transfer UX and real
transfer remain planned.
The agreed first targets are Android and Linux desktop. Delivery order is:
**development/testing and CI → AFM file-transfer UX → real transfer**.

This is the current first-delivery roadmap for AFM and Spirit2. It supersedes
starting with broad store ownership design or requiring the three-device
CLI/AFM/Kai retention scenario before AFM is useful. That larger scenario
remains a later milestone, not a prerequisite for this one.

## Workflow

This project-local roadmap is the source of truth for AFM work. Use bounded
pull requests and record build/test evidence and unresolved limitations in the
review. Do not mark a stage complete until its acceptance gate is met. Commit
and publish dependency changes separately before updating the pinned gitlink.
Deployment configuration and credentials remain outside the application repo.

## Outcome

From AFM on either Android or Linux desktop:

1. Pair the devices once and retain their identities and completed pairing.
2. Choose Add file and select a file with the platform's system file picker.
3. Import an immutable snapshot and show its name, size, and availability.
4. See the added file on the other paired device without entering a hash.
5. Choose Download, observe transfer state, then open or save the file locally.
6. Restart both apps and repeat without pairing again.

Adding advertises file metadata to paired devices; it does not automatically
copy the file's contents to every device. Changing the original file after
import does not silently change the imported snapshot.

## 1. Development, testing, and CI foundation

Baseline: the accepted pairing/SDK review; verify current behavior rather than
assuming historical test results still pass.

### First task: establish the run/test/release command matrix

The initial Linux-only baseline task was expanded to cover convenient desktop,
CLI, Android, and web aliases. The [command matrix](../../kmp/README.md#run-test-release-matrix)
is the usage reference. Native paths must build from Rust through generated
UniFFI bindings into AFM; browser paths explicitly remain UI-only.

From a clean review checkout with the intended Spirit2 revision available,
verify `run-*`, `test-*`, and `release-*`. Android must use an attached adb
device or safely boot the project emulator; browser development should rebuild
and reload on changes. Release commands produce local artifacts, not publication.
Do not force-update submodules or replace an active checkout.

Record revisions, command results, artifacts, and remaining manual gates in
the pull request and CI reports. Fix specific setup blockers without adding
file-transfer features. Emulator tests do not replace physical Android checks,
and local builds do not complete the separate CI work in this stage.

### CI and phone-test delivery

The [delivery design](../../wiki/design/delivery.md) defines the Kotlin/Gradle
implementation: full-history pinned-source verification, Rust/UniFFI preparation,
test and artifact tasks, separate protected signing/publication, immutable
Forgejo bundles, and guarded latest promotion. The former Python CI prototype
has been replaced rather than retained as a second orchestrator.

Delivered rollout work:

- The dedicated AFM signing identity and Forgejo/GitHub publication credentials
  are provisioned in Woodpecker. Preserve the key for every subsequent Android update.
- Real main pipeline runs publish signed, verified GitHub prereleases containing
  the universal APK and Linux `.deb`, as specified in the delivery design.

Remaining rollout work:

1. Install a published prerelease through Obtainium on a real phone and the
   published `.deb` on a Linux desktop; record versions and results.
2. Prove an in-place phone update to a higher prerelease preserves AFM data and
   completed pairing. Do not treat debug-to-release migration as an ordinary
   update or uninstall automatically.
3. Run Android instrumentation on an emulator in CI. `afmCiBuild` currently runs
   Rust, SDK, AFM JVM, build-logic, and Android host unit tests; `test-android`
   remains a local command.

### Remaining foundation scope

Audit and reuse the existing devenv environments, Gradle wrappers, Rust tests,
Kotlin tests, native preparation, and Spirit2 CI rather than creating parallel
build systems. Include AFM as a consumer, not only the Spirit2 demo.

### Deliverables

- Document and verify clean-checkout setup, dependency/submodule preparation,
  binding generation, build, test, Linux desktop run, and Android install/update
  commands. Pin the compatible Spirit2 revision and required toolchains.
- Make native library generation and loading reproducible for Linux desktop and
  Android. No reliance on untracked binaries or previously generated bindings.
- Establish Rust and Kotlin/SDK test commands, AFM shared UI-state tests, Compose
  UI test infrastructure with a fake backend, and Android instrumentation with
  a passing smoke test. Later feature tasks add their actual behavior tests.
- Establish a two-node integration harness with isolated persistent directories
  and deterministic loopback operation. Exercise current pairing and reconnect
  behavior without requiring a public relay in every unit-test run.
- Add or extend CI in the owning repositories for relevant pull requests and
  main-branch changes. Build the Spirit2 CLI/native dependencies and AFM Android
  APK and Linux desktop distribution from a clean checkout. Generate bindings,
  run the Rust/Kotlin/AFM automated suites, and retain test reports and installable
  AFM build artifacts with their source revisions.
- Run Android instrumentation in CI on an emulator, and Linux Compose UI tests
  in a supported headless display environment. Physical-device checks remain
  separately recorded; packaging success does not imply those checks passed.
- Define an APK update/signing path that preserves app data during development
  and CI artifact testing. Supply any signing credentials through protected
  runner secrets, never committed files. Release signing is not required here.
- Verify the existing Spirit2 identity/pairing baseline across desktop process
  restart and Android force-stop/relaunch and reboot. Record device IDs before
  and after, membership, and a successful authenticated reconnect. AFM-specific
  persistence is verified again after its integration in stage 3.

### Exit gate

A documented clean-checkout run and successful CI run produce usable Android
and Linux AFM artifacts and passing test reports. Install/run the artifacts on
an Android device and Linux desktop. Record commands, revisions, CI artifact
links, and persistence evidence in the stage's review report. No feature work
starts merely because a workflow file exists; missing test execution or device
checks remain explicit blockers.

## 2. AFM file-transfer UX

Prerequisite: stage 1's development, testing, and CI exit gate.

Build and review the user flow against a replaceable fake transfer backend.
Use real Android system document selection and Linux native file dialogs;
remote catalogs, peers, and transfer behavior can be simulated in this stage.
Do not present simulated transfers as working networking.

### Deliverables

- Device/pairing screens that distinguish saved membership from current
  connectivity, including pending invitation and disconnected states.
- Add file on either platform, picker cancellation, import progress/errors,
  and a file list showing name, size, source device, and local/remote state.
- A remote-file list and Download action, with queued/transferring/verifying,
  completed, canceled, failed, and source-unavailable states.
- Progress, cancel, retry, open, and save/export interactions. Separate bytes
  downloaded into AFM storage from successful export to a user-selected location.
- Explicit privacy copy explaining that added files are shared with paired
  devices. Do not upload unrelated files or scan the filesystem automatically.
- Shared state and Compose UI tests covering the flow and failure states;
  platform tests/manual evidence for native picker and destination behavior.

### Exit gate

Review the flow on Android and Linux using a real selected file and simulated
remote transfers. Tests cover state transitions, cancellation, retry, picker
cancellation, and failed export. Approve the UX before implementing networking.

## 3. Connect the UX to real Spirit2 transfers

### 3a. Minimal contract driven by the approved UX

Prerequisite: stage 2's UX approval. Limit design to what the first AFM flow needs:

- Node/store lifecycle and locking, stable data directories, and a clear error
  for conflicting owners. Broader shared CLI/Kai process ownership stays later.
- Import, local availability, peer fetch, export, progress, cancellation, and
  consistent errors across Rust, UniFFI, and Kotlin.
- Bounded-memory file I/O, Android content-URI handling without assuming a
  filesystem path, and safe Linux destination handling.
- An AFM-owned persistent shared-file catalog with names, sizes, hashes, and
  provider identity. Define authenticated metadata exchange, reconnect catch-up,
  and stale/offline provider states; seeing metadata is not proof bytes are local.
- Explicit authorization for paired-device file listing and retrieval. Hash
  knowledge alone must not grant an unpaired device access.

AFM owns names, catalog entries, and presentation. Spirit2 owns verified
content-addressed bytes and transfer. Do not introduce a general record system
or a distributed filesystem to satisfy this contract.

### 3b. Transfer engine and bindings

Agree the contract in stage 3a, implement the transfer engine, then expose it
through the bindings.

Implement verified atomic whole-file import and authenticated peer transfer,
then expose the agreed operations through the SDK/bindings and CLI. Fix existing
storage shortcuts that equate path existence with verified availability or reuse
one temporary name for concurrent same-content writes. A corrupt existing blob
must not be accepted as a successful import.

Bound memory and transfer resources, clean up canceled/interrupted temporary
files, distinguish corrupt/missing/unavailable/I/O failures, and never publish
partial data as a completed download. Insufficient disk space must fail safely;
automatic capacity policy and eviction are not required yet. Test unauthorized
peers, corruption, concurrent import, cancellation, retry, and process restarts.

### 3c. Real application integration

Prerequisites: stage 3b's working bindings and stage 2's approved UX.

Replace the fake backend with persistent identity/pairing, local import, catalog
persistence and peer metadata discovery, real transfers, and platform open/save.
Keep the fake for deterministic UI tests. Reuse the existing pairing SDK, and
verify Android uses a stable non-backed-up identity directory and Linux uses a
stable application data directory. Do not regenerate identity on ordinary launch.

On completion, both platforms can import, advertise, discover, download, verify,
and open/export the same file without users typing hashes. This stage does not
wait for retention or cache eviction.

## 4. Prove the two-device milestone

Prerequisite: stage 3's real AFM integration. Use a physical Android device and
a Linux desktop running CI-produced artifacts. Record revisions, device IDs,
actions, and results:

- Add on Android → discover on Linux → download → open/save.
- Add on Linux → discover on Android → download → open/save.
- Independently compare source and destination hashes/bytes in both directions.
- Restart both apps and reboot the Android device; identities, completed pairing,
  imported files, and saved catalog entries survive, and peers reconnect.
- Update the Android app without clearing data and repeat the persistence check.
- Interrupt a transfer; no partial file is marked complete, and retry succeeds.
- Take the source offline; its file remains visible with honest unavailable
  status, then becomes fetchable after reconnect without re-pairing.
- Exercise corrupt bytes, insufficient destination space, and export failure;
  show actionable errors rather than a false successful download.
- Exercise the real two-device connection path; loopback and emulators alone
  do not satisfy this milestone.

Outstanding pairing invitations may expire or disappear on restart; completed
pairing must not. Uninstalling/clearing app data is not expected to preserve
identity. Reconnect is required; live presence itself is not persisted.

## After the first AFM milestone

Only after stage 4:

- Define retention/offline accounting and capacity/eviction policy.
- Implement retention, repair, safe cleanup, and capacity policy.
- Integrate Kai after Kai's own prerequisites are also met.
- Prove the larger three-device CLI/AFM/Kai retention scenario.
- Consolidate delivered storage documentation; every earlier task still updates
  its own usage and test documentation as it lands.

Windows, macOS, iOS, web transfer, Android background services, transforms/Nix
execution, mutable file synchronization, automatic full-mesh replication,
general records, and erasure coding remain outside this first delivery.

## Later shared-storage design notes

These are future goals and open policy questions, not extra gates for the
first AFM transfer. The
shared-core/application-owned-meaning boundary applies throughout delivery.

- **Shared core, application-owned meaning:** AFM owns its file-manager views and user-facing names; Kai owns game/card/asset semantics. Both consume the same Spirit SDK and content-addressed storage behavior. Keep application schemas out of Spirit2. Determine how embedded applications and the CLI access a device's store without conflicting ownership.
- **File/folder copy levels:** level 3 requests retained copies on three distinct devices, level 2 on two, and level 1 on one. Repair missing copies. Proposed policy model: folders supply defaults and files can override them. Lowering a level releases surplus retention obligations; extras become evictable or are removed to reclaim space.
- **One assigned disk budget per device:** retained content and opportunistic cache share the user's allocation to Spirit2, used by AFM and Kai. Retained content takes priority; cache fills the remaining usable space. Levels define required retained copies; additional cached copies may exist above that count. Cache eviction respects the effective file/folder policy, offline pins, and application retention requirements. Never evict a required replica without first ensuring the retention target remains satisfied. Cached bytes only count as a retained replica after an explicit retention commitment; avoid storing the same hash twice locally.
- **Capacity warnings and performance:** warn as retained content approaches the assigned budget and prompt the user to allocate more disk space. Distinguish healthy cache fullness from retention pressure. More assigned space lets more files stay local, reducing network fetches; once retained content plus cache can hold the whole library, all available files can be kept locally. Show retained usage, reclaimable cache, and how much of the library is available locally. Report insufficient capacity rather than silently weakening copy levels.
- **Cache policy details to settle:** choose useful content for spare cache capacity, then evict eligible content according to policy and usage (for example, least recently accessed). Respect the assigned budget and filesystem free-space headroom, including metadata and temporary transfer space. Define warning thresholds and behavior when the user reduces the budget below retained usage.
- **Shared visibility:** AFM should show a file even when its bytes are stored elsewhere, along with its requested level, retained versus cached copies, currently reachable copies, and pending transfers or cleanup. Show cache usage and the effective inherited file/folder policy.
- **Later placement implementation:** distribute whole files across devices and replicate each according to its level. Clarify whether later sharding means splitting individual files into chunks; keep chunk placement and erasure coding outside the initial delivery.
- **Later three-device milestone:** after the bidirectional AFM flow works, pair three devices, import one immutable file, fetch and verify it through CLI and AFM/Kotlin, and have a minimal Kai integration retrieve and use the same content through the Rust SDK. Raise its level to 3, observe three confirmed retained copies, then lower it to 1 and demonstrate safe reclamation of extras under cache pressure. Include restart, interrupted transfer, and unavailable-device cases. Define version/update behavior separately before treating mutable source files as synchronized content.
- **Safe cleanup:** never count an incomplete transfer as a copy. Coordinate replica deletion so devices cannot independently delete every copy. A simple first design could use one designated placement coordinator and pause automatic deletion while it is unavailable. Temporary read caches, explicit offline pins, and retention requirements from multiple applications need defined behavior before exact storage counts can be promised. Removing an AFM reference must not discard content still retained by Kai, or vice versa.
- **Open decision:** does an offline device's confirmed stored copy count toward the level, or should the system create replacements to maintain that many reachable copies? Show unmet targets explicitly when there are too few eligible devices or insufficient capacity; “at all times” needs this failure policy defined.
- Keep transforms, broad record systems, automatic full-mesh replication, and additional platform support outside this initial delivery. Document the selected Spirit2 behavior and deliberate differences from Spirit1 as each user story lands.
