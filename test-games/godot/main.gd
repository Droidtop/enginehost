# Enginehost conformance test game for the godot plugin.
#
# MIT licence -- test-games/LICENSE.  Godot 4 API only, and the scene builds
# every node at runtime, so the project needs no imported assets and no
# editor pass.  It exercises the three things every conformance game must:
# a frame that changes on its own (the moving square), an input response
# (any key press flips the phase), and a save under user:// that is read
# back on the next launch, so the relaunch check verifies the save
# survived.  user:// is wherever Enginehost mapped the engine's save root.

extends Node2D

const SAVE_PATH := "user://conformance.json"
const SAVE_KEY := "phase"
const WINDOW := Vector2(1280, 720)

var phase := 1
var note := "no save yet"
var time := 0.0
var background: ColorRect
var square: ColorRect
var label: Label


func _ready() -> void:
	background = ColorRect.new()
	background.size = WINDOW
	add_child(background)

	square = ColorRect.new()
	square.color = Color(0.9, 0.8, 0.3)
	square.size = Vector2(48, 48)
	add_child(square)

	label = Label.new()
	label.position = Vector2(16, 16)
	add_child(label)

	load_save()


func _process(delta: float) -> void:
	time += delta
	# The square moves every frame, so the screen is never static.
	square.position = Vector2(fmod(time * 160.0, WINDOW.x), 336.0)
	background.color = Color(0.22, 0.35, 0.6) if phase == 1 else Color(0.6, 0.22, 0.35)
	label.text = "phase %d\n%s\npress any key: flips the phase and saves" % [phase, note]


func _unhandled_input(event: InputEvent) -> void:
	if event is InputEventKey and event.pressed and not event.echo:
		flip_phase()


func flip_phase() -> void:
	phase = 2 if phase == 1 else 1
	var save := FileAccess.open(SAVE_PATH, FileAccess.WRITE)
	if save:
		save.store_string(JSON.stringify({SAVE_KEY: phase}))
		save.close()
		note = "saved phase %d" % phase


func load_save() -> void:
	if not FileAccess.file_exists(SAVE_PATH):
		return
	var save := FileAccess.open(SAVE_PATH, FileAccess.READ)
	if save == null:
		return
	var parsed = JSON.parse_string(save.get_as_text())
	save.close()
	if parsed is Dictionary and parsed.has(SAVE_KEY):
		phase = int(parsed[SAVE_KEY])
		note = "loaded save, phase %d" % phase
