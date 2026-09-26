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

**The bundle's own files (built, twice-revised from this section's first
draft -- read this as the current account, not the history).** Not part
of the per-launch broker (bundle contents are install-time verified and
launch-independent, unlike game files).

The first thing built (`EngineBundleInstaller` making an isolatable
bundle's files world-readable, `PluginRegistry.root()` keeping its two
ancestor directories world-*executable*) addressed a real gap -- the
ancestor directories genuinely were never touched by the per-bundle
chmod -- but dq-sandbox-02 shows it was not the (or not the whole)
answer: the identical `IllegalArgumentException: A signed dex file is
missing`, same call site, reproduced on *both* BlueStacks' Android 9
*and* emulator-5560's Android 14 after this fix was in place, where the
Android-10-and-later theory predicted Android 9 specifically should have
started working. Recorded plainly rather than explained away: either
Android 9 on this rig image does not have the traditionally-assumed
0751 on `/data/user/0/<pkg>` (BlueStacks is not a stock AOSP device),
or something beyond ancestor-directory DAC bits is also in play (an
`isolated_app` SELinux denial independent of the Unix permission bits,
or a genuine platform restriction on what an isolated process may
`DexClassLoader`, is exactly what a raw error message and no root access
on the rig cannot distinguish between). This is exactly why the fd-based
route below does not lean on that theory being right: it sidesteps
directory permissions, DAC and any hypothesised SELinux category
question alike, since the isolated process never resolves a path of its
own at all.

