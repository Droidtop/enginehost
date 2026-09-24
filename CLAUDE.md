# Working on Enginehost

Enginehost is an Android app that runs game engines (Ren'Py, Godot, RPG Maker, KiriKiri, ...)
delivered as signed plugin bundles, each built in its own `Droidtop/enginehost-*-plugin`
repository. It is a complete app in its own right AND a host other apps drive over Intents:
any flow in the UI has a programmatic equivalent, and vice versa. The bundle contract is
`docs/engine-bundle-format.md`; read it before touching loading, trust or capabilities.

## Rules (the owner's, not suggestions)

- **Mainline is `main`.** Commit straight to `main` as ordinary commits (no pull requests): fetch, rebase onto the remote `main`, push. Keep each commit one coherent change.
  (`codex/engine-bundles` is retired.)
- **Builds are CI** (`.github/workflows/build.yml`). A change is done when CI is green on its commit.
  Say what was and was not verified; a green build is not proof that a game runs.
- **No AI attribution anywhere.** No `Co-Authored-By`, no "Generated with", in commits or PRs.
- **Commit messages** are plain prose saying why, citing evidence.
- **Decisions go into the docs** in the same change. State comes from code, not from notes.
- **One mechanism per job.** Consolidate duplicates; delete the dead code you replace.
- **Every plugin ships arm64-v8a AND x86_64.**
- **Saves:** Enginehost does not change an engine's save logic. It only makes SYSTEM locations
  (user data dirs, app-private dirs) mean a folder the person chose. Engines that save beside
  the game keep doing so.
- **`dev.enginehost.LAUNCH` is open to any app, by design.**
- **Engine sandbox (decided 2026-09-24):** engine code must not have internet access, and must
  not have arbitrary file access; Enginehost does the file reads a launch needs and hands them
  to the engine. Today engines run in the `:runtime` process under the app's own UID and so
  inherit both; do not widen that, and treat changes that narrow it as welcome.
- **Enginehost and droidtop are separate apps.** Enginehost never assumes droidtop, a Linux
  container, or root exists.

## What you cannot do from a cloud session

You cannot reach the test device. When a change needs checking on a device, say so in the commit message
description under a heading **Needs a rig check**, with exact steps. The coordinator runs it on
the test rig and reports back.
