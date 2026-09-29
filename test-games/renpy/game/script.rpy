# Enginehost conformance test game for the renpy plugin.
#
# MIT licence -- test-games/LICENSE.  Written for the conformance suite
# (docs/conformance-test-games.md).  Script language only, no
# python-version-specific constructs, so the same game runs under both the
# Ren'Py 7 and Ren'Py 8 plugin lines.
#
# It exercises the three things every conformance game must: a frame that
# changes on its own (the pulsing circle), an input response (text advance
# and the menu choice), and a save (renpy.save into this game's own save
# directory, wherever Enginehost mapped the engine's save root).

define config.save_directory = "enginehost-conformance-renpy"

image bg a = Solid("#3a5f9a")
image bg b = Solid("#9a3a5f")
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
    "This is the Enginehost conformance test game."
    "The circle keeps pulsing, so the frame keeps changing on its own."
    show dot at pulse
    "Advancing text already needed a click. The next step is a choice."
    menu:
        "Save the game now":
            $ renpy.save("conformance")
            scene bg b
            show dot at pulse
            "Saved. The save lives in this game's own save directory, wherever Enginehost mapped the engine's save root."
        "Do not save":
            scene bg b
            show dot at pulse
            "No save was written."
    "The choice changed the frame. Closing the window on this line is a clean end."
    return
