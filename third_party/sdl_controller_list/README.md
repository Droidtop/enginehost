# SDL controller list

Which glyph family a pad's face buttons print (Xbox ABXY, PlayStation cross/circle/square/triangle,
Nintendo with A on the right), from its USB or Bluetooth vendor and product id. Enginehost draws a
pad's glyphs by it (`PadFamilies`, docs/SPEC.md "Controls are drawn as glyphs").

- Upstream: SDL's `src/joystick/controller_list.h` (<https://github.com/libsdl-org/SDL>, branch SDL2),
  Valve's table of controller ids by type, which SDL's `SDL_GetJoystickGameControllerTypeFromVIDPID`
  classifies through.
- Licence: zlib, Copyright (C) Valve Corporation. The text is in `LICENSE` beside this file.
- Taken from droidtop, which generated `SdlControllerIds.kt` from SDL commit
  `4c2d9014afda49553c76f7045529207bb593f9b5` and groups the types into the three families the same
  way. Enginehost carries its own copy; it never assumes droidtop is installed.

## What is shipped

`app/src/main/kotlin/dev/enginehost/SdlControllerIds.kt`: the ids only, grouped by family. Generated,
not edited by hand. The rules SDL applies before and around the table (vendor 0, Amazon Luna, Stadia,
the NVIDIA Shield, the NES pad that shares a Joy-Con's id) are in `PadFamilies.classify`.

## Updating it

```sh
curl -fsSL -o /tmp/controller_list.h \
  https://raw.githubusercontent.com/libsdl-org/SDL/<commit>/src/joystick/controller_list.h
python3 third_party/sdl_controller_list/gen_sdl_controller_families.py /tmp/controller_list.h <commit> \
  > app/src/main/kotlin/dev/enginehost/SdlControllerIds.kt
```
