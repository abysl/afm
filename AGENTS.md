# AFM agent rules

The active app is `kmp/`; `bevy/` and `godot/` are prototypes. Read the root
README and the relevant design document before changing builds.

Code carries no comment lines. Put explanations in README files or
`wiki/design/` and use precise names in source.

New build, CI, signing, and delivery logic belongs in Gradle Kotlin DSL and
unit-tested Kotlin JVM build logic. Cargo owns Rust compilation and tests.
Do not add a parallel Python or shell build orchestrator. Bash is limited to
unavoidable environment/bootstrap glue and thin command forwarding.

`deps/spirit2` is an independent pinned Git submodule. Inspect its branch and
dirty state before updating it. Publish dependency commits before changing the
pin; never force an update over another developer's work. Native output stays
in `deps/spirit2/rust/target`, which the pinned SDK uses for JNA resources.

Native preparation precedes the consuming KMP composite-build invocation.
Keep signing/publication separate from compilation. Read credentials only at
task execution, disable configuration caching for protected delivery tasks,
and never put keys/passwords in task properties, build artifacts, or logs.

`release-version-base.txt` preserves Android update ordering across source-history
extraction. Do not lower it or switch to first-parent/pipeline-attempt counting.
Schema-1 delivery manifests are not standalone AFM provenance and must not be
silently reused as schema 2 or used to reset signing/version continuity.

`ci/setup-linux.sh` is only for a disposable Ubuntu container running as root,
not a developer host. Infrastructure configuration, hostnames, credentials, and
operator-specific deployment records do not belong in this application repository.
Use self-hosted CI, not GitHub Actions runners, for release builds.

An unsigned release APK is not an installable release. Do not add a debug-signing
fallback. A build is not proof of installed-runtime portability or live publication;
record validation limits explicitly.
