AFM_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export AFM_ROOT
export CARGO_HOME="$AFM_ROOT/.ci/cargo"
export RUSTUP_HOME="$AFM_ROOT/.ci/rustup"
export GRADLE_USER_HOME="$AFM_ROOT/.ci/gradle"
export ANDROID_HOME="$AFM_ROOT/.ci/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.3.11579264"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export JAVA25_HOME="${JAVA_HOME:?JDK 25 is required}"
export PATH="$CARGO_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
export CARGO_BUILD_JOBS=4
export CARGO_INCREMENTAL=0
export CARGO_PROFILE_DEV_DEBUG=0
export CARGO_PROFILE_TEST_DEBUG=0
export CARGO_TARGET_DIR="$AFM_ROOT/deps/spirit2/rust/target"
if command -v google-chrome >/dev/null 2>&1; then
  export CHROME_BIN="$(command -v google-chrome)"
fi
if [ "$(id -u)" = 0 ] && { [ -f /.dockerenv ] || [ -f /run/.containerenv ]; }; then
  export AFM_CI_CONTAINER=1
else
  export AFM_CI_CONTAINER=0
fi
