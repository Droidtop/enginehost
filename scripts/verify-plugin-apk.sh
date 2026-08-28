#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: verify-plugin-apk.sh APK ENGINE [ENGINE...]" >&2
  exit 2
fi

apk=$1
shift
test -s "$apk"

apkanalyzer_bin=${ANDROID_HOME:?ANDROID_HOME is required}/cmdline-tools/latest/bin/apkanalyzer
test -x "$apkanalyzer_bin"
manifest=$($apkanalyzer_bin manifest print "$apk")

grep -Fq 'dev.enginehost.plugin.RUN' <<<"$manifest"
grep -Fq 'dev.enginehost.plugin.pluginVersion' <<<"$manifest"
grep -Fq 'dev.enginehost.plugin.capabilities' <<<"$manifest"

for engine in "$@"; do
  grep -Fq "android:value=\"$engine\"" <<<"$manifest" || {
    echo "APK does not advertise engine $engine" >&2
    exit 1
  }
done

raw_files=$(unzip -Z1 "$apk" | grep -E '^res/raw/.*\.json$' || true)
test -n "$raw_files"
while IFS= read -r raw; do
  unzip -p "$apk" "$raw" | jq -e '
    .schemaVersion == 1 and
    (.capabilities | type == "array" and length > 0) and
    all(.capabilities[];
      (.id | type == "string" and length > 0) and
      (.runtimeVersion | type == "string" and test("^[0-9]+(\\.[0-9]+)+$"))
    )
  ' >/dev/null
done <<<"$raw_files"

echo "Verified enginehost plugin contract: $apk"
