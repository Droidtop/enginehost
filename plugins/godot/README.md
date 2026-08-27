# Godot 4.7.1 plugin

This separately installable enginehost plugin embeds the official Godot Android
library `org.godotengine:godot:4.7.1.stable`. It accepts a live game folder and
runs either an unpacked `project.godot` project or a selected `.pck`/`.zip`
through Godot's documented `--path` / `--main-pack` command line.

The capability is intentionally exact. Godot 3.x and other 4.x runtime builds
belong in co-installable plugin versions with their own explicit declarations.

Godot is copyright its contributors and licensed under MIT. The Maven artifact
retains upstream notices; see <https://godotengine.org/license/> and the
[official Android-library documentation](https://docs.godotengine.org/en/stable/tutorials/platform/android/android_library.html).

No game files are copied. Normal Android filesystem sandboxing applies until a
storage-access policy is explicitly approved. Builds run only in CI.
