# AFM agent rules

The active app is `kmp/`; `bevy/` and `godot/` are prototypes. Read the root README before changing builds.

Code carries no comment lines. Put explanations in README files or `wiki/design/` and use precise names in source.

`deps/spirit2` is an independent pinned Git submodule. Inspect its branch and dirty state before updating it. Push dependency changes before changing the pin; never force an update over another developer's work.

Main builds use self-hosted Woodpecker, not GitHub Actions. Infrastructure configuration and credentials belong in Atlas, not this repository. Keep build scripts independent of private hostnames and never commit signing keys or tokens.

`ci/setup-linux.sh` is intended for a disposable Ubuntu container running as root. Do not run it directly on a developer host. `ci/build-release.sh` requires JDK 25 and an initialized dependency. Native output must remain in `deps/spirit2/rust/target`, because the pinned SDK packages its native library from that path.

An unsigned release APK cannot be installed. Do not add a debug signing fallback or imply that the current artifact is signed.
