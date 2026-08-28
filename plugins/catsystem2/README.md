# Experimental CatSystem2 CST plugin

This first native Android interpreter reads `CatScene` CST scripts in place,
validates their tables and decompression bounds, decodes Shift-JIS names and
dialogue, and implements input/page waits. Unsupported CatSystem2 commands are
currently skipped, so graphics, audio, branching, archive access, and FES UI
scripts remain future plugin work.

The format implementation follows the publicly documented CatScene structure
and line types from the TriggersTools.CatSystem2 research project. No code from
the proprietary CatSystem2 runtime is included. The plugin currently requires
already-extracted `.cst` files and never extracts or copies game content.

Capability `cst` / `2.0` is experimental and intentionally narrow. Android
builds run only in CI.
