# A game's text: read aloud and translated by Enginehost

Droidtop/tracker#490. The owner, 2026-10-14: "TTS with per-character voices using various
services AND on-device generation (user selected), and MTL or automated translation. This
must be core-agnostic, meaning its a service provided TO the cores, and the cores must
present text and the like to be worked with, in a specific format".

So a core only reports the text its engine shows, in one format, `ehtext/1`. Enginehost does
the reading aloud and the translating, once, for every core. The design and the owner's
decisions on it are on #490 (design comment 6176583781, decisions 6176693957 and 6176721031):

- A translation is handed back to the engine to print in its own text box. Drawing it over
  the game is only the fallback for a core that cannot print it.
- The person chooses among several translators; none is imposed.
- Characters get distinct voices, so the on-device default is a voice engine with many
  speakers, with Android's own text-to-speech as one choice.
- Engines provide the speakers' names. Guessing a name from the text is only the fallback for
  a core whose engine has none.
- Enginehost lists default models with links and checksums, and the person can add their own.

## What is built

Built (2026-10-28): the format, the host calls, the host's intake with its caps, and reading aloud
with the device's own voices (below). Translation, the multi-speaker on-device voices and each
remaining core's emitter come in the slices listed on #490. CatSystem2 is the first core that sends
text.

## How a core sends its text

`EngineHost.emitText(String event)`: one event, one JSON object. It returns at once and may be
called from any thread. When the runtime is isolated, it crosses to the host as a oneway call
(`IEngineRuntimeCallback.emitText`), so a slow host never stalls a frame. On a host too old to
know it, the call fails to link with `IncompatibleClassChangeError`: the core catches that once
and stops sending.

A core declares nothing to send. A core that sends nothing gets nothing, and nothing pretends
otherwise.

## ehtext/1

Every event has `"v": 1` and `"t"`, its type. Unknown fields are ignored, so a 1.x event may add
optional fields. Only a change of meaning takes a new major version, and the host goes on
reading the old one for at least one release line.

```jsonc
{"v":1,"t":"hello","engine":"renpy","engineVersion":"8.3.4",
 "game":{"id":"<the engine's own id, if any>","title":"...","lang":"ja"},
 "markup":"renpy"}             // what markup `raw` carries: renpy|kag|nscripter|rpgm|bbcode|html|none
{"v":1,"t":"scene","id":"script.rpy:day1_morning","label":"day1_morning"}
{"v":1,"t":"line",
 "id":"day1_morning_3f2a9c1e", // stable across runs: the engine's own address for the line
 "seq":1532,                   // rises through the launch
 "kind":"dialogue",            // dialogue | narration | thought | choice_prompt | ui | system
 "speaker":{"id":"e","name":"Eileen"},  // from the engine; absent for narration
 "text":"Hello there!",        // what the reader sees: markup removed, ruby reduced to its base
 "raw":"{b}Hello{/b} there!",  // exactly what the engine had
 "ruby":[{"base":"漢字","reading":"かんじ","at":0}],
 "lang":"ja",
 "voiced":false,               // the game plays its own voice for this line
 "window":"adv",               // which text box
 "append":false,               // continues the previous line in the same box (NVL, KAG [l])
 "box":[0,560,1280,160]}       // the text box in engine pixels, when known
{"v":1,"t":"choices","id":"day1_menu_7c1d","prompt":"<line id or null>",
 "items":[{"id":"0","text":"Go left","enabled":true}]}
{"v":1,"t":"chosen","id":"day1_menu_7c1d","item":"0"}
{"v":1,"t":"advance","id":"day1_morning_3f2a9c1e"}  // the reader moved past the line
{"v":1,"t":"clear","window":"adv"}                   // the box was emptied
{"v":1,"t":"state","mode":"normal"}                  // normal | skip | auto | menu | paused | backlog
```

A line's `id` uses the engine's own address for the line where it has one:

| Engine | Line id |
|---|---|
| Ren'Py | the translation identifier |
| CatSystem2 | scene file + CST word index |
| Buriko, CMVS | script + offset |
| EasyRPG | map or common event + page + command index |
| RPG Maker MV/MZ | map + event + page + list index |
| NScripter | script offset |
| KiriKiri | storage + line |

Otherwise it is a hash of the scene id, the raw text and the line's occurrence in that scene.
Only caches and the voice map depend on the id, so a poor one costs a cache miss, nothing more.

## Printing translated text

`EngineHost.replaceText(String event)`: before the engine shows a `line` or `choices` event,
the core may ask for the text to print instead. The answer is the same event with its text
replaced, or null to print the original. It answers only from what the host already has and
never waits on a network, so an engine may ask on its own thread just before it draws. Until a
translator is chosen, every answer is null, which is what the API default answers too. The
isolated route is added with the first translator.

## What the host does with an event (`TextEvents`, `GameText`)

Every event is untrusted game data:

- **Rate.** 120 events a second on average, with bursts of up to 240 (`TextRate`). The excess
  is dropped, so a game drawing text every frame costs the host nothing more.
- **Size.** An event over 16 KiB of JSON is dropped. A text field is cut to 4,000 characters,
  and an id, name, language or mode to 256. A menu keeps 64 choices.
- **Format.** An event of another major version, of an unknown type or missing a required
  field is dropped whole, never half read.
  - Required fields: `engine` for hello; `id` for scene; `id` and `text` for line; `id` and at
    least one item with `id` and `text` for choices; `id` and `item` for chosen; a known
    `mode` for state.
  - An unknown `kind` reads as `dialogue`.
- **Characters.** Control characters are removed. A line break stays in text, and a tab
  becomes a space. A name, id or mode keeps no line break.
- **Display.** Text is shown as plain text only, never as markup or HTML.
- **Logging.** Game text is never logged: it can be anything the game shows, and host events
  never carry a game's content.

## Reading aloud (`GameSpeech`, `VoicePick`)

Settings, "Reading and translation", "Read text aloud": off until the person turns it on. The planner
reads it in the main process and hands it to the runtime with the launch
(`RuntimeActivity.EXTRA_READ_ALOUD`), so the runtime reads no preferences after its sandbox closes.

- **Voices.** The device's own text-to-speech (`android.speech.tts`), its voices for the device's
  language that need no network. This is one backend of the several the owner asked for; the owner's
  default for many distinct voices on the device is a multi-speaker engine (sherpa-onnx), a later slice.
- **One voice per character** (owner, 2026-10-14). `VoicePick` hashes the speaker's id (else its name)
  over the voices there are, so a character keeps its voice every launch with nothing stored. With fewer
  than eight voices each character also gets a pitch of its own, so two that share a voice still differ.
  Narration is read by the first voice at its own pitch.
- **What is read.** A line, unless the game voices it itself (`voiced`) or it is `ui` or `system` text. A
  line that continues the previous one (`append`) is queued after it; any other replaces it.
- **When it stops.** The reader moving on (`advance`, `clear`, `chosen`) stops the line being read. Skip
  mode stops reading until the game is back to normal. A line that arrives before the device's engine is
  ready is dropped rather than read late.

## Rig check

`adb shell setprop debug.enginehost.text 1`, then launch a game whose core sends text. The
newest six events are drawn in a band over the top of the game, one per line, and the band
never takes a touch. `setprop debug.enginehost.text 0` turns it off at the next launch. Only
adb or root can set a `debug.` property.
