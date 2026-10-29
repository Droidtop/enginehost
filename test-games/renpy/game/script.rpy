# Enginehost conformance test game for the renpy plugin.
#
# MIT licence -- test-games/LICENSE.  Written for the conformance suite
# (docs/conformance-test-games.md).  Script language only, no
# python-version-specific constructs, so the same game runs under both the
# Ren'Py 7 and Ren'Py 8 plugin lines.
#
# It exercises the three things every conformance game must: a frame that
# changes on its own (the pulsing circle), an input response (Start on the
# grey main menu turns the screen blue), and a save: every advance after the
# first line writes renpy.save into this game's own save directory, wherever
# Enginehost mapped the engine's save root, so a save is one advance however
# many clicks came before it (it replaced a menu choice a harness had to
# count its way to).

define config.save_directory = "enginehost-conformance-renpy"

image bg a = Solid("#3a5f9a")
image dot = Text("o")

transform pulse:
    xalign 0.5
    yalign 0.4
    zoom 1.0
    linear 0.5 zoom 1.6
    linear 0.5 zoom 1.0
    repeat

label start:
    scene bg a
    show dot at pulse
    "This is the Enginehost conformance test game. The circle keeps pulsing, so the frame keeps changing on its own."

label save_loop:
    $ renpy.save("conformance")
    "Saved. Each advance from here saves the game again."
    jump save_loop
