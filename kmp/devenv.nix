{
  pkgs,
  lib,
  ...
}: let
  emulatorAbi =
    if pkgs.stdenv.hostPlatform.isAarch64
    then "arm64-v8a"
    else "x86_64";
  script = description: exec: {
    inherit description;
    exec = "set -eu\n" + exec;
  };
  skikoRuntimeLibs = with pkgs; [
    fontconfig
    freetype
    libglvnd
    libxkbcommon
    libx11
    libxext
    libxi
    libxrender
    libxtst
  ];
in {
  cachix.enable = false;

  android = {
    enable = true;
    platforms.version = ["36"];
    buildTools.version = ["36.0.0"];
    emulator.enable = true;
    systemImages.enable = true;
    systemImageTypes = ["google_apis"];
    abis = [emulatorAbi];
    ndk.enable = true;
    ndk.version = ["26.3.11579264"];
  };

  languages.java.jdk.package = pkgs.jdk25;
  languages.rust = {
    enable = true;
    channel = "stable";
    targets = ["aarch64-linux-android" "x86_64-linux-android"];
  };

  packages = with pkgs;
    [binaryen cargo-ndk nodejs_22 yarn]
    ++ lib.optionals pkgs.stdenv.hostPlatform.isLinux [chromium dpkg fakeroot]
    ++ skikoRuntimeLibs;

  env.LD_LIBRARY_PATH = lib.makeLibraryPath skikoRuntimeLibs;
  env.JAVA25_HOME = pkgs.jdk25.home;
  env.CHROME_BIN = lib.mkIf pkgs.stdenv.hostPlatform.isLinux "${pkgs.chromium}/bin/chromium";

  enterShell = ''
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
    export ANDROID_USER_HOME="$DEVENV_ROOT/.android"
    export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
    mkdir -p "$ANDROID_AVD_HOME"
    export JAVA_HOME="$JAVA25_HOME"
    export AFM_ANDROID_SYSTEM_IMAGE="''${AFM_ANDROID_SYSTEM_IMAGE:-system-images;android-36;google_apis;${emulatorAbi}}"
    echo "AFM: run-{desktop,cli,android,web} / release-{desktop,cli,android,web}; devenv info lists tests"
  '';

  scripts = {
    check-spirit = script "Check that the pinned Spirit2 dependency is available" ''
      if [ ! -f "$DEVENV_ROOT/../deps/spirit2/rust/Cargo.toml" ] || [ ! -f "$DEVENV_ROOT/../deps/spirit2/kmp/settings.gradle.kts" ]; then
        echo "Missing pinned Spirit2 dependency at $DEVENV_ROOT/../deps/spirit2; initialize it before building AFM." >&2
        exit 1
      fi
    '';

    prepare-native = script "Build Spirit2 release FFI, CLI, and matching UniFFI Kotlin bindings" ''
      check-spirit
      exec "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" afmGenerateBindings --no-configuration-cache "$@"
    '';

    generate-bindings = script "Compatibility alias for prepare-native" ''
      exec prepare-native "$@"
    '';
    jvm-native = script "Compatibility alias for prepare-native" ''
      exec prepare-native "$@"
    '';
    ios-native = script "Compatibility alias delegated to the Spirit2 KMP environment" ''
      check-spirit
      cd "$DEVENV_ROOT/../deps/spirit2/kmp"
      exec devenv shell -- ios-native "$@"
    '';
    android-native = script "Build Spirit2 JNI libraries for Android arm64 and x86_64" ''
      check-spirit
      exec "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" afmAndroidNative --no-configuration-cache "$@"
    '';

    run-desktop = script "Build native bindings and run the AFM desktop app" ''
      prepare-native
      exec "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" :app:desktopApp:run "$@"
    '';
    run-cli = script "Run the Spirit CLI; arguments are forwarded to the CLI" ''
      check-spirit
      export CARGO_TARGET_DIR="$DEVENV_ROOT/../deps/spirit2/rust/target"
      cd "$DEVENV_ROOT/../deps/spirit2/rust"
      if [ "$#" -eq 0 ]; then set -- --help; fi
      exec cargo run --locked -p spirit-cli -- "$@"
    '';
    run-android = script "Build, select a device or boot an emulator, install, and launch AFM" ''
      build-android
      exec "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" afmRunAndroid --no-configuration-cache "$@"
    '';
    run-web = script "Run the JS stub UI with continuous rebuild and browser reload" ''
      check-spirit
      exec "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" :app:webApp:jsBrowserDevelopmentRun --continuous "$@"
    '';
    run-web-wasm = script "Run the Wasm stub UI with continuous rebuild and browser reload" ''
      check-spirit
      exec "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" :app:webApp:wasmJsBrowserDevelopmentRun --continuous "$@"
    '';

    build-android = script "Build the debug APK with both native Android ABIs" ''
      prepare-native
      android-native
      "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" :app:androidApp:assembleDebug "$@"
      echo "APK: $DEVENV_ROOT/app/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    '';
    install-android = script "Build and install the debug APK without launching AFM" ''
      build-android
      exec "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" afmInstallAndroid --no-configuration-cache "$@"
    '';

    release-desktop = script "Build a local desktop installer with bundled Java and Spirit2" ''
      prepare-native
      "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" :app:desktopApp:packageDistributionForCurrentOS "$@"
      echo "Installers: $DEVENV_ROOT/app/desktopApp/build/compose/binaries/main/"
    '';
    release-cli = script "Build and archive the local release Spirit CLI" ''
      check-spirit
      export CARGO_TARGET_DIR="$DEVENV_ROOT/../deps/spirit2/rust/target"
      cd "$DEVENV_ROOT/../deps/spirit2/rust"
      cargo build --locked --release -p spirit-cli "$@"
      output="$DEVENV_ROOT/build/release/cli/spirit-$(uname -s)-$(uname -m).tar.gz"
      mkdir -p "$(dirname "$output")"
      tar -czf "$output" -C "$CARGO_TARGET_DIR/release" spirit
      echo "CLI archive: $output"
    '';
    release-android = script "Build local release APK and AAB; unsigned unless signing is configured" ''
      prepare-native
      android-native
      "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" :app:androidApp:assembleRelease :app:androidApp:bundleRelease "$@"
      echo "APK: $DEVENV_ROOT/app/androidApp/build/outputs/apk/release/"
      echo "AAB: $DEVENV_ROOT/app/androidApp/build/outputs/bundle/release/"
    '';
    release-web = script "Build local static JS and Wasm stub UI distributions" ''
      check-spirit
      "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" :app:webApp:jsBrowserDistribution :app:webApp:wasmJsBrowserDistribution "$@"
      echo "Websites: $DEVENV_ROOT/app/webApp/build/dist/{js,wasmJs}/productionExecutable/"
    '';

    test-rust = script "Test the full Spirit2 Rust workspace" ''
      check-spirit
      export CARGO_TARGET_DIR="$DEVENV_ROOT/../deps/spirit2/rust/target"
      cd "$DEVENV_ROOT/../deps/spirit2/rust"
      exec cargo test --workspace --all-features --locked "$@"
    '';
    test-cli = script "Test the Spirit CLI against the Rust core" ''
      check-spirit
      export CARGO_TARGET_DIR="$DEVENV_ROOT/../deps/spirit2/rust/target"
      cd "$DEVENV_ROOT/../deps/spirit2/rust"
      exec cargo test --locked -p spirit-cli "$@"
    '';
    test-sdk = script "Test the Spirit2 Kotlin SDK against generated native bindings" ''
      prepare-native
      exec "$DEVENV_ROOT/../deps/spirit2/kmp/gradlew" -p "$DEVENV_ROOT/../deps/spirit2/kmp" :sdk:jvmTest "$@"
    '';
    test-desktop = script "Run the AFM desktop native storage roundtrip on the JVM" ''
      prepare-native
      exec "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" :app:shared:jvmTest "$@"
    '';
    test-android = script "Run AFM native storage instrumentation on a device or emulator" ''
      prepare-native
      android-native
      device_file=$(mktemp "$DEVENV_STATE/afm-device.XXXXXX")
      trap 'rm -f "$device_file"' EXIT
      "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" afmSelectAndroid "-PafmDeviceOutput=$device_file" --no-configuration-cache
      ANDROID_SERIAL=$(cat "$device_file")
      export ANDROID_SERIAL
      "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" :app:shared:connectedAndroidTest "$@"
    '';
    test-web = script "Test JS and Wasm stub UI boundaries in headless Chrome" ''
      check-spirit
      exec "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" :app:shared:jsBrowserTest :app:shared:wasmJsBrowserTest "$@"
    '';
    test-launcher = script "Run Kotlin Android launcher unit tests without a device" ''
      exec "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT/build-logic" test --tests afm.delivery.AndroidLauncherTest --no-configuration-cache "$@"
    '';
    test-aliases = script "Test generated root and KMP command dispatch through Kotlin mocks" ''
      exec "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT/build-logic" test --tests afm.delivery.DevenvAliasesTest --no-configuration-cache "$@"
    '';
    test-plumbing = script "Verify Rust, SDK, desktop, launcher, and alias plumbing; excludes device, browser, and packages" ''
      test-rust
      test-sdk
      test-desktop
      test-launcher
      test-aliases
    '';

    unit-test = script "Compatibility alias for test-desktop" ''exec test-desktop "$@"'';
    jvm-test = script "Compatibility alias for test-desktop" ''exec test-desktop "$@"'';
    desktop = script "Compatibility alias for run-desktop" ''exec run-desktop "$@"'';
    apk = script "Compatibility alias for build-android" ''exec build-android "$@"'';
    install = script "Compatibility alias for install-android" ''exec install-android "$@"'';
    assemble = script "Build Android, desktop, JS, and Wasm development outputs" ''
      prepare-native
      android-native
      exec "$DEVENV_ROOT/gradlew" -p "$DEVENV_ROOT" :app:androidApp:assembleDebug :app:desktopApp:assemble :app:webApp:jsBrowserDistribution :app:webApp:wasmJsBrowserDistribution "$@"
    '';
  };
}
