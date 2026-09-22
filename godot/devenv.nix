{pkgs, ...}: let
  script = description: exec: {
    inherit description;
    exec = "set -eu\n" + exec;
  };
in {
  cachix.enable = false;

  packages = with pkgs; [coreutils godot_4];

  scripts = {
    run-godot = script "Run the Godot project" ''
      exec godot --path "$DEVENV_ROOT" "$@"
    '';
    edit-godot = script "Open the Godot project in the editor" ''
      exec godot --editor --path "$DEVENV_ROOT" "$@"
    '';
    test-godot = script "Import and run a bounded headless Godot smoke test" ''
      timeout --foreground 30s godot --headless --path "$DEVENV_ROOT" --import
      exec timeout --foreground 30s godot --headless --path "$DEVENV_ROOT" --quit-after 60 "$@"
    '';
  };
}
