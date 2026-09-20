# Abysl File Manager

AFM is a Compose Multiplatform file manager backed by Spirit2. The active app lives in `kmp/`; `bevy/` and `godot/` are earlier feature-flow prototypes, not release targets.

## Checkout

```sh
git clone --recurse-submodules git@github.com:abysl/afm.git
cd afm
```

`deps/spirit2` pins the SDK and native library to an exact commit. Both repositories currently require GitHub access. After pulling changes, run `git submodule update --init --recursive`; do not replace the pin with the latest SDK branch.

## Develop

With Nix and devenv installed:

```sh
cd kmp
direnv allow
unit-test
desktop
```

The development environment generates UniFFI bindings and builds the native library before launching. See [the app guide](kmp/app/README.md) for individual Gradle targets.

## Main builds

A private Forgejo pull mirror follows GitHub `main`. Woodpecker compiles and tests on self-hosted Linux runners, then publishes a GitHub prerelease named for the full source commit. No GitHub Actions runner is used.

The release is kept as a draft until every asset and its checksum has uploaded successfully. Downloads are available on [GitHub Releases](https://github.com/abysl/afm/releases), not the GitHub Actions artifact store.

| Asset | Contents |
|---|---|
| `afm-linux-x86_64.deb` | Linux x86-64 desktop installer with a bundled Java runtime; built on Ubuntu 24.04 |
| `afm-android-unsigned.apk` | Release APK with ARM64 and x86-64 native libraries; **must be signed before installation** |
| `afm-web.tar.gz` | `js/` and `wasmJs/` static web distributions |
| `SHA256SUMS` | SHA-256 checksums for the three downloads |

Web and iOS currently expose the UI without the native store. macOS, Windows, and iOS packages are not part of this Linux pipeline. Main builds are development prereleases, not stable versioned releases.

The pipeline runs shared JVM tests, which load the native Spirit library, before packaging. Packaging also verifies both Android native libraries and both web entry points.

## Reproduce the Linux build

Use the same `eclipse-temurin:25-jdk-noble` container as CI, from an initialized checkout:

```sh
docker run --rm -v "$PWD:/workspace" -w /workspace \
  eclipse-temurin:25-jdk-noble \
  bash -c 'bash ci/setup-linux.sh && bash ci/build-release.sh'
```

This installs build tools inside the container and keeps downloaded toolchains and build output under the ignored `.ci/`, dependency build directories, and `dist/`. Container output is owned by root. The setup accepts the Android SDK licenses for the container; review those licenses before running it.

Rust 1.98.1, cargo-ndk 4.1.2, Android API 36, and NDK 26.3.11579264 match the pinned SDK. Gradle downloads its own web tools outside devenv. Native Linux packaging deliberately uses Ubuntu rather than Nix so the shipped runtime does not depend on a runner's `/nix/store`.

Run lightweight packaging regression tests without the compiler toolchains:

```sh
python3 -m unittest discover -s ci -p '*_test.py'
```

AFM is organized by implementation dependency while the feature flows are being explored.

| Directory | Role |
|---|---|
| `godot/` | Godot hello world |
| `bevy/` | Bevy hello world |
| `kmp/` | Kotlin Multiplatform client backed by Spirit2 |

Each implementation is an independent starting point for exploring the same asset-management workflows.
