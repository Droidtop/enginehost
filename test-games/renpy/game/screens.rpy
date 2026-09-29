# The conformance game needs almost nothing from the default template, but
# Ren'Py boots to a main menu, so it needs one screen: a Start button on the
# same background the game uses.

screen main_menu():
    add Solid("#3a5f9a")
    textbutton "Start" action Start():
        xalign 0.5
        yalign 0.5
