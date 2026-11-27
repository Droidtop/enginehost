# Enginehost application flow review, 2026-11-27

Baseline: verified public main `92da4c3e6809baf4e94b5b07f3e047928afb89d9`. Code/static analysis only, per the owner.
No app, device or game was run. Paths below are under `app/src/main/kotlin/dev/enginehost/` unless specified.

| Flow | Source trace | Finding / boundary |
|---|---|---|
| Launch and core approval | `GamePage` primary → `GameRunner.run` → `LaunchActivity` → `GameRunner.plan` → `PluginTrustActivity` | One launch planner detours for missing/unapproved cores. It saves a pending launch, names the waiting game, and focuses Approve. These are already on main. |
| Approval continuation | `PluginTrustActivity.approve` → `GameRunner.pendingLaunchFor` → `resolvesWith` / `PendingPluginLaunchStore.take` → `GameRunner.resume` | Continuation requires a fresh pending launch, approval and the core the game actually resolves to. Leaving a detour while no install runs clears its pending launch. Source trace only; does not prove all asynchronous interleavings. |
| Caller and sandbox consent | `LaunchActivity.resolveCallerAccess`, `offerCallerAccessPrompt`, `offerSandboxConsent` | Caller access precedes game-folder reads. Cancel/Back cancels; silence or an inability to show the sheet never becomes consent. No initial highlight in touch mode and Cancel first are intentional decisions, not fixes to reverse. |
| Game-page return | `GameActivity.onResume` → `GamePage.refresh` → `GameStatus` / `GameSectionsLoader` | Returning after setup/core changes refreshes the page. Generation checks prevent an older detail read overwriting a newer one. |
| Cancel and launch failure | `LaunchActivity.cancel`, `onActivityResult`, `classifyExit`, `showFailure` | Cancel ends a starting runtime; errors have Retry/Back/Report. Runtime exit classification is delayed and bounded. Configuration destruction was incorrectly clearing the running-game registry: fixed in this batch with lifecycle-state tests. |
| Core updates | Home notice → `PluginTrustActivity.EXTRA_UPDATES`; `renderUpdates` → `runUpdates` | Cached update reads happen off the main thread. Update all gets focus once. Failed installs are counted and reported; cards redraw. Device layout/focus behavior remains unverified. |
| Per-game build choice | `GameSectionsLoader` → `GameSectionsView.changeCore` → `GameOverrides` | Compatible builds and Use newest are offered; a missing pinned build retains a recovery row. Game setup's `ConfigEditorActivity` separately offers engine/version/build choices. These do not establish that droidtop exposes cross-line choices. |
| Ren'Py controllers (#966) | `GameRunner.plan` selects `BundledActivityProxy` for the activity transport → `EnginehostComponentFactory` instantiates the bundle's Android entrypoint | Input is owned by the selected RAPT/SDL/pygame_sdl2 release, not `RuntimeActivity`'s host bindings. No affected-vs-working `log.txt` pair is available, so no mapping/focus/blocklist fix is guessed. |

## Fixed: a recreated launch floor forgot its still-running game

`LaunchActivity` saves and restores `runtimeStarted`, but previously `onDestroy` always called `RunningGame.ended`
and `onCreate` did not restore that registration. After configuration recreation, the same game's next launch
could miss `RunningGame.isRunning`, take the fresh-game `FLAG_ACTIVITY_CLEAR_TASK` path and restart it.

The old floor now preserves the registration while changing configurations. A restored started-runtime floor
reattaches an empty registry, without overwriting a newer game's registration. Normal destruction and actual
runtime results still clear the matching game. Pure state cases cover recreation, an empty registry, planning
without a started runtime, and a stale owner after another game starts.

The manifest already handles orientation, screen size and keyboard visibility. Ordinary rotation by itself is
therefore not claimed as the trigger: unhandled configuration recreation (for example UI mode/font/density/locale)
and process restoration are the relevant paths.

## Still unverified

- Real control input per Ren'Py line, focus timing and native runtime teardown.
- Every engine family's first frame, saves, watchdog behavior and return path.
- Large core-list responsiveness, Back/Cancel during install and configuration changes while a consent sheet is open.
- Touch, pad, accessibility, narrow/wide and light/dark layouts.

Required CI compiles/tests this change. It does not turn these device checks into passes.
