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
app's private directory. That is the whole rule, enforced by the kernel.
The shape:

- `RuntimeActivity` stays in Enginehost and owns the window. It hands the
  isolated service a `Surface` (or a `SurfaceControlViewHost` package on
  API 30+) to draw into, and forwards input and lifecycle over Binder.
- **Enginehost does the reads.** The service receives the game as a
  read-only file service: the host opens each file the engine asks for,
  inside the game folder only, and passes back a descriptor. Saves go the
  same way into the save folder, which is where the save policy already
  points them. A directory descriptor alone is not enough: files on
  shared storage are checked by the FUSE daemon against the caller's UID,
  so the isolated UID could not open them through it.
- The bundle's own files are passed the same way; the service cannot read
  `files/engine-bundles` either.

What stands in the way, per plugin shape:

- `runtimeTransport: activity` plugins (SDL, Godot's Activity) are
  Activities by construction and must become surface-driven.
- Native engines open game files by POSIX path. Under layer 2 those opens
  must reach the host's file service, through a wrapper `open`/`stat`
  layer or through the v2 host interface's "game folder as a read-only
  file tree" (docs/engine-bundle-format.md, bundle format v2), which is
  where this belongs: one host file service written once, not a shim per
  plugin.
- The WebView expects an app data directory and a normal app process;
  whether it runs in an isolated service is open and must be tried.

So layer 2 arrives with the v2 host interface, and each v1 plugin moves
under it when its wrapper does. Until then layer 1 is what holds.
