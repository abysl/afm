set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/environment.sh"

test "$(uname -sm)" = 'Linux x86_64'
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates curl git unzip python3 build-essential pkg-config libssl-dev \
  clang cmake protobuf-compiler fakeroot dpkg-dev file binutils \
  libfontconfig1 libfreetype6 libgl1 libx11-6 libxi6 libxrender1 libxtst6 libxkbcommon0

mkdir -p "$AFM_ROOT/.ci" "$ANDROID_HOME/cmdline-tools"
curl --fail --silent --show-error --location --retry 3 \
  https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-gnu/rustup-init \
  -o "$AFM_ROOT/.ci/rustup-init"
chmod +x "$AFM_ROOT/.ci/rustup-init"
"$AFM_ROOT/.ci/rustup-init" -y --no-modify-path --profile minimal --default-toolchain 1.98.1
rustup target add --toolchain 1.98.1 aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked

curl --fail --silent --show-error --location --retry 3 \
  https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
  -o "$AFM_ROOT/.ci/android-tools.zip"
printf '%s  %s\n' \
  7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee \
  "$AFM_ROOT/.ci/android-tools.zip" | sha256sum --check
if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
  unzip -q "$AFM_ROOT/.ci/android-tools.zip" -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
python3 -c 'print("y\n" * 100)' | sdkmanager --licenses > /dev/null
sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'ndk;26.3.11579264'
