# Unity Mono-vs-IL2CPP and architecture detector: scoping

Step zero of any Unity effort. The 2026-09-25 new-engine census put Unity
at 613 titles, 36.4 percent of the catalog, by far the largest
still-unaddressed bucket -- and could not say how it splits, because the
split is not visible from a store page: 127 rows are flagged `Candidate`
(Mono, on the census's own guess), about 22 say IL2CPP explicitly, and
roughly 480 more sit in `Investigate` until someone opens each title's
own files (the private census synthesis,
research/engine-census-2026-09-25.md, section 3; counts only, the
workbook itself stays out of the repository). The census's
first recommendation was therefore not an engine but this: a static
detector that classifies each title's runtime type (Mono vs IL2CPP),
target architecture and Unity version from the build's contents, before
any Unity runtime work is planned. This page is that detector's scope.
Research and tooling only: no engine code, no host change, no SPEC
change, and committing to a Unity plugin is exactly what this page exists
to prevent doing blind.

The host already reads these facts -- `EngineBuiltinProbes.isUnity`
(classification, from the shared `engines-database.json` row: a
`UnityPlayer.dll`/`.so`/`.dylib` within three directory levels),
`EngineFormats.kt`'s `UnityBuild` (scripting backend, architecture,
version) and `ExecutableArchitecture` (PE and ELF headers), surfaced
through `EngineDetector.unity` as an unhosted family's context, version
and machine, and said plainly by `UnhostedEngine` -- landed as commit
4c3860a, "Detection: read engine runtime versions, and say why Unity
cannot run". That commit is the mechanism this scope builds on; the
detector work is running the same readers over the census's titles, not
writing a second one.

## What each fact is, and where a build states it

A Unity standalone build is a player plus a data folder: the game's own
executable beside `<Name>_Data/`. Unity writes the three facts into that
layout, in files the game ships, without running anything.

**Runtime type.** The scripting backend is a directory-shape fact
(`EngineFormats.kt`, `UnityBuild.read`):

