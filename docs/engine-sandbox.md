# The engine sandbox

The owner's rule (CLAUDE.md, decided 2026-09-24): engine code gets no
internet access and no arbitrary file access. Enginehost reads what a
launch needs and hands it to the engine. This page is how far Android
lets that go today, what is built, and what the rest needs.

"Engine code" means everything that runs in the `:runtime` process: the
bundle's dex and native libraries, and the game those run. The game is
the larger risk of the two. A Ren'Py game is Python, an RPG Maker MV game
is JavaScript, a Godot game is GDScript, and `dev.enginehost.LAUNCH` lets
any app ask Enginehost to run any folder (by design). Until this page,
whatever a game's scripts did, they did with Enginehost's UID: its
all-files access and its INTERNET permission.

## What Android allows inside one app

Permissions belong to the UID, not the process. `:runtime` is the same
UID as the main process, so it holds INTERNET (the `inet` group) and
MANAGE_EXTERNAL_STORAGE for as long as the app does, and an app process
cannot drop supplementary groups (no CAP_SETGID). What a process *can* do
to itself without privilege is install a seccomp filter: Android already
puts every app under one, and a second one stacks on it and can only
narrow it. A seccomp filter sees system call numbers and integer
arguments, never the memory behind a pointer, so it can tell an IP socket
from a Unix one but cannot tell one file path from another.

## Layer 1: no IP sockets in `:runtime` (built)

`EnginehostApplication.onCreate` calls `RuntimeSandbox.apply()` first
thing in the `:runtime` process, before any activity exists and so before
any bundle is loaded. It installs `app/src/main/cpp/runtime_sandbox.c`'s
filter with `SECCOMP_FILTER_FLAG_TSYNC`, so the threads already running
(ART's daemons, binder threads) are covered too, and every thread and
child process started afterwards inherits it. The filter answers EACCES
(what an app without INTERNET sees) to:

- `socket()` with `AF_INET` or `AF_INET6`;
- `io_uring_setup()`, which can make sockets without calling `socket()`;
- any system call in an architecture other than the process's own (i386
  through `int 0x80` and x32 on x86_64), which would otherwise go around
  the two checks above.

Unix sockets, netlink and files are untouched: logd, netd and the
framework need them. The WebView's network stack runs in the app process,
so the web engines (MV/MZ, HTML, Flash) lose the network with everything
else. Their game content never used it: they serve the game folder
through `shouldInterceptRequest`. The MV/MZ plugin's `allowNetwork` option
is inert under the sandbox, by the rule above.

Nothing in the host's own `:runtime` code uses the network (catalog,
updates, the platform snapshot and problem reports all run in the main
process).

**Best effort, by decision.** When the filter cannot be installed (32-bit
x86, where bionic makes sockets through `socketcall()` and a filter cannot
read its arguments, or a kernel that refuses), the game still runs, as it
did on every device before this, and logcat says so under
`EnginehostSandbox`. When TSYNC is refused, the filter still goes on the
main thread, which is the thread every engine starts from. Refusing to
launch instead would be a small change in `RuntimeSandbox.apply`; it was
not made blind, without a device to show that no supported device takes
the fallback.

### What layer 1 does not stop

- **DNS.** Name lookups go through netd over a Unix socket, so engine
  code can still resolve names, and a lookup of a name it made up carries
  data out to whoever runs that domain's DNS server. There is no address
  to connect to afterwards. It is slow and small, but it is a channel.
