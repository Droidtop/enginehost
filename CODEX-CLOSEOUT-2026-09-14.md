# September 14 code and build closeout

## Verified release

- Droidtop build 529, commit `2711544aa605dd4e8aef4aeb0b41714fcbf24a46`, CI `34904705771`: SUCCESS, including lint and unit tests. Class-load check inspected 15,439 classes.
- Release APK SHA-256: `2A08C82463332A46750BCADFE986A051FF8D51964DAD70CB8F5DB7463C0DFD20`.
- Application-owned scans survive navigation; the UI observes StateFlow. Added regression coverage for continued scanning with no observer and repeated triggers during an active scan.
- Platform ownership is data-driven (`ownedBy: store`); Steam/Epic are excluded from folder resolution and manual assignment. Shared database commit `c74eba3` is published.

## Follow-up fixes in CI

- Droidtop `8dd9b62c`, run `34914857745`: also filters old store ROM cache rows, excludes stores from generated folders, and inherits bundled ownership for old downloaded platform snapshots. Await final CI result.
- KiriKiri `246a9be` fixed OpenSSL configuration for x86_64. Run `34904710126` passed both dependency builds but failed compiling etcpak.cpp because SSE4.1 intrinsics lacked a header.
- KiriKiri `728afb7` added the guarded smmintrin.h include. Run `34914680012` passed dependencies but exposed missing SIMD tables and AVX2 encoders in the vendored source. `f147d66` gates that incomplete implementation off and uses the existing scalar encoder; run `34915284110` is validating it. Do not claim a three-ABI release until the bundle is verified.
- Droidtop `086907e1`, run `34915418883`: cancels unused lazy scan jobs created by repeated screen triggers; regression now exercises 100 repeats and has bounded waits. Queued behind the previous build.

## Repository hygiene and remaining work

- Droidtop publishes through `github/main`; `origin` remains the older Windows relay. Do not infer staleness from tracking origin alone.
- Plugin and related remotes fetched; active branches fast-forwarded where possible. gamenative-tux and windowcast advanced. Upstream engine refs were fetched without merging them into plugin lines.
- HTML plugin is in an interrupted interactive rebase of `plugin/2.0-2.12` onto `aeb7dc5`. Working tree is clean, no todo commands remain, HEAD `fddd8c9`. Left intact for a branch-history review; its old remote branch is deleted.
- Physical console was not accessed. The documented Windows ADB/emulator paths and BlueStacks process were unavailable during this pass. Device validation remains outstanding.
- Remaining queued work includes Wine in-game menu adoption, CMVS polling/compositor fixes, Enginehost UPDATE_NOW and design-language work, onboarding controller/theme-preview work, and broader game acceptance. CatSystem2 and Buriko are not marked complete by this closeout.
- This file supplements historical owner briefs; it does not replace their evidence or mark unverified device items done.
