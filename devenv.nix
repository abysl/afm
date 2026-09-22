{pkgs, lib, ...}: let
  kmp = import ./kmp/devenv.nix {inherit pkgs lib;};
  delegate = child: name: description: {
    inherit description;
    exec = ''
      set -eu
      cd "$DEVENV_ROOT/${child}"
      exec devenv shell -- ${lib.escapeShellArg name} "$@"
    '';
  };
  isPublicKmpScript = name: lib.any (prefix: lib.hasPrefix prefix name) ["run-" "release-" "test-"];
  kmpScripts = lib.mapAttrs (name: script: delegate "kmp" name script.description) (lib.filterAttrs (name: _: isPublicKmpScript name) kmp.scripts);
in {
  cachix.enable = false;

  enterShell = ''
    echo "AFM root shell: run-bevy, run-godot, run-desktop, and child run/release/test commands"
  '';

  scripts = kmpScripts // {
    run-bevy = delegate "bevy" "run-bevy" "Run the Bevy AFM shell";
    test-bevy = delegate "bevy" "test-bevy" "Test the Bevy AFM shell";
    release-bevy = delegate "bevy" "release-bevy" "Build the Bevy AFM shell in release mode";
    run-godot = delegate "godot" "run-godot" "Run the Godot AFM project";
    edit-godot = delegate "godot" "edit-godot" "Open the Godot AFM project in the editor";
    test-godot = delegate "godot" "test-godot" "Run the bounded Godot headless smoke test";
  };
}