- **Binder.** Engine code holds a Context. It can ask the system to
  download a URL (DownloadManager checks the *app's* INTERNET permission),
  open a URL in another app with an Intent, or call another app's
  exported service. The filter cannot see any of that.
- **Files.** Nothing in this layer narrows file access; see below.

## Files: why there is no in-process answer

MANAGE_EXTERNAL_STORAGE is the UID's, and a path is a pointer a filter
cannot read. Linux's Landlock is the one unprivileged way a process can
narrow its own file access, but it is not a dependable answer on
Android: whether a device's kernel enables it is not established, and if
Android's app seccomp policy does not list its system calls, calling them
kills the process with SIGSYS rather than returning an error. Neither is
something to find out on the owner's device by shipping it. What *can*
stop a game reading the whole of shared storage is running the engine
under a UID that does not have it.

## Layer 2: the engine under its own UID (the target, not built)

An `android:isolatedProcess="true"` service runs under a fresh UID with no
permissions at all: no `inet` group, no shared storage, not even this
app's private directory. `isolatedProcess` is a `<service>` attribute --
the manifest schema has no such attribute for `<activity>` -- so the
framework itself rules out an isolated Activity; whatever draws the
game's frame has to be a Service the host binds to, not a screen the
engine owns. That is the whole rule, enforced by the kernel (a fresh UID
with an empty permission set) and by the manifest schema (Services only).

### The sandbox is on by default; what cannot be isolated asks every time (owner, 2026-09-25)

"Keep the sandbox on, actually. If a plugin doesn't support the sandbox,
it should prompt on EVERY run if the user is willing to run it
unsandboxed." A bundle that declares `isolatable` (docs/engine-bundle-format.md
"Sandboxing and the plugin contract") always runs isolated -- there is no
setting that turns this off, so there was nothing to remove or default
differently; `RuntimeActivity`'s dispatch has always been the one
unconditional check, `if (resolved.plugin.isolatable)`.