- An **IL2CPP** build carries the compiled game code as a native library
  beside the data folder -- `GameAssembly.dll` on Windows, `GameAssembly.so`
  on Linux, `GameAssembly.dylib` on macOS -- plus metadata under
  `<Name>_Data/il2cpp_data/Metadata/global-metadata.dat`. IL2CPP "converts
  IL (Intermediate Language) to C++, and compiles it into
  platform-specific native code, which is then packaged with your
  application in the standard format for the target platform, such as
  APK/AAB, iOS app bundle, or Windows executable and DLLs"
  (https://docs.unity3d.com/Manual/il2cpp-introduction.html); the tool
  ecosystem reads it exactly as a pair, the il2cpp image plus
  `global-metadata.dat` ("the executable file for the PC platform is
  `GameAssembly.dll` or `*Assembly.dll`",
  https://github.com/Perfare/Il2CppDumper).
- A **Mono** build carries the managed assemblies under
  `<Name>_Data/Managed/` (`Assembly-CSharp.dll` and Unity's own). Mono is
  Unity's JIT .NET runtime fork, and the assemblies are portable IL
  (https://docs.unity3d.com/Manual/scripting-backends-mono.html).
- The folder markers (`_data/il2cpp_data/`, `_data/managed/`) are
  platform-stable, which is why the reader keys on them rather than on
  the `GameAssembly` file name (`.dll` only exists on Windows), and why
  the IL2CPP test runs first: a folder matching both is IL2CPP.

**Architecture.** The player's own image header names the machine, and
every player file in one build names the same one. `ExecutableArchitecture`
already reads the two PC formats: the PE `Machine` field of a `MZ` image
(0x8664 x64, 0x014c i386, 0xAA64 ARM64, 0x01C4 ARM Thumb-2 -- Microsoft
PE specification, "Machine Types",
https://learn.microsoft.com/en-us/windows/win32/debug/pe-format) and the
ELF `e_machine` field (62 AMD x86-64, 3 x86, 183 AArch64, 40 ARM --
https://en.wikipedia.org/wiki/Executable_and_Linkable_Format). The third
PC format is not read yet and the census pass must record it as unknown
rather than guess: Mach-O's `cputype` (7 = x86, 12 = ARM; with the
0x01000000 ABI64 bit those become x86_64 and arm64; magic 0xFEEDFACF for
a 64-bit image, 0xCAFEBABE for a universal file holding several slices --
llvm/BinaryFormat/MachO.h,
https://github.com/llvm/llvm-project/blob/main/llvm/include/llvm/BinaryFormat/MachO.h).
A macOS universal build legitimately holds x86_64 and arm64 slices at
once, so its answer is "both", a case the PE and ELF paths cannot
produce.

**Unity version.** Written as a plain string near the start of every
serialized file: `globalgamemanagers` (5.x and later),
`data.unity3d` (a build compressed into one archive, whose UnityFS
header names it too) or `mainData` (4.x and earlier), each read as its
first 512 bytes and matched against
`(?<![0-9.])(\d{1,4}\.\d{1,2}\.\d{1,3}[abfpx]\d{1,3}(?:c\d{1,3})?)(?![0-9.])`,
which covers the whole shape from `4.7.2f1` through `2017.4.40f1` to
Unity 6's `6000.0.xxf1` form (AssetRipper reads "Unity versions from
`3.5.0` to `6000.4.X`", https://github.com/AssetRipper/AssetRipper). A
build that strips or obfuscates the string answers null; nothing guesses.

## Known limits the census pass must not inherit silently

The in-app reader is aimed at a folder the person picked on a device,
which is not the same question as a whole library survey. Four limits
matter here; each gets a "record unknown, keep going" rule in the pass,
and only real titles hitting one justify changing the app afterwards, as
its own small change:

1. **Classification keys on the split-player layout.** `isUnity` needs a
   `UnityPlayer.dll`/`.so`/`.dylib` within three levels, which modern
   builds ship. Older Unity generations shipped one monolithic player
   executable with no `UnityPlayer` file, so their answer is "not Unity"
   today. The census pass must classify on the data-folder layout
   instead -- a `<Name>_Data/` directory holding `globalgamemanagers` or
   `mainData` -- or those titles vanish from the count. (Whether the
   registry's builtin probe should learn the older shape is a database
   change, decided by whether the pass finds such titles, not by this
   page.)
2. **macOS builds read no architecture** (`ExecutableArchitecture` has
   no Mach-O reader, and `UnityBuild`'s player set is `.dll`/`.so` only;
   a `.app` keeps its player under `Contents/MacOS/`). The pass records
   `unknown` and moves on; a PC library is overwhelmingly Windows and
   Linux builds.
3. **Distributions are not folders yet.** Steam titles arrive as depots
   and installers; the pass reads an *installed* build folder, never a
   setup exe -- the same "unzip the build, read it, run nothing" shape
   the census already prescribes for Ren'Py version recovery. GOG-style
   archive downloads get unpacked to scratch first.
4. **Protected builds may defeat the metadata.** Il2CppDumper's own
   error list warns that "games may obfuscate this file for content
   protection purposes"; some DRM wrappers do the same to the player
   image. The pass records the facts it can read and marks the rest
   unknown per field -- `il2cpp_data/` present but `global-metadata.dat`
   unreadable is still an IL2CPP classification with a null version.

## The census pass: shape, output, and what it is not

**Shape.** `UnityBuild`, `ExecutableArchitecture` and the `GameTree` /
`FileGameTree` readers are deliberately plain JVM code -- `EngineFormats.kt`
imports only `java.nio` and `java.util.zip`, and the `GameTree` half of
`EngineRegistry.kt` only `java.io` and `java.util` (the file's Android
imports belong to its registry half, unused by the tree) -- which is what
makes the census pass a *use* of the one mechanism rather than a
reimplementation: a small harness, living in `scripts/` and outside the
app build, that compiles those readers verbatim, walks each title's
installed folder through `FileGameTree.scan`, and calls the same
`UnityBuild.read` the app calls. The harness writes
nothing into any game folder, runs no engine code, needs no network, and
is not the `./gradlew` path (local builds stay forbidden; the harness is
a desktop tool run where the titles are, not a repo build step).

**Input and output.** Input: each owned census Unity title's installed PC
build -- the wishlist and discovery-sample rows join only once actually
owned and installed. Output: one row per title -- scripting (`mono` / `il2cpp` / `unknown`),
architecture (`x86_64` / `x86` / `arm64` / `arm` / `unknown`), Unity
version, and the file-path evidence for each answer -- written as a
table beside this page when the run happens, so the counts are
reproducible from the listed evidence. That table is the deliverable the
census asked for: the ~480 `Investigate` guesses become real numbers.

**What the numbers are for.** They turn the plugin decision from a guess
into arithmetic:

- **IL2CPP titles are excluded permanently, whatever the count.** IL2CPP
  output is per-title machine code -- the same category as YYC GameMaker
  and Unreal (docs/new-engines/gamemaker-scoping.md) -- with no managed
  layer for anything to interpret. No future finding changes this; the
  Wine/container side of the world owns them, and Enginehost only ever
  says "cannot run it" (`UnhostedEngine`), never assuming droidtop
  exists.
- **Mono titles are the only in-principle addressable set**, and the
  count decides whether the "Unity player" epic the census priced as
  multi-month has an audience worth opening at all. The scripts are
  portable .NET, but they need Unity's closed engine, which ships with
  the game only as the PC player -- the app already says exactly this
  (`unhosted_unity_mono`), and that sentence stays true until a Unity
  runtime for Android exists to package. A census pass that finds, say,
  a large Mono majority does not green-light the epic; it sizes it. A
  near-total IL2CPP/x86_64 result closes the question for good.

If a plugin ever exists, the bundle contract already has the shape for
these facts (docs/engine-bundle-format.md): the detected Unity version
is the `runtimeVersion` vocabulary a capability would advertise by
`supportedSeries`, and the backend is the context -- one capability per
Unity line the plugin actually runs, matched by the same detection
output, never typed by hand. Writing that mapping now would be deciding
the plugin by paperwork; the counts come first.

The detector does not commit to a plugin, and neither does this page.
Detection stays as it is: classification from the registry, enrichment
from `UnityBuild`, the unhosted explanation from `UnhostedEngine`.

## Recommendation

1. Build the offline harness (small: `scripts/`, the existing readers,
   a directory walker and a table writer) and run it over the
   owned census titles' installed builds. It needs the private census
   workbook and the person's own library, so it runs on the owner's
   machine, not from a cloud session; the harness itself is a
   single-worker task.
2. Record the results table beside this page. Update the census's
   section 3 numbers from it, not by hand.
3. In-app changes, if and only if the pass shows real titles needing
   them, each as its own small change with its own evidence: a Mach-O
   reader for macOS architecture; the pre-split-player layout in the
   registry's builtin probe. Neither is part of this scope.
