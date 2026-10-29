#!/usr/bin/env bash
# Conformance layer 1b on an emulator (docs/conformance-test-games.md, "Layer 1b"): this
# commit's debug Enginehost, each family's published unstable core, and the MIT test games in
# test-games/, run through the core harness. Called by .github/workflows/conformance-emulator.yml
# once the emulator is up; usable by hand against any emulator with adb.
#
#   conformance-emulator.sh <debug.apk> <folder of *.enginehost.tar.xz> <results folder>
set -euo pipefail

apk=$1
cores=$2
out=$3
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
games_root=/sdcard/EnginehostConformance
mkdir -p "$out"

adb wait-for-device
adb install -r -g "$apk"
# Game folders are read by path, as on a device where the person granted all-files access.
adb shell appops set dev.enginehost MANAGE_EXTERNAL_STORAGE allow
# LOCKDOWN=1: every core runs under the process lockdown, as one that declares it does (#550).
if [ "${LOCKDOWN:-}" = 1 ]; then
    adb shell setprop debug.enginehost.lockdown 1
    echo "The process lockdown is forced on for every core"
fi
# adb is the unrecognized caller; a person allows it once in Settings, here the debug switch does.
adb shell am start -W -n dev.enginehost/.DebugCallerAccessActivity

# Each core through the debug installer, which verifies it as the app does and approves only an
# official signer. It answers in logcat.
for bundle in "$cores"/*.enginehost.tar.xz; do
    name=$(basename "$bundle")
    adb push "$bundle" "/data/local/tmp/$name" >/dev/null
    adb shell chmod 644 "/data/local/tmp/$name"
    adb logcat -c
    adb shell am start -W -a dev.enginehost.debug.INSTALL_BUNDLE --es dev.enginehost.debug.ARCHIVE "/data/local/tmp/$name" >/dev/null
    installed=""
    for _ in $(seq 1 90); do
        log=$(adb logcat -d -s enginehost-debug-install:* 2>/dev/null || true)
        if grep -q "Installed and approved" <<<"$log"; then installed=yes; break; fi
        # The installer refused it: no point waiting out the rest.
        if grep -q " E enginehost-debug-install" <<<"$log"; then break; fi
        sleep 2
    done
    if [ -z "$installed" ]; then
        echo "::error title=Conformance::$name was not installed"
        adb logcat -d > "$out/install-$name.log" || true
        exit 1
    fi
    echo "Installed $name"
done

# The test games, where the harness's games file says.
adb shell mkdir -p "$games_root"
for family in html love2d nscripter; do
    adb push "$repo/test-games/$family" "$games_root/" >/dev/null
done

status=0
# Cores with no sandbox yet (html, love2d) still run: these are our own MIT games, and the report
# names every game that ran unsandboxed.
# LEARN=1: a learning run (the harness sets and resets debug.enginehost.learn itself).
learn=()
[ "${LEARN:-}" = 1 ] && learn=(--learn)
python3 "$here/core_harness.py" --games "$here/conformance-games.json" --out "$out" --first-frame-seconds 120 --play-seconds 15 --run-unsandboxed "${learn[@]}" || status=$?

# The report and each game's evidence, in the job's log: some readers get the log but not the artifact.
cat "$out/report.md" || true
# A learning run's proposed policy: what each core would have been refused.
[ -f "$out/sandbox-proposal.md" ] && { echo "=== sandbox proposal"; cat "$out/sandbox-proposal.md"; } || true
for game in "$out"/*/; do
    echo "=== $(basename "$game")"
    # How much the screen changed idle and after input: the input check's two numbers.
    [ -f "$game/result.json" ] && python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); print("input change:", r.get("input_change"))' "$game/result.json"
    # Each line may find nothing; under set -e that must not end the loop.
    [ -f "$game/host-events.txt" ] && tail -n 25 "$game/host-events.txt" || true
    [ -f "$game/screen-ui.xml" ] && { echo "--- on screen:"; grep -o 'text="[^"]\+"' "$game/screen-ui.xml" | head -n 25; } || true
    [ -f "$game/crash.txt" ] && head -n 40 "$game/crash.txt" || true
    [ -f "$game/logcat.txt" ] && { echo "--- Enginehost errors:"; grep -E " [EF] (Enginehost|enginehost|AndroidRuntime|DEBUG)" "$game/logcat.txt" | tail -n 25; } || true
    # What the sandbox said for this game: lockdown on or not, learning, a trace of an ended runtime.
    [ -f "$game/logcat.txt" ] && { echo "--- the sandbox:"; grep " EnginehostSandbox" "$game/logcat.txt" | tail -n 25; } || true
    # A game that failed a check: the last lines its own processes logged, the engine's among them.
    if [ -f "$game/app-log.txt" ] && ! grep -q '"passed": true' "$game/result.json" 2>/dev/null; then
        echo "--- its processes' log:"; grep -v -E "app_time_stats|AppsFilter" "$game/app-log.txt" | tail -n 80 || true
    fi
    for shot in no-first-frame first-frame input-before input-idle input-after; do
        [ -f "$game/$shot.png" ] && { echo "--- $shot:"; python3 "$here/screen_text.py" "$game/$shot.png" 32; } || true
    done
done
exit $status