A bundle that does not declare it -- everything except the CatSystem2
milestone below, today -- runs the way every plugin ran before layer 2
existed: in `:runtime`, with this app's own all-files and network access
for as long as it plays. `LaunchActivity`, the one screen every entry
point passes through (droidtop's `LAUNCH` intent, Enginehost's own
library, Game setup's Test), asks about this before any plugin code
runs, on every single launch: a sheet naming what unsandboxed access
means in plain terms (shared storage and the network, the same as the
app itself), "Run unsandboxed" and Cancel, Cancel first and so the pad's
first focus, B/Back/a tap outside all cancelling too. Nothing is
remembered -- no per-plugin flag, no "don't ask again" -- so leaving and
relaunching the same game asks again, and a launch that finds nowhere to
show the sheet (the screen already finishing) refuses rather than ever
reading silence as consent. Enginehost only; droidtop is unaffected.

### Audit: what breaks per plugin shape

Today every v1 plugin, of both transports, runs inside one process,
`:runtime` (`app/src/main/AndroidManifest.xml`, `RuntimeActivity` and
`BundledActivityProxy`, both `android:process=":runtime"`). That process
is the app's own UID: full `MANAGE_EXTERNAL_STORAGE`, `INTERNET` (narrowed
by layer 1's seccomp filter, not by any UID change), and the app's private
data directory. Layer 2 moves that process's privilege to nothing and
re-derives, per plugin shape, how it still does its job.

**`runtimeTransport: activity` plugins are Activities by construction.**
`EnginehostComponentFactory` requires `runtimeTransport == RUNTIME_TRANSPORT_ACTIVITY`
before it will instantiate a bundle's own Activity class as
`BundledActivityProxy`, and that Activity is what the OS starts, resumes,
and hands input to. An Activity cannot run in an isolated service --
again, the manifest schema itself forbids it -- so this shape cannot move
into layer 2 unchanged. It needs a second host-side rewrite: replace "the
plugin's own Activity is the window" with "the plugin's own View or engine
loop runs inside an isolated Service and a host-owned Activity (today's
`RuntimeActivity`) is what the OS actually starts," the same
surface-and-input relationship layer 2 already gives plugin-api-transport
plugins. Until an activity-transport plugin is rewritten to stop being an
Activity, it stays on layer 1 only. No activity-transport plugin is
targeted by the first milestone (see below); this is deliberately later
work and is called out again in the roadmap.

**`runtimeTransport: plugin` (plugin-api transport) plugins already have
the right shape for a service boundary.** `RuntimeActivity.onCreate` loads
the bundle's dex with `PluginDexLoader`, constructs the plugin class, and
calls `EnginePlugin.onCreate(EnginePluginSession)` in-process
(`app/src/main/kotlin/dev/enginehost/RuntimeActivity.kt`). Nothing in
`EnginePlugin` or `EnginePluginSession` requires an Activity: the plugin
gets a `ViewGroup display()` to attach a `View` into, an `EngineHost` for
host services, and Strings/File for the game path and save directory
(`plugin-api/src/main/java/dev/enginehost/api/EnginePluginSession.java`).
What breaks moving this into an isolated service is exactly two things
that do not survive a process boundary as they are today:

- `session.display()` is a live `ViewGroup` from the host's own view tree.
  A `View` object cannot cross Binder; only tokens/surfaces can
  (`Surface`, `SurfaceControl`, `IBinder` for `SurfaceControlViewHost`).
- `session.gamePath()` and `host().saveDirectory()` are `String`/`File`
  the plugin is expected to open directly (`new File(gamePath, name)`,
  `fopen`). An isolated UID cannot open either: shared-storage paths are
  arbitrated by the FUSE daemon (`sdcardfs`/`MediaProvider` FUSE) against
  the *calling UID's* storage permissions, which an isolated UID never
  has, and the app's own private directory
  (`saveDirectory()`/`cacheDirectory()` today resolve under it) is
  `0700`-owned by the main UID and unreadable by any other UID, isolated
  or not.

So plugin-api-transport plugins are the shape layer 2 fits without a
second Activity rewrite: everything else in `EnginePlugin` (lifecycle
calls, controller events, `EngineHost.log/rumbleController/restart/fail`)
is already ordinary Binder-shaped data, once `display()` and file access
are replaced. This is why the first milestone (below) targets this
transport.

**Native engines doing their own `open`/`stat`.** A plugin-api plugin's
JNI layer routinely bypasses `EngineFileSystem` (which exists but is
explicitly optional, "for engines that do not require mmap/native
paths") and does POSIX I/O straight from C. CatSystem2 is a concrete,
minimal example: `cs2_files_open` (`src/files.c`) calls `opendir(game_root)`
once, at open time, to list the `.int` archive files, builds one absolute
path per archive (`snprintf(entry->path, ..., "%s/%s", game_root, ...)`),
and each archive is opened lazily with `cs2_kif_open(entry->path, ...)`
which resolves to a plain `fopen`. Saves are similar: `cs2_system_set_save_folder`
takes an absolute path and `save.c`'s `write_to`/`read_from` open files
under it directly. None of this reaches Java's `EngineFileSystem`; it is
raw `opendir`/`fopen` against a path string, in a `.so` the isolated
process loads and runs at native privilege. An isolated UID's `fopen` on
a shared-storage path fails the same way Java's would (FUSE UID check);
it is not a JNI-specific hole, just the same wall reached from C instead
of `java.io.File`.

**WebView inside an isolated service (HTML, MV/MZ).** Both web-hosted
plugins already read the game folder through one seam rather than scattered
`fopen`s: the HTML plugin's `GameServer.shouldInterceptRequest`
(`enginehost-html-plugin/app/src/main/java/dev/enginehost/plugin/html/GameServer.java`)
answers every resource request itself from `File`/`FileInputStream`
against the game folder, serving the game as `https://game.enginehost.local`
so `fetch`, Range requests and module scripts behave like a real origin;
nothing is ever loaded as a `file://` URL. `LocalStorageBridge` similarly
funnels `localStorage` through one Java object into a single
`localStorage.json` in the save folder rather than WebView's own
per-origin storage. Both seams are exactly where a host-broker FD swap
belongs and need no change to WebView usage itself.

What is genuinely untried is whether a `WebView` will construct and render
at all inside an `isolatedProcess` service. Two specific risks, both
documented Chromium/WebKit behavior rather than something narrow to this
codebase:

- Chromium's WebView refuses to run twice against the same on-disk data
  directory in the same process generation; a second `WebView` instance in
  a second process needs `WebView.setDataDirectorySuffix()` called before
  any `WebView` object is constructed, or it throws
  (`Using WebView from more than one process...`) or silently corrupts the
  shared lock file. An isolated-service `:runtime` is a second process by
  definition, so this call is required, not optional, once WebView runs
  there -- and the isolated UID needs *somewhere* writable for that data
  directory, which layer 2 does not otherwise give it (see the file
  service design below: WebView's private directory is not the game or
  save folder and needs its own small per-launch scratch area).
- Android's `isolated_app` SELinux domain (tightened further for WebView's
  own renderer sandbox as `isolated_app_locked` since API 29) allow-lists
  which system `IBinder` services an isolated UID may call at all; it is
  not merely "no permissions," it is "no access to most of Binder." Audio
  (`AAudioService`/`AudioFlinger`) and `SurfaceFlinger`/`gralloc` access
  for a `Surface` are both known to work from isolated processes -- that
  is exactly how Chrome's own renderer sandbox and WebView's own
  `isolated_app_locked` renderer already run video and audio today, so
  those two are not expected to be new problems for this project's
  isolated services either. Whether *this* project's target API levels'
  SELinux policy additionally allows the specific calls an embedded
  WebView makes when it is not itself Chromium's own privileged renderer
  slot is not established from source reading alone and is exactly the
  kind of claim the milestone below must confirm on-device before HTML or
  MV/MZ are scheduled into layer 2. Recorded as untried, not assumed
  either way.

**Surfaces and input.** The replacement for "the plugin attaches a `View`
into `session.display()`" is one of two documented cross-process
mechanisms, not a new one invented here:

- `SurfaceControlViewHost` (API 30+): the host creates a
  `SurfaceControlViewHost` for its own window, hands the isolated service
  the reverse channel (a `SurfaceControlViewHost.SurfacePackage`, itself
  `Parcelable`), and the service attaches a real `View` hierarchy into it
  that composites directly into the host's window without an extra
  `SurfaceView` hop. This is the API Android's own docs recommend for
  "render a UI owned by a different process" and is what this project's
  minimum supported API should determine, not an assumption made here
  (this doc does not currently state a `minSdk`; whatever it is gates
  whether `SurfaceControlViewHost` is available for every device the
  isolated path ships on).
- Plain `Surface` handoff (older API floor, also always available): the
  host creates a `SurfaceTexture`/`SurfaceView`, takes its `Surface`
  (`Parcelable`), and passes it to the isolated service over the AIDL
  connection; the service draws into it directly (`ANativeWindow` from
  JNI, or a `Canvas` from `Surface.lockHardwareCanvas()`). This is the
  same mechanism `MediaCodec.configure(..., surface, ...)` and
  `Camera2`'s capture targets use to hand a decoder or camera pipeline in
  another process a place to draw, and needs no minimum API layer 2
  doesn't already require.

CatSystem2 (the milestone target) makes this simpler than either: its
`ScreenView` is a plain `android.view.View` that rasterizes the engine's
own `int[]` pixel buffer into a `Bitmap` in `onDraw`, and the native layer
already computes a row-range diff per frame (`nativeFrame`'s returned
`(firstRow << 16) | rowCount`) specifically so unchanged frames cost
nothing. That diffed `int[]` is small, Binder-transportable data --
sending it to the host process on every non-empty frame and letting the
host draw it into a local `Bitmap`/`View` is a valid first-milestone
transport that needs no `Surface` or `SurfaceControlViewHost` handoff at
all. This does not generalize to a GPU-driven engine (Godot, SDL,
anything using `GLSurfaceView`/Vulkan), which must use one of the two
mechanisms above; it is specific to why CatSystem2 is a genuinely small
first step and not a template for every plugin.

Input travels the direction it already does: `RuntimeControllerRouter`
normalizes controller/touch events on the host side today and calls
`plugin.onControllerEvent`/View touch handlers in-process; under layer 2
those normalized events (already small, serializable data --
`EngineControllerEvent`'s action/pressed fields) cross the same AIDL
connection the frame data uses, host to service. Nothing about
normalization needs to move.

**Audio.** CatSystem2 opens an `AAudioStream` and gets sample callbacks
entirely inside its own native code (`app/src/main/cpp/jni.c`'s
`open_sound`/`feed_audio`); it never asks the host for anything audio
related. AAudio's client library talks to `AAudioService` over Binder from
whichever process calls it. Playing audio has never required a
declared Android permission for any app, isolated or not, and isolated
processes are already relied on elsewhere (WebView's own render process,
Chrome) to reach `AAudioService`/`AudioFlinger` for exactly this. Expected
to work unchanged; the milestone rig check (below) confirms it rather than
assuming it, since it is the one piece of this plugin's behavior that
depends on the isolated UID reaching a system service and not just on
file/surface plumbing this doc controls directly.

**JNI and `dlopen` from the bundle directory.** `RuntimeActivity.loadPlugin`
resolves native library paths as `Build.SUPPORTED_ABIS.map { File(root,
"lib/$it") }` under the installed bundle's own directory (a subdirectory
of the app's private `files/engine-bundles`) and threads them through
`PluginDexLoader`'s native library search path, which is an ordinary
`dlopen` search path, not a broker call -- the same mechanism
`PathClassLoader`/`BaseDexClassLoader` always uses. Under layer 2 this
does not change in kind: the isolated service still needs `dlopen` to
work, which it does for any file the *service's own process* can read.
The blocker is not `dlopen` itself, it is that `files/engine-bundles` is
today under the main UID's private directory (mode 0700), unreadable by
an isolated UID. The fix is part of the same file service as the game and
save folders (below): the host either opens the bundle's `lib/<abi>/*.so`
and dex files as FDs for the isolated service the same way it opens game
files, or -- simpler, and preferred, since bundle contents are verified
once at install and do not change per launch -- the bundle's *installed*
directory is made group-readable to a group the isolated UID's `gid` is
also placed in (isolated UIDs still get a `gid` derived predictably from
the declared `isolatedProcess` service, which the host can `chgrp` the
extracted bundle directory to at install/verify time, once, rather than
proxying every dex/`.so` open per launch). This needs a concrete decision
before the milestone lands; see the file service design's "the bundle's
own files" bullet.

**Saves.** Already narrower than game files: the save directory is
per-game, host-chosen, and the plugin only ever gets a `File` --
`session.host().saveDirectory()` -- and writes under it (CatSystem2:
`cs2_system_set_save_folder`; MV/MZ and Ren'Py: their own save-path
option). It moves onto the same host-broker FD path as the game folder,
scoped read-write instead of read-only, and needs no new design beyond
"same broker, different open mode, different root." The HTML plugin's
`LocalStorageBridge` already writes exactly one file
(`localStorage.json`) there today and is the simplest case to prove the
broker against.

### Host file service design

**Mechanism: a per-launch, per-game Binder file-broker service, plus a
callback seam in each native engine's existing file-access layer rather
than an `LD_PRELOAD`/PLT hook.**

The broker itself: the host (main-UID process) exposes a small AIDL
interface (new, e.g. `IEngineFileBroker`) that the isolated `:runtime`
service is handed a live `IBinder` for at construction (an extra in the
`bindService` intent's binder extras, or the first call over the
service's own AIDL connection -- a detail for implementation, not a
design fork). It offers, scoped to exactly the one launch that created it
and revoked when that launch ends:

- `list(String relativePath): String[]` -- one directory's entries, game
  folder only.
- `openRead(String relativePath): ParcelFileDescriptor` -- opened
  read-only against the game folder root the launch resolved,
  canonicalized and bounds-checked the same way `RuntimeActivity`'s
  existing `safeRuntimeChild` guards bundle files today (reject `..`,
  reject symlink escapes, reject absolute paths in the request).
- `openWrite(String relativePath, boolean append): ParcelFileDescriptor`
  -- opened read-write against the save folder root only; the game folder
  is never opened for write, which matches the save policy ("we don't
  change save logic, we just make system locations mean somewhere
  different") and keeps a compromised isolated process from touching the
  reader's game files even if it finds a bug in the broker's path
  checking.
- `stat(String relativePath): FileStat` (size, mtime, isDirectory) for
  engines that need metadata without an open.

`ParcelFileDescriptor` is the standard cross-process file handle on
Android -- it is what `ContentProvider.openFile`, `MediaStore`, and
`SAF`'s `DocumentsContract` already hand callers in other processes, and
what `RuntimeSandbox`'s own seccomp filter deliberately leaves untouched
(files, unlike sockets, are supposed to keep working). The broker opens
the underlying `File` itself, in the *host's* process, with the host's
own UID and its already-granted `MANAGE_EXTERNAL_STORAGE`, and hands the
fd across; the isolated process never resolves a path against shared
storage itself, so the FUSE UID check never sees the isolated UID at all,
which is the entire point.

**Why not `LD_PRELOAD` or a PLT hook.** Both were considered and rejected:

- An `LD_PRELOAD`-style interposer needs the dynamic linker's cooperation
  (`LD_PRELOAD` env var, honored by bionic since API 23 for non-`setuid`
  processes) and must be set before the target library's `dlopen`. It
  would have to intercept every libc entry point an engine's C code might
  call (`open`, `open64`, `openat`, `fopen`, `opendir`, `stat`/`stat64`,
  `access`, ...) uniformly across NDK/bionic versions, and still misses
  anything a plugin reaches via a statically-linked or vendored libc
  (several plugins vendor third-party decoders as static libraries). It
  is also invisible at review time: nothing in the plugin's own source
  says which calls are intercepted, which conflicts with "one mechanism
  per job, not two" once a per-plugin native shim exists anyway.
- A PLT/GOT hook (patch each library's import table at load time) is
  narrower in scope than `LD_PRELOAD` but is exactly the kind of binary
  patching this project has no infrastructure for, is fragile across
  compiler/linker changes (relocations, `RELRO`, 16 KB page alignment on
  newer NDKs), and would need to be re-verified every time a vendored
  engine's toolchain changes.
- Every native engine already funnels its file access through a small
  number of its own functions, not raw libc calls scattered through game
  logic: CatSystem2 through `cs2_files`/`cs2_saves`, and the pattern holds
  wherever a plugin has any file abstraction at all (`EngineFileSystem`
  exists in the Java API for exactly the plugins that use it). Adding one
  host-callback seam to that existing layer -- `cs2_files_open` gains an
  optional "open by callback" path that takes a small vtable of
  `list`/`open`/`stat`/`close` function pointers wired from JNI to the
  Binder broker, used instead of `opendir`/`fopen` when the plugin runs
  isolated -- is a few dozen lines in one file per plugin, visible in
  review, and does not depend on linker behavior. This is the "VFS layer
  the plugin ABI already has" this doc's audit above found: it already
  exists per-engine in embryonic form (a single files-abstraction module),
  it just currently opens real paths instead of asking a callback.

**The bundle's own files (built, revised from this section's first draft).**
Not part of the per-launch broker (bundle contents are install-time
verified and launch-independent, unlike game files): `dlopen`/`DexClassLoader`
must resolve real paths themselves, which a broker's fds do not
substitute for without also building `android_dlopen_ext`/
`InMemoryDexClassLoader` plumbing this milestone does not have yet. What
is actually built instead: `EngineBundleInstaller` makes an isolatable
bundle's own extracted files world-readable (`.so` also world-executable)
at install time, and `PluginRegistry.root()` keeps the bundle registry
directory and this app's own `files/` directory world-*executable*
(traversable by name, never listable) every time either is touched. Both
are needed: a directory missing the execute bit for "other" refuses
traversal into it by any other UID even when the file at the far end of
the path is itself world-readable, regardless of that file's own mode --
found the hard way, when dq-sandbox-01's first rig run reached
`IEngineRuntimeService.init()` and failed there with "A signed dex file
is missing": the per-bundle chmod was real, but its two ancestor
directories were not touched by it, so the isolated UID could not walk
into either one to reach a file it did have read permission on.
Narrower than the broker route (limited to files a bundle's own manifest
already opted into exposing, and only two ancestor directories besides,
neither of them listable), but it is what is built, not the broker-based
design this section originally proposed without building. The ancestor-
traversal fix above is queued for its own rig check now
(dq-sandbox-01's steps 1-4, re-run); until that passes this remains
"believed fixed", not confirmed. The broker route remains the
longer-term target if a second isolatable plugin's needs outgrow this
one; nothing here forecloses it.

### First milestone

Target: **CatSystem2** (`enginehost-catsystem2-plugin`, branch
`plugin/0.1` -- its real, current, native-engine line; `plugin/2.0` is an
abandoned Activity-based CST prototype with no engine in it, wrongly
named here in this section's first draft), chosen over CMVS after
reading both. Reasons, not a coin flip:

- Both are `runtimeTransport: plugin`, no native-Activity transport, and
  similar in size (CatSystem2: 30 native files under `src/`, one JNI
  bridge file, one ~300-line Java wrapper; CMVS: 32 native files, larger
  surface including its own touch/movie handling in `camera.c`).
- CatSystem2's rendering is a plain software `View` rasterizing a native
  `int[]` pixel buffer with an already-implemented row-diff
  (`nativeFrame`), not a `SurfaceView`/GL surface. As the surfaces-and-input
  audit above notes, this lets the milestone ship real frames over the
  AIDL connection as data (an `int[]`/byte buffer) rather than requiring
  `SurfaceControlViewHost` or `Surface` handoff to be built and proven
  first. That is strictly less to build for a first milestone, without
  weakening the design for engines that do need a real surface -- both
  mechanisms remain documented above for when a GPU-driven engine's turn
  comes.
- Its file access is already funneled through one native seam
  (`cs2_files`), the exact shape the broker's native-callback design
  above targets, and its save path is a single `cs2_system_set_save_folder`
  call rather than several.

Acceptance, matching the gap assessment's own wording
(`/root/coordination/research/gaps-enginehost-2026-09-25.md` item 17):
CatSystem2's `:runtime` runs under `android:isolatedProcess="true"`, all
file access (game archives, save file) goes solely through the host
broker described above, and a real game still boots and is playable.

Work still to land, in order, none of it done yet:

1. A new isolated `<service>` in `app/src/main/AndroidManifest.xml`
   (`android:process=":runtime_isolated"` or similar, `android:isolatedProcess="true"`,
   `android:exported="false"`), bound by `RuntimeActivity` (or a small
   successor that keeps owning the window) instead of loading the plugin's
   dex in-process, gated behind a per-plugin flag so every other plugin's
   `RuntimeActivity` path is unchanged -- see the manifest/ABI addition
   below.
2. `IEngineFileBroker` AIDL, implemented host-side against the resolved
   launch's game folder, save folder and bundle directory, with the same
   path-safety checks `safeRuntimeChild` already applies.
3. The frame/input AIDL surface (CatSystem2-specific: pixel-diff frames
   host-ward, controller/touch events service-ward) -- deliberately not
   the general `Surface`/`SurfaceControlViewHost` mechanism, per the
   audit above.
4. `cs2_files`'s callback seam in `enginehost-catsystem2-plugin`
   (`src/files.c`, `src/save.c`, wired from a new JNI entry point), on its
   `plugin/0.1` branch, built for both `arm64-v8a` and `x86_64` as every
   plugin ships.
5. The manifest/plugin-contract addition this step's instructions ask
   for: a new optional bundle-manifest field (`docs/engine-bundle-format.md`)
   naming the plugin's isolation support, e.g. `"isolatable": true` on a
   capability or bundle, additive and versioned so a host that does not
   understand it (an older Enginehost) ignores it and keeps using the
   unisolated path, and a plugin that does not declare it is never asked
   to run isolated. This is a manifest/doc-level decision recorded now;
   it is **not implemented in code this pass** -- landing an unused field
   with no host behavior behind it yet is dead weight, and the host-side
   flag check belongs with step 1 above, in the same change that adds the
   isolated service. This doc is where the shape is decided so that
   change, when it lands, matches this design rather than improvising one.

**Status (updated, later pass): steps 1-4 and the manifest field in step
5 are all built and merged** -- the isolated service, the broker AIDL,
the frame/input AIDL, and `enginehost-catsystem2-plugin`'s own seam
(`plugin/0.1`, commit `69fa2d2`), plus `isolatable` in the bundle
manifest schema. Both repos' CI is green, and the signed CatSystem2
bundle is live on the unstable channel. Not yet confirmed on a device: a
first isolated rig run (dq-sandbox-01) reached `IEngineRuntimeService.init()`
and failed there ("A signed dex file is missing" -- an ancestor-directory
traversal permission gap in `PluginRegistry.root()`, fixed above under
"The bundle's own files"); a re-run of that check is what would make this
section's "not yet confirmed" become "confirmed".

### Roadmap: the remaining plugins, in order

1. **CatSystem2** (above) -- proves the broker and the native-callback
   seam end to end on the simplest surface shape.
2. **CMVS** -- same transport and broker shape as CatSystem2 once the
   broker exists; its extra surface (`camera.c`'s touch/movie handling)
   is the first test that the design generalizes past the exact plugin
   it was built against.
3. **KiriKiri (`enginehost-kirikiri-plugin`/`enginehost-plugin-kirikiri`),
   NScripter, mkxp-z, Ren'Py, EasyRPG** -- other `plugin`-transport,
   non-Activity engines. Each needs its own file-access audit (some may
   already route entirely through `EngineFileSystem` rather than raw
   `fopen`, which would mean only the Java-side broker wiring, no native
   callback seam) before being scheduled; this doc does not assume they
   match CatSystem2's shape without checking.
4. **HTML and RPG Maker MV/MZ (WebView-hosted)** -- blocked on the WebView
   audit item above being resolved on-device (does a `WebView` construct
   and render inside `isolated_app`/`isolated_app_locked` at all, and does
   `setDataDirectorySuffix` plus a broker-backed scratch directory make it
   work). Their file-access seam (`GameServer.shouldInterceptRequest`,
   `LocalStorageBridge`) is already broker-ready; only the WebView-in-isolation
   question is open.
5. **Flash/AIR (`enginehost-flash-air-plugin`)** -- same web-hosted shape
   as above if it also runs inside a WebView-equivalent runtime; audit
   before assuming.
6. **`runtimeTransport: activity` plugins (SDL/Godot's Activity path)**
   last, deliberately: these need the second host-side rewrite the audit
   above describes (an Activity's responsibilities moved into a
   host-owned Activity plus an isolated service, not a broker swap alone)
   and are the largest remaining piece of work, not a small follow-on.
   They stay on layer 1 only until that rewrite is scoped and designed in
   its own pass of this document.
