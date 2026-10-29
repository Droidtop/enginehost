# The conformance game needs almost nothing from the default template, but
# Ren'Py boots to a main menu, so it needs one screen: a Start button on a
# dark grey background, so that starting the game (blue) changes the screen.

screen main_menu():
    add Solid("#202020")
    textbutton "Start" action Start():
        xalign 0.5
        yalign 0.5
