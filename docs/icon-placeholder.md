# App icon: placeholder in place, real art still needed

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (and `ic_launcher_round.xml`) define an
adaptive icon from three vector layers under `app/src/main/res/drawable/`:

- `ic_launcher_background.xml` -- flat `@color/eh_accent` fill, full 108x108dp canvas.
- `ic_launcher_foreground_placeholder.xml` -- a plain "EH" block mark, PLACEHOLDER.
- `ic_launcher_monochrome_placeholder.xml` -- the same mark as the Android 13+ themed-icon
  (monochrome) layer, PLACEHOLDER.

`app/src/main/res/values/ic_launcher_aliases.xml` aliases `ic_launcher_foreground` and
`ic_launcher_monochrome` to those two placeholder drawables. minSdk is 26, so this
`mipmap-anydpi-v26` adaptive icon is the only launcher-icon resource the app ships; there is no
legacy PNG fallback to also update.

## Replacing the placeholder

Drop the real foreground and monochrome art in as `ic_launcher_foreground_real.xml` (or a raster
`ic_launcher_foreground_real.png` set across the `drawable-*dpi` buckets) and repoint the two
`<item>` lines in `ic_launcher_aliases.xml` at them -- nothing else has to change. Or replace the
two `*_placeholder.xml` files in place, same names, same viewport.

Rules the real art has to follow (adaptive icon spec):
- Each layer is drawn on a 108x108dp canvas; the system crops it to whatever mask shape the
  launcher uses (circle, squircle, rounded square, ...).
- Keep all meaningful content inside the safe zone: a 66dp-diameter circle centred on the canvas
  (66/108 of the full size, centred). Anything outside that can be clipped on some launchers.
- The monochrome/themed layer must be a single silhouette (opaque shape, transparent elsewhere) --
  the system supplies its own tint, so its own fill colour does not matter, only its alpha shape.
- If shipping raster PNGs instead of vectors, the legacy sizes are 48/72/96/144/192px
  (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi) for a flat icon, or the same buckets at the adaptive 108x108dp
  canvas size for the two adaptive layers.

See `docs/DESIGN-LANGUAGE.md` (global) for the token names (`eh_accent`, `eh_on_accent`, ...) the
placeholder pulls from; real art should use the same tokens rather than new literals unless the
new mark needs its own colour, in which case add it to `res/values/colors.xml` under the existing
`eh_` naming.
