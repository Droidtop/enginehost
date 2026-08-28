# Experimental Buriko/Ethornell plugin

This is a real native interpreter plugin built from
[OpenBGI](https://github.com/Cytlan/openbgi), pinned at commit
`e2500e0badd04aab472e4620477747973e5e5544`, and official SDL2 source.
It does not invoke the original Windows executable or Wine.

OpenBGI is intentionally incomplete: upstream currently reaches menus and
settings in at least one title, implements a growing subset of VM/graphics
opcodes, and does not yet implement sound. This plugin advertises only the
experimental `compiled-script-v1` / `1.0` capability. Later plugin versions can
expand support as OpenBGI evolves.

OpenBGI currently expects resources already present as extracted directories;
the plugin never extracts, imports, or copies them. Games still packed only in
BGI archives are not supported by this first version.

OpenBGI is GPL-2.0 and preserves its `LICENSE` and `THIRD_PARTY` notices in the
pinned source. SDL is zlib-licensed. Android builds run only in CI.
