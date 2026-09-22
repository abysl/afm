# AFM delivery build logic

The included `afm.delivery` Gradle plugin owns the delivery task graph. Plain
Kotlin classes own Git provenance, version rules, Android signing, manifest
validation, and Forgejo publication. The tests use temporary Git repositories,
fake signing commands, and a loopback HTTP registry; they need no production
credentials.

From `kmp/`:

```sh
devenv shell -- ./gradlew -p build-logic test --no-configuration-cache
devenv shell -- ./gradlew afmPrepareNative --no-configuration-cache
devenv shell -- ./gradlew afmCiBuild -PafmRelease=true --dry-run --no-configuration-cache
```

The dry run only inspects the task graph. Actual CI tasks require trusted-main
metadata, exact clean source, full history, and the pinned Spirit2 checkout.
Native preparation and the consuming KMP build deliberately use separate Gradle
invocations; there is no nested Gradle process in a task.

An optional integration test signs a real built version-42 APK with a disposable
one-day key, verifies it, and deletes the key/output. Build the fixture first:

```sh
AFM_VERSION_CODE=42 AFM_VERSION_NAME=0.1.42 devenv shell -- release-android
AFM_SIGNING_SMOKE_APK="$PWD/app/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk" devenv shell -- ./gradlew -p build-logic test --no-configuration-cache
```

That disposable identity is never a release key and is never published. Without
`AFM_SIGNING_SMOKE_APK`, the integration test is explicitly skipped.

See the [delivery design](../../wiki/design/delivery.md) for the task sequence,
secret configuration, artifact schema, retry/promotion behavior, and the later
GitHub Releases/Obtainium plan. AndroidLauncherTest and DevenvAliasesTest replace
the former development-launcher/alias scripts too. They use fake adb/emulator
processes and isolated command fixtures; no phone is needed for unit tests.
`devenv shell -- test-launcher` and `devenv shell -- test-aliases` run those
suites individually. The alias suite evaluates actual devenv scripts and requires
devenv; it reports a skip rather than false success if devenv is unavailable.
