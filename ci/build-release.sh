set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/environment.sh"
cd "$AFM_ROOT"

test "$(uname -sm)" = 'Linux x86_64'
expected_spirit=$(git rev-parse HEAD:deps/spirit2)
test "$(git -C deps/spirit2 rev-parse HEAD)" = "$expected_spirit"
python3 -m unittest discover -s ci -p '*_test.py'
python3 deps/spirit2/ci/native.py
android_min_sdk=$(python3 -c 'import tomllib; print(tomllib.load(open("kmp/gradle/libs.versions.toml", "rb"))["versions"]["android-minSdk"])')
(
  cd deps/spirit2/rust
  cargo ndk -t arm64-v8a -t x86_64 -P "$android_min_sdk" \
    -o ../kmp/sdk/src/androidMain/jniLibs \
    build --release --locked -p spirit-ffi
)
bash kmp/gradlew -p kmp \
  :app:shared:jvmTest \
  :app:desktopApp:packageDistributionForCurrentOS \
  :app:androidApp:assembleRelease \
  :app:webApp:jsBrowserDistribution \
  :app:webApp:wasmJsBrowserDistribution \
  --console=plain --no-daemon --max-workers=2
python3 ci/package-release.py
