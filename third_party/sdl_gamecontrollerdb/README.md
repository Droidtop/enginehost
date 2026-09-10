# SDL_GameControllerDB

Seed data for Enginehost's controller PROFILE layer: the community database
that says, for a great many pads, which of the buttons and axes a platform
reports is actually the A button, the left stick, the left trigger.

- Upstream: <https://github.com/mdqinc/SDL_GameControllerDB>
- Licence: zlib. The full text is in `LICENSE` beside this file.
- Vendored: 2026-09-10, from `master`.

## What is shipped

`app/src/main/assets/gamecontrollerdb-android.txt` holds the Android-platform
entries only, 299 of the roughly 3,700 lines upstream carries. Every other
line names a platform Enginehost does not run on, so it is 74 KB of data that
can apply instead of 1.8 MB that mostly cannot. Nothing else is changed: the
lines are verbatim upstream lines, in upstream order.

## Updating it

From the repository root:

```sh
curl -fsSL -o /tmp/gamecontrollerdb.txt \
  https://raw.githubusercontent.com/mdqinc/SDL_GameControllerDB/master/gamecontrollerdb.txt
curl -fsSL -o third_party/sdl_gamecontrollerdb/LICENSE \
  https://raw.githubusercontent.com/mdqinc/SDL_GameControllerDB/master/LICENSE
grep 'platform:Android,' /tmp/gamecontrollerdb.txt \
  > app/src/main/assets/gamecontrollerdb-android.txt
```

Then update the vendored date above, and read the diff: this file decides what
a pad does out of the box, so a changed line for a pad someone owns is a
behaviour change and belongs in the commit message.

`ControllerProfiles.kt` parses it. The format is one line per pad: a 32
hex-character GUID, a display name, then `standard:target` pairs where a
target is `bN` (button index N), `aN` (axis index N, optionally signed or
followed by `~` for an inverted axis) or `hN.M` (hat N, direction M), and a
final `platform:` tag.

## How much of it Enginehost uses, and why not more

Matching is by device name, which is what both SDL and RetroArch fall back to
on Android and the only key that is stable there: SDL's Android GUIDs come
from several different eras of SDL and encode the name, or a CRC of the
descriptor, or vendor and product, depending on which version generated the
entry, so a GUID comparison would silently match the wrong pad. Where several
entries share a name and disagree, Enginehost applies none of them rather than
guessing.

Only the parts of an entry that can contradict Android are used. Android
already resolves a pad's raw HID codes through its own key-layout files, so
for most pads the entry agrees with what Android reports and translates into
an empty profile that costs nothing. What is read is the face, shoulder,
stick-click, Start, Back and Guide buttons (`bN`), and the stick and trigger
axes (`aN`). What is skipped, deliberately:

- `hN.M` hat targets and signed-axis d-pad targets. The d-pad is Android's
  own business; it delivers `KEYCODE_DPAD_*` for a hat already.
- A trigger given as a button, or a face button given as an axis. Enginehost's
  own binding for one is an axis and for the other a key, and turning one into
  the other is a different job from correcting which control is which.
- Any entry whose translation is not one-to-one, which would mean two physical
  controls claiming the same standard button.
