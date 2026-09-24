# Abysl File Manager

AFM is a Compose Multiplatform file manager backed by Spirit2. The active app is
`kmp/`; `bevy/` and `godot/` are independent feature-flow prototypes. The current
app demonstrates native content-addressed storage and
[QR mesh pairing](kmp/app/README.md#qr-mesh-pairing) on Android and desktop.
The first cross-device file-manager UX and real file transfer remain on the
[project roadmap](plans/first-file-transfer/plan.md).

## Checkout

```sh
git clone --recurse-submodules https://github.com/abysl/afm.git
cd afm
```

`deps/spirit2` is an independent Git submodule pinned to an exact SDK/native
revision. After changing an AFM revision, inspect dependency state before running
`git submodule update --init -- deps/spirit2`. Never reset an active dependency
checkout or replace its pin with a moving branch implicitly.

## One development shell

From the repository root, enter `devenv shell` or enable direnv with `direnv allow`.
Each command enters its own child environment with the correct project directory. Web remains a local UI-only experiment and is not part of the signed prerelease channel:

```sh
devenv shell -- run-desktop
devenv shell -- run-android
devenv shell -- run-cli --help
devenv shell -- run-web
devenv shell -- run-bevy
devenv shell -- run-godot
devenv shell -- test-plumbing
devenv shell -- release-desktop
```

The root derives KMP's public run/test/release commands from its
[command matrix](kmp/README.md#run-test-release-matrix). Bevy and Godot also
provide `test-bevy`, `release-bevy`, `edit-godot`, and `test-godot`. Godot release
exports need an export preset and are not configured. Browser builds are UI-only:
the current UniFFI/JNA native store is available on JVM desktop and Android.

## Build and release ownership

Gradle Kotlin DSL and tested Kotlin JVM build logic own native preparation,
Android launching, tests, versioning, signing, manifests, and artifact publication.
Cargo owns Rust builds; UniFFI generates the SDK bindings. There is no Python
build or release path. See [build-logic](kmp/build-logic/README.md).

Normal development versions remain `1` / `0.1.0`. Trusted CI development prereleases use the
checked-in `release-version-base.txt` plus all reachable AFM commits, so extracting
source history cannot lower Android version codes. Do not lower that base or
rewrite published release history. Release manifests identify this repository's
commit as `source.afm_commit` and use schema 2.

Release aliases produce local artifacts, not automatic public releases. Android
release APKs are unsigned until explicitly processed by the protected signing task;
there is no debug-key fallback. Keep one backed-up signing identity for upgrades.

The delivery path is GitHub main → a private Forgejo pull mirror →
self-hosted Woodpecker → versioned signed GitHub prereleases. No GitHub Actions
runner is required. Every successful non-superseded main build publishes immutable
`v0.1.N` prerelease Android and Linux assets after draft upload/verification; stale
and partial builds never advance the testing channel. Obtainium sorts the numeric
tags with prerelease support enabled. Web artifact publication is deferred until
both browser targets are functionally useful and pass their own CI acceptance. Deployment configuration and credentials remain outside this
repository. See the [delivery design](wiki/design/delivery.md) for exact version,
asset, retry, and phone-update contracts.

## Disposable Linux CI environment

`ci/setup-linux.sh` prepares a disposable Ubuntu root container with JDK 25
already installed, for example `eclipse-temurin:25-jdk-noble`. Do not run this
script directly on a developer host: it installs system packages and accepts
Android SDK licenses inside the container.

From an initialized checkout:

```sh
docker run --rm --shm-size=1g -v "$PWD:/workspace" -w /workspace \
  eclipse-temurin:25-jdk-noble \
  bash -c 'bash ci/setup-linux.sh && source ci/environment.sh && bash kmp/gradlew -p kmp afmPrepareNative --no-configuration-cache'
```

Continue with the Gradle test/package tasks described in the command matrix.
Protected delivery tasks require trusted-main CI metadata and signing/publication
secrets; the example does not publish anything. Build portable Linux installers
in the disposable distribution environment rather than assuming a runtime linked
to a developer's Nix store will work on another machine. Container output is
owned by root; caches/toolchains live under ignored `.ci/` and build directories.

## Layout

| Directory | Role |
| --- | --- |
| `kmp/` | Active Kotlin Multiplatform app and Gradle build logic |
| `deps/spirit2/` | Pinned SDK and Rust core dependency |
| `ci/` | Thin disposable-container environment bootstrap |
| `bevy/` | Bevy feature-flow prototype |
| `godot/` | Godot feature-flow prototype |
| `wiki/design/` | Current architecture and release contracts |
| `plans/` | Project implementation roadmap |