Built alongside it, and the one this doc now recommends: the host opens
the bundle's own dex and native-library files itself (against the
already hash-verified installed directory -- `InstalledBundleVerifier`
runs before either loading path is reached, so this opens nothing that
was not already checked file-by-file against the signed manifest) and
hands the isolated service `ParcelFileDescriptor`s for them over
`IEngineRuntimeService.init()`, the same Binder mechanism the game/save
broker already uses. The isolated side never resolves a path of its own:
each descriptor is addressed as `/proc/self/fd/N`, the same open file by
a path string ordinary path-based loading APIs accept -- `DexClassLoader`
for the dex (`PluginDexLoader`, unchanged mechanism, just fed a
`/proc/self/fd` path instead of a real one) and, for each native
library, `PluginDexLoader.findLibrary(name)` resolving straight to its
`/proc/self/fd` path so the plugin's own unmodified
`System.loadLibrary(name)` call finds it exactly as it would a real
directory search. No `android_dlopen_ext`/`ANDROID_DLEXT_USE_LIBRARY_FD`
native bootstrap needed: `ClassLoader.findLibrary` is a documented,
ordinary extension point built for exactly this, and going through the
normal `System.load`/`Runtime.nativeLoad` path (rather than a raw
`dlopen`) is what keeps native-method resolution working for the plugin's
own `native` declarations without touching the plugin at all -- a raw
`dlopen_ext` call would load the bytes but not register the library
against the plugin's classloader, leaving every `native` method
unresolved unless each one were re-bound by hand with `RegisterNatives`,
which does not scale across plugins. This route needs no minSdk above
26 (Enginehost's own floor): `DexClassLoader`'s `librarySearchPath`
constructor argument and `findLibrary` override have existed since
before `isolatedProcess` did, and `/proc/self/fd` path resolution is
ordinary Linux kernel behaviour, not an Android- or API-level-specific
feature. (`InMemoryDexClassLoader`, the alternative for the dex half
alone, would need API 29 for its own `librarySearchPath` constructor --
moot here, since `DexClassLoader` fed a `/proc/self/fd` path already
covers both halves down to API 26.)

**Security reasoning for the chmod route, while it still runs alongside
the fd route:** on Android releases where it takes effect (pre-10, so
far only confirmed relevant to BlueStacks' Pie image), an isolatable
bundle's dex and native libraries become reachable by *any* app on the
device that already knows the exact path -- not listable (no directory
gained a read bit, only execute), so nothing is discoverable, only
openable by a path an attacker would have to already have. This is
acceptable only because what it exposes is a bundle's own *code*,
install-time signature-verified against the key pinned for its origin,
already public in the sense that its origin repository ships it openly
-- the same trust class as an installed APK's own files, which Android
itself makes world-readable by long-standing convention. It must never
be extended to saves or `enginehost.json`: those stay behind the broker
exclusively, read-write and per-launch-scoped, never touched by any
chmod this installer applies.

**A gap in the fd route itself, found before any device confirmed it
either way:** opening `/proc/self/fd/N` BY PATH -- what `DexClassLoader`
and `findLibrary`'s resolved path both do -- is a fresh `open()` of the
*original* file, so SELinux re-checks it against that file's own
security context (`app_data_file`) exactly as it would a direct path
open. If `isolated_app`'s SELinux policy, not a DAC permission bit, is
what has actually been refusing this all along (dq-sandbox-02's
identical failure across two Android versions fits that better than a
DAC-only story), the fd route as first built would hit the same wall.
Binder's own fd transfer needs no such recheck -- reading directly from
a descriptor already handed to this process is not a fresh open -- so
`IsolatedRuntimeService` now copies each received descriptor's bytes
(no reopen, one read from the fd already held) into a `memfd_create(2)`-backed
file this process created itself (`android.system.Os.memfd_create`,
API 30+; below that, or on any failure, the original descriptor is used
unchanged, today's behaviour). A memfd carries no `app_data_file`
label, so reopening *its* `/proc/self/fd` entry checks this process's
access to its own anonymous memory-backed file, not the bundle's. The
owner's own device (Retroid Pocket 5, Android 13) is comfortably above
the API 30 floor this needs; BlueStacks (Android 9) is not, and stays on
the plain fd-reopen behaviour there, unresolved either way until
dq-sandbox-03 (now updated to also grep logcat/dmesg for `avc: denied`,
which is what actually distinguishes a SELinux denial from anything
else) comes back.

**Status:** dq-sandbox-02 (commit e37db5e, the chmod-only fix) failed
identically on both BlueStacks and emulator-5560 -- the chmod route is
not what fixes this, on either device tested so far. The fd route,
now including the memfd copy above (commit to follow this doc update),
is built, this repo's own CI compiles it, and it does not depend on the
ancestor-directory theory being correct at all, which is exactly why it
is queued next (dq-sandbox-03) rather than treated as already explained.
The chmod-based ancestor-traversal fix is left in
place for now regardless -- it costs nothing extra since dex/native-library
loading no longer depends on it either way, and it may still matter for
some other read the isolated process does that this doc has not audited
for path-traversal exposure yet. Once dq-sandbox-03 confirms the fd
route, revisit whether the chmod fix is still pulling its weight for
anything, and remove it if not: two mechanisms for the one job is not
something this project keeps once one of them is proven unnecessary.

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

**Status (updated, second later pass): steps 1-4 and the manifest field
in step 5 are all built and merged** -- the isolated service, the broker
AIDL, the frame/input AIDL, and `enginehost-catsystem2-plugin`'s own seam
(`plugin/0.1`, commit `69fa2d2`), plus `isolatable` in the bundle
manifest schema. Both repos' CI is green, and the signed CatSystem2
bundle is live on the unstable channel.

Still not confirmed on a device, and the story is not as simple as first
thought: dq-sandbox-01 (BlueStacks/Android 9) hit "A signed dex file is
missing" at `IEngineRuntimeService.init()`, traced to an ancestor-directory
traversal gap in `PluginRegistry.root()`; a chmod fix for that (commit
`e37db5e`) landed and dq-sandbox-02 re-ran it on *both* BlueStacks and
emulator-5560 (Android 14) -- and got the identical failure on *both*,
where the working theory expected Android 9 specifically to start
passing. So the ancestor-directory theory is not the (or not the whole)
explanation; see "The bundle's own files" below for what that does and
does not tell us. A second route -- `ParcelFileDescriptor`-based dex and
native-library loading that never has the isolated process resolve a
path of its own, so it does not depend on that theory being right either
way -- is built and CI-green as commit `84cc5ee`, queued for its own rig
check as dq-sandbox-03.

**dq-sandbox-03 (both rigs): dex/native-library loading is fixed.**
CatSystem2 decrypts its archives and starts executing in the isolated
process on both BlueStacks and emulator-5560; the isolated UID is
`99013`, the app's own is `10064` -- the boundary itself is real, not
just configured. The new, different blocker: the isolated process hangs
forever rather than failing, inside CatSystem2's own `open_sound()`,
waiting on AAudio to open a stream. An isolated process cannot look up
`AudioFlinger` through `ServiceManager` at all (see "What Android allows
inside one app" above), so the wait never ends on its own -- nothing
timed out anywhere in this milestone's own code before this pass, so
this single missing service could hang every future isolated launch
silently, however it happens to be caused next time. Two changes follow
from that, both landed together: the actual fix (below, "Audio"), and a
launch-level watchdog that is not specific to audio at all --
`IsolatedRuntimeHost.start()` now bounds the whole
`bindService`-through-`init()` sequence to `INIT_TIMEOUT_MS` (15s) via a
single `settle {}` gate that every success and failure path (including
the AIDL call itself throwing) must pass through exactly once; a timeout
unbinds the service and fails the launch with a reported reason instead
of leaving the screen waiting. This is the backstop for whatever the
*next* unreachable system service turns out to be, not just this one.

### Audio

An isolated process cannot reach `AudioFlinger` (dq-sandbox-03, above),
so a plugin's own audio path can never simply keep working the way its
in-process one does -- unlike file access, where a broker can make a
real read/write happen on the plugin's behalf, there is no "ask the host
to open my AAudio stream for me" primitive; AAudio session negotiation
is bound to the process that opens it. So audio crosses the isolation
boundary the same way frames do in this milestone (see "First
milestone" above): the isolated side never touches a real audio device
at all, and the host owns the one real output.

**The mechanism.** `IsolatedRuntimeHost` (`:runtime`, before it even
binds the isolated service) creates an anonymous, memory-backed region via `android.system.Os.memfd_create` (API 30+; the same primitive `ownedCopy` in IsolatedRuntimeService.kt already relies on)
-- a 16-byte header (write position, read position, capacity, reserved,
each a little-endian `uint32`) followed by a 32KiB ring of raw 16-bit
stereo PCM -- and hands a `dup()` of its fd across `IEngineRuntimeService.init()`
as `audioBuffer`, alongside `audioSampleRate` (picked from
`AudioTrack.getNativeOutputSampleRate`, the host's own real output
rate). `EngineHost.isolatedAudioBuffer()`/`isolatedAudioSampleRate()`
(plugin-api, default `null`/`0` so no existing `EngineHost` has to
change to keep compiling) hand that descriptor and rate to the plugin
exactly like `gameBroker()`/`saveBroker()` do for files. The isolated
side (`IsolatedRuntimeService.init()`) never opens AAudio: it passes the
fd straight through to native code.

In CatSystem2 (`enginehost-catsystem2-plugin`, `jni.c`), `nativeOpenIsolated`
now takes the `ParcelFileDescriptor` and rate as two extra arguments and,
when both are present, calls `open_sound_bridged()` instead of the
existing AAudio-based `open_sound()` -- `finish_open()` branches on an
`isolated` flag so an ordinary in-process launch is completely
unaffected and still opens AAudio directly, unchanged. `open_sound_bridged()`
`mmap()`s the fd (`MAP_SHARED`), constructs `cs2_audio` exactly as
before (`cs2_audio_new(files, sample_rate)` -- the engine-agnostic mixer
itself, `cs2_audio_mix`, is pure computation over a `cs2_files*` and
neither knows nor cares whether its caller is isolated), and starts one
`pthread` (`audio_produce`) that loops calling `cs2_audio_mix` into a
1024-frame (4096-byte) chunk and copying it into the ring, using C11
`_Atomic`/`stdatomic.h` on the two position words with
acquire/release ordering across the shared page. The producer only ever
advances the write position and only ever reads the read position; a
full ring backs the thread off with a short sleep rather than blocking,
so a host that stops reading (a paused launch) cannot wedge this thread
either. On the host side, `IsolatedAudioBridge` (`IsolatedRuntimeHost.kt`)
is the consumer: a plain Kotlin thread reading from the mapped
`ByteBuffer` (no atomics needed there -- plain `getInt`/`putInt` is an
accepted, pragmatic simplification for soft-real-time audio, not a
correctness-critical structure) and writing what it reads straight into
a real `AudioTrack` in streaming mode, so the reader hears the game's
own mix with no engine code duplicated on the host side of the
boundary. `IsolatedRuntimeHost.destroy()` and `IsolatedRuntimeService`/`close_session`
on both sides tear the bridge down (stop/join the thread, unmap, close
the fd) whenever the launch itself ends, not only on success.

A host older than API 30, or one that cannot make the shared region at all (or a plugin
built before this addition, or a game that reaches a broken audio path
for its own reasons) still plays: `EngineHost.isolatedAudioBuffer()`
answers `null`, `open_sound_bridged()` logs and returns without ever
touching AAudio, and the game is silent exactly as when no audio device
is available today -- never a hang, because nothing in this path waits
on anything the isolated process cannot reach.

**What else the engine touches, audited against the same question
(does it need this same host-bridging, or does it already avoid
touching a system service the isolated process cannot reach):**

- **Input** (pad, pointer): already fully host-normalized before it
  reaches the plugin. `IsolatedRuntimeHost` reads real input in
  `:runtime` and forwards it over `IEngineRuntimeService.onControllerEvent`/
  `onPointerMove`/`onPointerUp` -- the isolated side never touches
  `InputManager` or a `View`'s own input dispatch at all. No change
  needed.
- **Vibrator**: already bridged. `EngineHost.rumbleController()` crosses
  back over `IEngineRuntimeCallback.rumbleController`, so the isolated
  side asks the host to vibrate rather than holding a `Vibrator` of its
  own. No change needed.
- **Sensors**: not used by CatSystem2 at all (no accelerometer/gyro call
  anywhere in `src/` or `jni.c`). Nothing to bridge for this plugin;
  revisit for a future plugin that does read a sensor.
- **Fonts**: already file-broker-only. `cs2_font_open_beside_via_broker`
  (`src/font.c`) reads a loose font file the same way `cs2_files`/`cs2_save`
  do -- a broker round-trip, no system service (no `FontManager`, no
  `/system/fonts` access) involved either way.

Audio was the one gap: it is the only thing CatSystem2 touches that both
(a) is not already file- or callback-shaped and (b) resolves to a system
service `isolated_app` cannot reach.

**dq-sandbox-04 (both rigs): three platform-specific bugs, none of them
the design being wrong.**

- **API 34 (emulator-5560): ART refuses the whole launch.**
  `SecurityException: Writable dex file '/proc/self/fd/76' is not
  allowed.` ART's dex loader treats a memfd's ordinary read-write mode
  bits as writable no matter how the fd handed to it was itself opened,
  and only trusts `fcntl(fd, F_GET_SEALS)` carrying `F_SEAL_WRITE` as
  proof it will not be written to again -- the same mechanism Android
  itself uses elsewhere for handing over dex/APK content by descriptor.
  `ownedCopy()` (IsolatedRuntimeService.kt) now seals the memfd
  (`F_ADD_SEALS`: `F_SEAL_SEAL|F_SEAL_SHRINK|F_SEAL_GROW|F_SEAL_WRITE`,
  via `Os.fcntlInt`) right after filling it and before the dup it hands
  onward, for native-library copies too, not only dex.
- **Also API 34: the audio bridge itself failed separately**, with
  `NonWritableChannelException` out of `IsolatedAudioBridge.create()`.
  A Java NIO `FileChannel`'s read/write capability comes from which
  stream opened it (`FileInputStream` is always read-only,
  `FileOutputStream` always write-only), never from the underlying fd's
  own `O_RDWR` mode -- so `FileChannel.map(READ_WRITE, ...)` could never
  have worked here regardless of what flags `memfd_create` was given.
  Replaced the mapped `ByteBuffer` with direct `Os.pread`/`Os.pwrite` on
  the fd (both take a plain byte array, operate at the syscall level,
  and are not subject to that Java-side distinction) for the header
  words and the ring data alike.
- **API 28 (BlueStacks): the isolated process died silently mid-run**,
  about 11 seconds into CatSystem2's own frame stepping, with no
  tombstone and nothing else in logcat marking its own end -- the host
  only found out when a subsequent `step()` Binder call failed with
  `DeadObjectException`. The real bug wasn't the death itself (still
  unexplained -- see below) but what the host did with it:
  `frameStep()` funnelled that exception through the exact same silent
  `activity.finish()` an ordinary end-of-game uses, and
  `RuntimeActivity.onDestroy()` deliberately kills this `:runtime`
  process on every finish (each launch owns its own process, success or
  failure) -- so a crash and a clean exit were indistinguishable, and
  with no back-stack destination for the rig's `am start` shortcut, the
  whole app appeared to silently vanish to the home screen. `frameStep`
  now tells the two apart: `svc.step(pixels)` throwing routes through
  the same `onLaunchFailure` path a launch-time error already uses
  (`RuntimeActivity.failAndFinish`, which sets a result extra the
  launch screen reads before finishing), so the reason reaches the
  screen instead of vanishing; only a real `-1` return value (the
  engine's own `EngineStepDriven` contract for "I have ended") still
  finishes silently, since that one is not an error. Also added
  `logIsolatedDeath()`, reading `ActivityManager.getHistoricalProcessExitReasons`
  (`ApplicationExitInfo`, API 30+) for whatever Android itself recorded
  about why the isolated process stopped, since a `DeadObjectException`
  alone never carries more than "remote process probably died" -- this
  is diagnostic-only groundwork: BlueStacks is API 28, below that API,
  so this specific rig still can't get a real answer from it, and the
  actual root cause of the death itself remains open, to be chased with
  whatever `logIsolatedDeath` and the now-visible on-screen failure
  reason turn up on dq-sandbox-05.

All three fixes are host-side only (Enginehost commits `eed4cd1` and
`3e34269` on `main`); nothing changed in `enginehost-catsystem2-plugin`
for this pass. Both new commits hit a real compile-time platform-API
guess wrong on the first try (`Os.fcntlLong` does not exist; the actual
generic fcntl binding is `Os.fcntlInt(FileDescriptor, int, int)`) --
caught by CI, confirmed against developer.android.com's own reference
pages before the fix, not guessed a second time.

**dq-sandbox-06 pass: the seal itself failed, a real error was getting
lost, and the frame-transfer path most likely explains BlueStacks'
still-open death.**

- **`F_ADD_SEALS` itself returned EPERM** on emulator-5560, SELinux
  enforcing. `memfd_create(2)`'s own man page: without `MFD_ALLOW_SEALING`
  at creation, the kernel starts the file with `F_SEAL_SEAL` already
  set, which blocks every later seal addition -- exactly the coordinator's
  own suspicion, confirmed against the man page rather than assumed.
  `Os.memfd_create` now passes `MFD_ALLOW_SEALING` (`0x0002`; not exposed
  as a named constant on `OsConstants` -- confirmed against
  developer.android.com's own reference, only `MFD_CLOEXEC` is there).
- **Fail closed.** `ownedCopy()`'s old fallback -- silently reusing the
  plain received descriptor when the memfd copy/seal failed -- is
  proven broken on any device that reaches it at all: the same SELinux
  policy that motivates sealing in the first place also denies opening
  the bundle's own `classes.dex` by its real `app_data_file`-labelled
  path, the exact avc line dq-sandbox-05 captured. `ownedCopy()` now
  throws instead of returning null, and is called at all only on API
  30+; below that (BlueStacks, API 28, where the writable-dex check does
  not exist and dq-sandbox-03 already proved the plain descriptor works
  fine) the plain descriptor is still used directly, unchanged.
- **Surfaced the swallowed error.** That avc denial's own
  `ClassNotFoundException` reached `JavaBinder:` in logcat -- proof it
  was thrown -- yet `svc.init()` returned normally on the host side, and
  the launch limped on to an unrelated "no picture size" failure
  instead. Root cause: `android.os.Binder` only auto-marshals
  `RemoteException`/`RuntimeException`/`OutOfMemoryError` back across a
  transaction; `ClassNotFoundException` is none of those (it extends
  `ReflectiveOperationException`), so it propagated past that machinery
  uncaught and was simply lost rather than reaching the caller. `init()`'s
  real body moved to a private `initInternal()`; `init()` itself now
  wraps every call in a try/catch that rethrows anything not already a
  `RuntimeException` wrapped in one, so a real cause now actually
  crosses the boundary.
- **The frame-transfer path**, investigated per the coordinator's own
  candidate list for BlueStacks' still-unexplained ~11s isolated death:
  `step()` carried the *entire* frame every call as an AIDL
  `out int[] pixels` array -- CatSystem2's default 1024x576 is ~2.25MB,
  marshalled through Binder's own flat transaction buffer 60 times a
  second. The failure dq-sandbox-04 captured was a *tiny*, unrelated
  104-byte parcel failing right after -- the classic symptom of a
  process's Binder transaction buffer already exhausted by prior large
  ones, not a fresh large transaction itself failing. This is the
  leading candidate for the death, ahead of a memory limit or the
  Layer-1 seccomp filter (audit counters for that are still unchecked;
  this pass fixes the mechanism most directly implicated by the actual
  evidence rather than guessing between all three blind). Frame data
  now crosses through `setFrameBuffer()`, a new AIDL method handing the
  isolated side a plain host-owned file (not memfd: this must work down
  to this app's real minSdk 26, since BlueStacks -- API 28 -- is exactly
  the rig this needs to fix on, unlike the audio ring which can afford
  its API 30+ gate). `step()` itself no longer takes or returns pixel
  data at all; only the changed rows `band` already names are
  `pwrite`/`pread` on either side, at their real byte offset, so this
  moves exactly the same amount of data the row-diff optimization
  already limited it to -- just off Binder and onto a plain file.

Host and isolated sides both touched (`IEngineRuntimeService.aidl`,
`IsolatedRuntimeHost.kt`, `IsolatedRuntimeService.kt`, commit `054b9f4`
on `main`); nothing changed in `enginehost-catsystem2-plugin`, since
`EngineStepDriven.step(int[])`'s own contract for a plugin is unchanged
-- only the isolated glue's own transport for it changed. Every
`android.system.Os`/`OsConstants` surface this pass relies on
(`memfd_create`'s flags parameter, `MFD_ALLOW_SEALING`'s absence from
`OsConstants`, `pread`/`pwrite`) was confirmed against
developer.android.com before writing the code, not guessed -- this
file's own two wrong guesses in the previous two passes
(`SharedMemory.getFileDescriptor`, `Os.fcntlLong`) are the reason.

Still open: BlueStacks' isolated process dying at all remains
unconfirmed as fixed -- moving frames off Binder removes the leading
suspect, but this needs a real rig run to know whether the death is
actually gone, not just less likely.

One candidate is ruled out by inspection, not guesswork: Layer 1's own
seccomp filter (`RuntimeSandbox.apply()`, `runtime_sandbox.c`) was only
ever installed from `EnginehostApplication.onCreate()`'s
`isRuntimeProcess()` check, which compares the process name for exact
equality against `"$packageName:runtime"` -- `":runtime_isolated"` never
matched that, so the isolated process never called `RuntimeSandbox.apply()`
at all and carried no seccomp filter of this app's own making. A memory
limit remains the other open candidate and cannot be confirmed or ruled
out from source alone; dq-sandbox-06 should watch for it directly (a
low-memory kill would typically show as `lmkd` activity in `dmesg`
around the time of death, and -- now that BlueStacks reaches this app's
own `Build.VERSION.SDK_INT < 30` branch of `logIsolatedDeath()` -- confirms
that path ran even though it cannot say more there).

**That rule-out surfaced a real gap, fixed separately (its own commit,
landed after dq-sandbox-06 reports so the two rig runs are not
confused): Layer 2 was narrowing Layer 1's coverage, not adding to it.**
`android:isolatedProcess` gives `:runtime_isolated` a fresh UID and no
permissions of its own, but that is a *Binder/permission* boundary, not
a syscall one -- the process still starts as an ordinary Linux process
that can open an IP socket unless something stops it, exactly like
`:runtime` could before this filter ever existed. Since the filter was
never installed there, the isolated runtime had *less* syscall
filtering than the plain shared-uid runtime it exists to improve on.
`EnginehostApplication.onCreate()` now calls `RuntimeSandbox.apply()`
for both `isRuntimeProcess()` and the new `isIsolatedRuntimeProcess()`
(`":runtime_isolated"`, exact match, mirroring the existing check) --
the other two `isRuntimeProcess()`-gated calls in that method
(`RuntimeInputInstaller`, `RuntimeClassLoader.installBelowApi29`) stay
scoped to `:runtime` only, since both are meaningless in a process that
never hosts an Activity.

No rule in the filter itself needed to change for the isolated side's
own fd-based I/O. `runtime_sandbox.c`'s BPF program is a plain
deny-list -- `socket(AF_INET/AF_INET6)`, `io_uring_setup`, and any
syscall issued from a mismatched architecture are the only paths that
reach `SECCOMP_RET_ERRNO`; every other syscall number falls straight
through to `SECCOMP_RET_ALLOW` at the first check that doesn't match
`__NR_socket` (traced instruction by instruction, not assumed) -- so
`pread`, `pwrite`, `memfd_create`, `fcntl` (the seal calls), and `mmap`
were already unaffected before this change, the same as they already
are, unexamined, on the host side of every one of this milestone's own
mechanisms (`IsolatedAudioBridge`'s `Os.memfd_create`, the frame
buffer's `ParcelFileDescriptor.open`, both sides' `pread`/`pwrite`) that
run under `:runtime`'s copy of this exact filter today without issue.
The filter also returns `EACCES`, never `SIGSYS`/`SECCOMP_RET_KILL`, so
it cannot itself be the source of a process death; a `SIGSYS` on either
process would have to come from the *platform's* own filter underneath
this one (installed by zygote before either of these processes' own
code runs), which this filter does not touch or suppress the kernel's
own audit trail for -- worth remembering if a future rig run needs to
tell the two apart.

**dq-sandbox-06 results: BlueStacks confirmed fixed, emulator-5560 hit
the writable-dex rejection again, with a different signature.**
BlueStacks ran CatSystem2 isolated for 67+ seconds at ~55fps, through
Scenario Select, a cutscene and a CG scene, reproduced twice -- the
frame-transfer fix (`054b9f4`) was the actual cause, confirmed rather
than assumed. Saves are still unverified: the rig could not find how to
open CatSystem2's own Save UI by controller or touch input; noted as a
possible CatSystem2 controls/input-mapping gap for that plugin's own
work, not a sandbox issue, and still needs checking once reachable.

emulator-5560 (API 34) still hit `SecurityException: Writable dex file
'/proc/self/fd/78' is not allowed` -- but this time with *none* of
`ownedCopy()`'s own log lines anywhere in logcat, where dq-sandbox-05's
capture of the same underlying rejection showed its `ErrnoException`
clearly. Since `ownedCopy()` never logged anything on its OWN success
path (only on failure), that silence was genuinely ambiguous: it could
mean the API-30+ gate was skipping the function entirely, or that it
ran, succeeded (no exception), and the resulting sealed memfd was
*still* rejected as writable. Investigated rather than re-guessed a
third time:

- The API gate itself (`Build.VERSION.SDK_INT >= 30`) is correct and
  unconditional; nothing in the code path could skip it on an API 34
  device.
- `ownedCopy()` now logs explicitly at entry and again on success (with
  the sealed fd's `F_GET_SEALS` read back and printed), so this
  ambiguity cannot recur -- a future rig run will say definitively which
  case it hit.
- The likelier explanation, and the one this pass fixes regardless of
  which it turns out to be: **`F_ADD_SEALS`/`F_SEAL_WRITE` only stops
  `write(2)`/`ftruncate(2)` on the memfd going forward -- it does
  nothing to the file's own Unix permission bits.** `memfd_create(2)`
  leaves a freshly created memfd privately read-write for its own
  creator (this process), and a check that asks "is this file writable
  *by me*" via `stat`/`access` rather than `fcntl(F_GET_SEALS)` would
  still see it as writable no matter how thoroughly it is sealed --
  seals and permission bits are two orthogonal mechanisms in Linux.
  `ownedCopy()` now also calls `Os.fchmod(memFd, 0444)` (read-only for
  owner, group and other) right after filling the file and before
  sealing it, so whichever check ART's own loader actually performs --
  seals, permission bits, or both -- is satisfied, rather than betting
  the whole fix on a single guessed mechanism a second time.

Not yet re-run: this needs its own rig pass (folded into a single
dq-sandbox-07 alongside re-confirming BlueStacks and the seccomp
install) before it can be called fixed rather than merely
better-reasoned.

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
