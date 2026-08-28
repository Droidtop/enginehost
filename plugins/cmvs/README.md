# Experimental native CMVS plugin

This first runtime reads CMVS `PS2A` scripts in place. It validates all lengths,
decrypts and LZ-decompresses PS2 scripts, resolves PS3 dialogue string references,
decodes Shift-JIS, and presents the resulting dialogue with tap-to-advance.

It does not yet execute general CMVS opcodes, choices, graphics, audio, CPZ
archives, or save state. Games currently need extracted `.ps2`/`.ps3` scripts.
Capability `ps2-ps3-dialogue` is intentionally experimental and narrow.

The format work is based on the MIT-licensed `xmoezzz/CMVS-Engine` PS2 and PS3
text dumpers. The implementation here is new bounds-checked Java code; no
proprietary CMVS runtime or game data is included. Android builds run only in CI.
