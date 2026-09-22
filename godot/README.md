# AFM Godot

This is the first Godot feature-flow shell for Abysl File Manager.

Enter with `devenv shell`, then use `run-godot` to run the configured main scene
or `edit-godot` to open the project in Godot 4. `test-godot` imports the project
and runs its main scene headlessly for at most 30 seconds.

There is no `release-godot` command because this project has no
`export_presets.toml`. Add an explicit export preset before defining a local
Godot export workflow.
