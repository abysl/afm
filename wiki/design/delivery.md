# AFM testing-release delivery

## Scope

Build and verify Android and Linux desktop artifacts in private Woodpecker CI,
then publish them to Forgejo's Generic Package Registry. GitHub synchronization
and Obtainium setup are a later phase. No source code or credentials are copied
to GitHub by this implementation.

The implementation uses Gradle Kotlin DSL for the task graph and tested Kotlin
JVM build logic for source verification, signing, manifests, and publication.
Cargo owns Rust builds/tests; UniFFI generates the bindings. There is no Python
or Bash delivery orchestrator. Android launching and root/KMP alias tests are
also Kotlin/JUnit. AFM no longer owns or invokes Python scripts. Thin shell
wrappers only enter environments, forward commands, or hand a selected Android
serial to the next Gradle process.

## Task sequence

Run from `kmp/` in its devenv environment. The CI runner provides event metadata.

| Invocation | Responsibility |
| --- | --- |
| `./gradlew -p build-logic prepareSpirit2 --no-configuration-cache` | Initialize only the pinned Spirit2 checkout; reject dirty/mismatched existing checkouts |
| `./gradlew afmDeliveryPreflight -PafmRelease=true --no-daemon --no-configuration-cache` | Require trusted main, exact clean source/pin, full history, and delivery credentials |
| `./gradlew afmPrepareNative -PafmRelease=true --no-configuration-cache` | Build host Rust/UniFFI, generate Kotlin, build both Android native ABIs, record source context |
| `./gradlew afmCiBuild -PafmRelease=true --no-configuration-cache` | Test the delivery logic, Kotlin launcher/aliases, Rust, SDK, AFM JVM and browsers; build unsigned APK/AAB and Linux `.deb`; archive reports |
| `./gradlew afmDeliver -PafmRelease=true --no-daemon --no-configuration-cache` | Restore an existing verified bundle or sign/package locally, then publish and conditionally promote latest |

Native preparation is a separate invocation because the included SDK needs its
generated Kotlin/native resources before the consuming composite build executes.
The build context is checked again after tests/builds and before delivery.
Gradle is not recursively invoked inside a build task. The source checkout and
all developer aliases remain usable without CI variables. `test-android` uses
a temporary file to carry Kotlin's selected device serial into the instrumentation
process's `ANDROID_SERIAL`, which must exist when that Gradle invocation starts.

Delivery runs only for main push/manual events on a single trusted Linux builder.
It does not enable secret-bearing PR/fork jobs. Source checkout uses full history,
no recursive blanket submodule update, and the exact recorded Spirit2 gitlink.
Local devenv runs resolve tools through the checked-in lock/configuration. The
disposable Ubuntu bootstrap instead pins Rust, cargo-ndk, and Android SDK/NDK
inputs while installing distribution packages and current Google Chrome; it is
not a fully locked container image. Record tool versions with validation results.
Alias tests require devenv and explicitly skip when it is absent, including in
the minimal Ubuntu container. A green container suite alone is not alias validation.

## Version and signing contract

CI Android `versionCode` is the checked-in `release-version-base.txt` value plus
`git rev-list --count HEAD` in this AFM repository. `versionName` and Linux
package version are `1.0.<versionCode>`. The initial base is 1752, preserving an
update floor across the source-history extraction. Do not lower or remove it.
All reachable commits are counted, not only first-parent history: the latter can decrease even on an ordinary fast-forward.
Full Git history is required. Verification also requires the Git root to be the
AFM directory directly above `kmp`, never an enclosing repository. Retrying a commit retains its version. Rewriting
main history is not a supported release operation, and the publisher also refuses
to lower the remembered version floor.

Local development retains version code 1 and version name `1.0.0`. Supplying
`AFM_VERSION_CODE` and `AFM_VERSION_NAME` overrides both Android and desktop from
one validated definition; partial/malformed overrides fail. The application ID
stays `com.abysl.afm`.

Provide a dedicated, backed-up AFM signing identity as protected Woodpecker
repository secrets, limited to trusted main push/manual delivery:

| Environment input | Meaning |
| --- | --- |
| `AFM_KEYSTORE_BASE64` | Canonical single-line Base64 keystore |
| `AFM_KEYSTORE_PASSWORD` | Keystore password |
| `AFM_KEY_ALIAS` | Release private-key alias |
| `AFM_KEY_PASSWORD` | Private-key password |
| `AFM_FORGEJO_TOKEN` | Package write and source repository read access |

Do not commit a keystore, password, or token. The keystore is decoded into a
private system temporary directory, not the project, and is removed on success
and failure. `apksigner` receives password environment-variable names, never
password values in arguments. The signer verifies signature, certificate digest,
application ID, version, and both arm64-v8a/x86_64 Spirit libraries before exposing
an output. Signing failure never falls back to a debug signature.

