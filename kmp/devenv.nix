{
  pkgs,
  lib,
  ...
}: let
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
    platforms.version = ["34" "36" "37"];
    buildTools.version = ["34.0.0" "36.0.0"];
    ndk.enable = true;
    ndk.version = ["26.3.11579264"];
  };

  languages.rust = {
    enable = true;
    channel = "stable";
    targets = [
      "aarch64-linux-android"
      "x86_64-linux-android"
      "aarch64-apple-ios"
      "aarch64-apple-ios-sim"
    ];
  };

  packages = with pkgs;
    [
      binaryen
      cargo-ndk
      jdk25
      nodejs_22
      yarn
    ]
    ++ skikoRuntimeLibs;

  env.LD_LIBRARY_PATH = lib.makeLibraryPath skikoRuntimeLibs;

  enterShell = ''
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
    export JAVA25_HOME="${pkgs.jdk25.home}"
    export JAVA_HOME="$JAVA25_HOME"
    echo "afm dev env — android sdk at $ANDROID_HOME, jdk 25"
  '';

  scripts."generate-bindings".exec = ''
    set -eu
    cd "$DEVENV_ROOT/../deps/spirit2/kmp"
    devenv shell -- generate-bindings
  '';

  scripts."jvm-native".exec = ''
    set -eu
    cd "$DEVENV_ROOT/../deps/spirit2/kmp"
    devenv shell -- jvm-native
  '';

  scripts."android-native".exec = ''
    set -eu
    cd "$DEVENV_ROOT/../deps/spirit2/kmp"
    devenv shell -- android-native
  '';

  scripts."ios-native".exec = ''
    set -eu
    cd "$DEVENV_ROOT/../deps/spirit2/kmp"
    devenv shell -- ios-native
  '';

  scripts."unit-test".exec = ''
    set -eu
    generate-bindings
    jvm-native
    "$DEVENV_ROOT"/gradlew -p "$DEVENV_ROOT" :app:shared:jvmTest
  '';

  scripts."desktop".exec = ''
    set -eu
    generate-bindings
    jvm-native
    "$DEVENV_ROOT"/gradlew -p "$DEVENV_ROOT" :app:desktopApp:run
  '';

  scripts."apk".exec = ''
    set -eu
    generate-bindings
    jvm-native
    android-native
    "$DEVENV_ROOT"/gradlew -p "$DEVENV_ROOT" :app:androidApp:assembleDebug
    echo "apk at $DEVENV_ROOT/app/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
  '';

  scripts."install".exec = ''
    set -eu
    apk
    adb install -r "$DEVENV_ROOT"/app/androidApp/build/outputs/apk/debug/androidApp-debug.apk
  '';

  scripts."assemble".exec = ''
    set -eu
    generate-bindings
    jvm-native
    android-native
    "$DEVENV_ROOT"/gradlew -p "$DEVENV_ROOT" build
  '';

  scripts."ide-gradle-props".exec = ''
    set -eu
    mkdir -p "$HOME/.gradle"
    cat > "$HOME/.gradle/gradle.properties" <<EOF
org.gradle.java.installations.paths=$JAVA25_HOME
android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/36.0.0/aapt2
EOF
    echo "wrote $HOME/.gradle/gradle.properties"
  '';
}
