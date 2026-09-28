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

- A permanent, versioned release for every green build of main, so build
  history stops disappearing when the next build lands. The rolling
  "latest" release is now only a pointer to the newest one; it is no
  longer the only record of what shipped.

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
