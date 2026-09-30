# AFM app

`app` is the Compose Multiplatform client of the parent `afm` Gradle workspace.

| Gradle project | Role |
|---|---|
| `:app:shared` | Shared UI, store integration, and platform source sets |
| `:app:androidApp` | Android entry point |
| `:app:desktopApp` | Desktop JVM entry point |
| `:app:webApp` | JavaScript and Wasm web entry point |

The shared module consumes `blue.rae.spirit:spirit-mesh` for the portable
`MeshSession`/`MeshNode` contracts and `blue.rae.spirit:spirit-sdk` for JVM and
Android native nodes/stores. Both come from the pinned `deps/spirit2/kmp`
composite build. Spirit owns tickets, group membership, departures and presence;
AFM owns the screens, Android camera, data directories and lifecycle. Web and iOS
expose the UI without a native node because UniFFI uses JNA.

Use the parent project's [run/test/release matrix](../README.md#run-test-release-matrix)
so native builds and binding generation run before the consuming Gradle tasks:

```sh
cd ..
devenv shell -- run-desktop
devenv shell -- run-android
devenv shell -- run-web
devenv shell -- test-plumbing
devenv shell -- release-desktop
```

## Groups and device invitations

1. Select **New group** on the groups home and enter a name. Fresh installs do
   not create a group automatically. Existing installs show their original
   `AFM mesh` as an ordinary group, retaining its signed identity and members.
2. To join someone else's group, select **Join a group** and show your device's
   QR or ticket text to a current member. The invitation is not tied to any
   group. The member chooses which group to add you to; your device displays
   **Joined Family** (with your group name) when it learns of the enrollment.
3. To invite another device, open the desired group and choose **Scan QR code** or **Add pasted code**:
   scan its displayed QR with the bundled Android camera or paste its ticket
   into **Paste code** on Android or desktop. Scanning never creates a group.
   Camera permission, denial and cancellation are handled in AFM. The pending
   target group is retained through Android Activity recreation; repeat scans
   cannot redirect an in-flight ticket into another group.
4. QR tickets are single-use bearer secrets valid for up to five minutes. Show
   one only to trusted devices. Select **Refresh QR** when the code expires or
   was used. A rejected ticket needs a fresh QR; an unreachable device needs a
   working connection. A device can belong to up to 64 groups.
5. Members show both a colored presence indicator and **Online**/**Offline**.
   Only a recent authenticated ping or pong counts as online; Spirit pings
   every five seconds and a peer stays online for 60 seconds after a reply.
   Peers need compatible Spirit versions. Closing AFM eventually makes a
   device appear offline. Any member can add a device; there are no roles,
   owners or admin permissions.

6. Open a group's menu and select **Leave group**. Confirm after reading that files
   held only here become unavailable and unsynced changes are lost. If this
   device is the last member, leaving deletes the group. AFM reports how many
   other members were notified. Unreached members learn through a notified
   member or on next contact with this device while it keeps a departed copy.
   Leaving does not erase files other members already downloaded.

Adding a device lets it see the group's members, and eventually all files in
that group, including files added later. Showing your QR lets its holder add
**you** to one group of their choosing. Being in two groups reveals the same
device identity to members of both. Leaving a group is not revocation: already
downloaded copies stay on other devices, and readmission may expose this device
to that group again. Do not show your QR to untrusted devices.

Android keeps one `MeshSession` in its Activity ViewModel across rotation and
scanning, using `noBackupFilesDir/afm-node` for identity and membership. Desktop
uses `~/.spirit2/afm-node`. The migration is one-way: an older build refuses a migrated
node directory, so prerelease users cannot downgrade. Spirit's process-wide node-directory lease waits for a closing owner
before reopening, or reports `NodeBusy` after 15 seconds. The independent demo blob store still uses the existing app directory and remains open
until its owner closes even if opening the node fails. Group file imports use
the node-owned store at the sibling `afm-store` directory, not that demo store. The Android scanner model uses `SavedStateHandle` to keep the target group through Activity recreation and never retains an Activity, Context, or launcher.
Its permission and camera steps wait for the existing result after restoration.
There is no Android foreground service. In-flight actions finish before the
node shuts down; cancelling a screen does not undo an enrollment. A leave
confirmed while the app closes may not start. The group remains visible so
you can retry after reopening.

See the [implementation plan](../../plans/multimesh-groups/plan.md) for outstanding physical camera and cross-network checks.

## Catalog API for the files screen

Native Android and desktop owners expose `GroupCatalogs` alongside `MeshSession`.
`entries(meshId)` is a `StateFlow<List<CatalogEntry>>` with ID, name, BLAKE3 hash,
size and author. Import bytes using `MeshSession.files.value` first, then call
`addFile(meshId, name, ImportedBlob)`; call `remove(meshId, entryId)` to stop
sharing an entry, without deleting local bytes. Fetch/export remains a separate
UI action. `errors` exposes per-group synchronization failures. The web builds
remain UI-only and do not instantiate a catalog. The signed format, disk layout
and app-channel protocol are in [catalog design](../../wiki/design/catalog.md).

The node's native blob store is `afm-store`, a sibling of `afm-node` in Android's
non-backed-up `noBackupFilesDir` or desktop's `~/.spirit2` directory. These must
not overlap: Spirit rejects equal or nested node/store directories. The older
AFM prototype store remains separate. Desktop group catalogs use
`~/.spirit2/afm-groups/groups/<meshId>`.

## Group files

On Android choose **Add file** to open the system document picker; AFM streams the chosen document into its verified local store. On Linux desktop the native file dialog imports the selected path. Canceling a picker does nothing. Each group has a flat list with sizes, author devices and local availability. Files you add are visible to all current and future group members; AFM neither scans the filesystem nor uploads unrelated files. Removing an entry requires confirmation and stops advertising it, but removal is not erasure: members who downloaded the bytes keep their copies. Download, open and save are not available in this first files increment.
