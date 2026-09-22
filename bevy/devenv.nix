{pkgs, lib, ...}: let
  bevyRuntimeLibs = with pkgs; [
    alsa-lib
    libGL
    libX11
    libXcursor
    libXi
    libXinerama
    libXrandr
    libxkbcommon
    udev
    vulkan-loader
    wayland
  ];
  script = description: exec: {
    inherit description;
    exec = "set -eu\n" + exec;
  };
in {
  cachix.enable = false;

  packages = with pkgs; [cargo clippy rust-analyzer rustc rustfmt pkg-config] ++ bevyRuntimeLibs;

  env.LD_LIBRARY_PATH = lib.makeLibraryPath bevyRuntimeLibs;

  scripts = {
    run-bevy = script "Run the Bevy AFM shell" ''
      exec cargo run --locked -- "$@"
    '';
    test-bevy = script "Test the Bevy AFM shell" ''
      exec cargo test --locked "$@"
    '';
    release-bevy = script "Build the Bevy AFM shell in release mode" ''
      exec cargo build --locked --release "$@"
    '';
  };
}