Credentials are read at task execution, not captured in task properties. Signing
and publishing tasks have no cacheable outputs and are incompatible with Gradle's
configuration cache; CI explicitly disables that cache and uses `--no-daemon`
for the protected steps so credentials do not outlive the invocation in a reusable
Gradle daemon. The build/test step does not receive delivery secrets. Never add build scans or verbose credential dumps
to secret-bearing steps.

An existing debug-signed installation cannot be updated in place by a differently
signed release APK. Back up/export its data before any deliberate one-time switch.
CI and launcher scripts never uninstall the app automatically. All subsequent
release updates must retain the release key and increasing version code.

## Registry format

The deployment supplies `AFM_PACKAGE_BASE` and `AFM_REPOSITORY_API` for the same
HTTPS Forgejo origin. HTTP is allowed only for literal-loopback unit tests.
Redirects are rejected so credentials cannot be forwarded to another service.

Under the package base:

```text
<full-source-commit>/delivery.json
<full-source-commit>/<sha256>-afm-1.0.<count>-android.apk
<full-source-commit>/<sha256>-afm-1.0.<count>-linux-x86_64.deb
<full-source-commit>/<sha256>-test-reports.tar.gz
<full-source-commit>/<sha256>-checksums.txt
identity/android-signing.json
latest/delivery.json
```

`delivery.json` schema 2 records `source.afm_commit` and `source.spirit2_commit`, version
code/name, application ID, signing certificate SHA-256, and each logical artifact
name, media type, byte length, and SHA-256. Schema 1 with the former embedding
repository provenance is rejected explicitly; do not reuse its bundle or checkpoint
as standalone AFM state without an intentional operator migration. The remote artifact filename prepends
its hash to that logical name. The completion manifest is uploaded last, only
after every stored artifact has been downloaded/hashed to verify its bytes.

A full commit bundle is immutable. A conflicting existing object fails instead
of being deleted. Partial retries may produce different report or APK bytes;
content-addressed filenames let those retry without overwriting earlier partial
objects. Existing complete bundles are restored and verified instead of re-signed.
A valid local bundle can also be reused after an incomplete upload in the same
workspace. Orphaned partial artifacts may require later retention cleanup.

## Latest promotion and recovery

A runner-local file lock serializes publication. Main is checked under the lock
and again immediately before promotion; stale builds can publish immutable
bundles but cannot move `latest`. Signing continuity applies to stale builds too.

The immutable `identity/android-signing.json` pins the signing certificate.
A durable checkpoint beside the publication lock remembers the highest admitted
version and signer, independently of the mutable latest pointer. The default
lock is under `$XDG_CACHE_HOME/afm-delivery/` or `$HOME/.cache/afm-delivery/`;
`AFM_PUBLISH_LOCK` overrides it, and the checkpoint appends `.state.json`.
Keep this state when rotating or cleaning the runner. Do not publish concurrently
from different runners with independent locks/checkpoints.

Forgejo Generic Package Registry replacement is DELETE followed by PUT, not an
atomic operation. A failed promotion can leave `latest/delivery.json` absent,
but cannot erase the independent signing/version guards. Retry the same or a
newer verified release to complete promotion. If latest and the runner checkpoint
are both missing while an identity already exists, publication fails closed:
recover the checkpoint from the known-highest verified release under operator
review before retrying. Never delete the identity or invent a lower version to
make a retry pass. Immutable commit URLs remain available during a latest gap.

## Later phase: GitHub Releases and Obtainium

1. Use `abysl/afm` as the GitHub destination and choose the release channel. A testing
   pre-release channel is recommended initially; enable pre-releases in Obtainium.
2. Read the Forgejo latest manifest, then fetch its immutable commit manifest
   and signed APK. Verify source/version, certificate, size, and checksums.
3. Copy those exact APK bytes into a GitHub Release with the matching numeric
   version tag and versioned APK name. Do not rebuild or re-sign on GitHub.
4. Make synchronization idempotent: existing matching assets are reused;
   mismatched assets or a stale version fail rather than being overwritten.
5. Mirror only explicitly approved public assets. Private CI reports/logs,
   infrastructure, keystores, and source checkout contents stay private.
6. Point Obtainium at that GitHub repository's releases, not GitHub Actions
   run artifacts, which expire and are not the normal install/update source.
7. Install one release on a real phone, publish a newer signed version, and
   verify Obtainium updates in place without losing AFM data or pairing.

No GitHub synchronization, credentials, public release, or production signing-key
provisioning is implemented or performed by this change. Live Forgejo publication
also requires the configured secrets and a reviewed pipeline run.
