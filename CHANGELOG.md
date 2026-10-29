# Changelog

All notable changes to Enginehost are documented in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versions follow [Semantic Versioning](https://semver.org/) (0.x releases are
pre-release: no compatibility promises between them).

Every build published between releases gets its own release notes on the
per-build release for that commit, generated the same way as this file's
entries (grouped Added / Changed / Fixed); this file only gains a new
section when a real version is cut.

## [Unreleased]

### Added

- The launch contract with droidtop (the actions, extras and `enginehost.json` keys) is held once, in the shared
  library's `:launch-contract`, and the manifest and docs/contract.md are checked against it; every string is
  unchanged, so released builds of both apps interoperate (Droidtop/tracker#204).
- Read text aloud (Settings, Reading and translation): a game's lines in the device's own voices,
  one per character, stopping when you move on. It works with cores that report their text, which
  is CatSystem2 so far; Enginehost now takes the lines, speakers and menus a core reports in one
  format (`ehtext/1`).
- Translate games (Settings, Reading and translation): a game's lines in your language, through an
  OpenAI-compatible service on your own account (OpenAI, OpenRouter, or your own Ollama or LM Studio
  server). Each line is sent once and kept on the device. An engine that can print the translation
  does; otherwise it shows at the bottom of the screen.
- A game's own loading screen while its engine starts, in place of the plain dark window:
  Ren'Py's presplash (with its progress fill), Godot's boot splash and RPG Maker
  MV/MZ's Loading picture, else the game's box art. It goes at the engine's first frame.
- A permanent, versioned release for every green build of main, so build
  history stops disappearing when the next build lands. The rolling
  "latest" release is now only a pointer to the newest one; it is no
  longer the only record of what shipped.

### Changed

- GitHub requests (the plugin catalog, the engine registry, a repository's key documents, the update check and
  plugin bundle downloads) go through the shared `:net` client, the same as droidtop's. The GitHub token is no longer
  sent to `objects.githubusercontent.com` or to a subdomain of a GitHub host; redirects are followed one hop at a
  time. A bundle download that ends short of the length the server announced is now an error at once, not only a log
  line (Droidtop/tracker#195).
- The signature verifier, the bundle archive reader and the version ordering are now the library
  droidtop and Enginehost share (Droidtop/droidtop-shared, pinned as `vendor/droidtop-shared`) instead of
  Enginehost's own copies (Droidtop/tracker#193). Behaviour is the same except that the shared verifier
  also checks key sizes on every certificate of a chain and not only the leaf's, and the archive reader
  refuses a link or special file, a repeated path and an xz stream over 512 MiB the same way as before
  but in one place. Adds the `kotlinx-serialization-json` library.

- Engine plugins are called cores everywhere you read them: the Cores
  screen, the catalog, update notices, error messages, problem reports and
  the docs. Enginehost is to visual-novel and PC-game engines what
  RetroArch is to emulators. Nothing installed changes; the bundle format
  and the names other apps use are the same.

## [0.1.5] - 2026-09-28

This is Enginehost's first version-history entry: there was no changelog
before this release, so this section summarizes what already exists rather
than listing every change one by one.

### Added

- A centralized host for multiple game engine runtimes on Android
  (KiriKiri, Ren'Py, RPG Maker, Buriko/Ethornell, CatSystem2, CMVS,
  Flash/AIR, Twine, and Godot), each shipping as an independently
  versioned, installable engine bundle.
- A complete standalone application: game library, a config creator for
  games that need one, a plugin catalog with trust screens, controller
  mapping, and settings.
- A programmatic contract (`dev.enginehost.LAUNCH`, `dev.enginehost.CONFIGURE`,
  and the `dev.enginehost.capabilities` content provider) so another app,
  such as droidtop, can drive Enginehost end-to-end without its own UI.
- An in-app self-update check against the CI-published "latest" build.
- Crash and bug reporting, and a per-caller access/trust model for which
  other apps may launch games through Enginehost.
