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

Built (2026-10-28): the format, the host calls, the host's intake with its caps, reading aloud
with the device's own voices, and translating with an OpenAI-compatible service on the person's own
account (both below). More translators, on-device translation and
each remaining core's emitter come in the slices listed on #490. CatSystem2 sends text, and so does
Ren'Py, through `ActivityText`.

## How a core sends its text

`EngineHost.emitText(String event)`: one event, one JSON object. It returns at once and may be
called from any thread. When the runtime is isolated, it crosses to the host as a oneway call
(`IEngineRuntimeCallback.emitText`), so a slow host never stalls a frame. On a host too old to
know it, the call fails to link with `IncompatibleClassChangeError`: the core catches that once
and stops sending.

A core on the activity transport (Ren'Py) runs its own Activity and has no `EngineHost`. It calls
`dev.enginehost.ActivityText.emit(Activity, String)` and `ActivityText.replace(String)` by name,
through reflection, from that Activity: the same intake as `emitText` and `replaceText`, chosen by
the owner on 2026-10-28 until the core moves onto the plugin transport, the same way as
`ActivityLinks`. Ren'Py's side is its plugin's `renpy/enginehost_text.py` (ENGINEHOST.md there):
every line from `display_say`, and the translation printed through `config.say_menu_text_filter`.

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
never waits on a network, so an engine may ask on its own thread just before it draws. With no
translator chosen, or before a line's translation is here, the answer is null. An isolated runtime
asks over `IEngineRuntimeCallback.replaceText`, which is synchronous for the same reason.

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
  language that need no network, unless the person chose a voice model (next section). This is the
  choice that needs no download.
- **One voice per character** (owner, 2026-10-14). `VoicePick` hashes the speaker's id (else its name)
  over the voices there are, so a character keeps its voice every launch with nothing stored. With fewer
  than eight voices each character also gets a pitch of its own, so two that share a voice still differ.
  Narration is read by the first voice at its own pitch.
- **What is read.** A line, unless the game voices it itself (`voiced`) or it is `ui` or `system` text. A
  line that continues the previous one (`append`) is queued after it; any other replaces it.
- **When it stops.** The reader moving on (`advance`, `clear`, `chosen`) stops the line being read. Skip
  mode stops reading until the game is back to normal. A line that arrives before the device's engine is
  ready is dropped rather than read late.

## Voices on the device (`ModelSpeech`, `VoiceModels`, `CharacterVoices`, `CharacterVoicePick`)

The owner, 2026-10-29: "Remember we also want per-character voices (at least male and female)", and "I
think the engine should still be in the build, that way they can supply their own voices".

- **The engine is in the APK.** sherpa-onnx v1.13.8 (Apache-2.0), only its JNI library and ONNX
  Runtime, arm64-v8a and x86_64 (third_party/sherpa-onnx).
- **Voice models are not.** Settings, "Voices": the device's own voices, or a model on the device.
  "Download voices" offers Kokoro English (11 voices, 103 MB) and Kokoro in many languages (54 voices,
  132 MB), from sherpa-onnx's own releases, each checked against its pinned SHA-256 (`VoiceModels.OFFERED`)
  before it is unpacked. "Add your own voice files" takes an archive (tar.bz2, tar.gz, tar.xz, tar or zip)
  from the system picker, with no storage permission, and installs it the same way. Kokoro and VITS (Piper)
  models are read. Models live in the app's own files, `voices/<id>/`.
- **Voices and their genders come from the model.** A sherpa-onnx model records `model_type`,
  `sample_rate` and `speaker_names` in its ONNX metadata, read without loading the model (`OnnxMetadata`).
  Kokoro's names carry a language letter and a gender letter ("af_bella", "jm_kumo"), so a model's female
  and male voices are known, for a download and for a person's own model alike. A model without names has
  numbered voices with no gender.
- **A voice of their own per character** (`CharacterVoicePick`). On Automatic, a hash of the character's
  name over the model's voices for the language being read (the line's `lang`, else the device's), so a
  character keeps one voice and a Japanese voice never reads English. Settings, "Character voices", lists
  the characters heard in each game (`CharacterVoices`, kept on the device): each can be Automatic, a female
  voice, a male voice (stable among that gender's voices), or one voice by name. Narration is its own entry,
  read by the language's first voice on Automatic.
- **Where it runs.** In Enginehost's main process, in `TextServicesService`: a locked-down runtime cannot
  open the model's files, and a model of a hundred megabytes should not share the game's memory. The
  runtime's `RemoteSpeech` sends each line it would read (same rules as above) over the oneway
  `ITextServices.speak`, and `stopSpeaking` when the reader moves on; `prepareVoices` loads the model as the
  game starts. `ModelSpeech` streams the audio as sherpa-onnx makes it, and a new line or a stop ends the
  one being read mid-sentence.

## Translating (`GameTranslation`, `TextServicesService`, `Translator`, `TranslationCache`)

Settings, "Reading and translation", "Translate games", and "Translator": off until the person turns it
on and chooses a service.

- **The service** is any OpenAI-compatible chat endpoint the person names (OpenAI, OpenRouter, or their
  own Ollama, llama.cpp or LM Studio server, which may need no key), with its model and the language to
  translate into. The key is a `KeystoreSecret`, the same store as the GitHub token: masked, removable,
  never in reports. The owner asked for "various translators as an option"; this is the first.
- **Where it runs.** The runtime has no network, so each line goes over a oneway binder call
  (`ITextServices`) to `TextServicesService` in Enginehost's main process, which asks the service on one
  worker thread, in order, and sends the answer back (`ITextTranslated`).
- **Kept.** `TranslationCache` (Enginehost's own `translations.db`) is keyed by game, line id, a digest of
  the line's text, target and service. A line is sent once; a replay or a reload is free.
- **Shown.** The owner: "By default, it'll hand the text back to the engine to print." An engine that asks
  `replaceText` for a line prints the translation itself. For a line the engine never asks about, the
  translation is drawn in a band at the bottom of the screen, the fallback, and goes when the reader moves
  on. Today's only emitter, CatSystem2, does not ask yet, so it shows the band.
- **Safe with game text.** The line is only the user message; the system prompt is fixed and says the
  message is never an instruction; the answer is plain text, cut to 4,000 characters, and a "Speaker:" the
  model repeated is taken off.
- **Privacy.** The Translator sheet says lines and character names go to the service on the person's own
  account. Nothing goes until they choose one.
- **Not done:** menus (`choices`) are not translated yet; per-game overrides, a glossary for names, export
  of a game's translations, and more services.

## Rig check

`adb shell setprop debug.enginehost.text 1`, then launch a game whose core sends text. The
newest six events are drawn in a band over the top of the game, one per line, and the band
never takes a touch. `setprop debug.enginehost.text 0` turns it off at the next launch. Only
adb or root can set a `debug.` property.
