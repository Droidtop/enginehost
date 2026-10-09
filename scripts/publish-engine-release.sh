#!/usr/bin/env bash
# Publishes one signed engine bundle (Droidtop/tracker#196): the release for its
# channel, then the permanent history release of the build (Droidtop/tracker#122).
# Run by .github/workflows/publish-engine-bundle.yml, which every plugin
# repository calls; it is the only copy of this logic.
#
# Environment (all required unless noted):
#   GH_TOKEN GH_REPO   token and repository the releases are made in
#   REF_NAME SHA RUN_NUMBER   the build: ref, full commit, workflow run number
#   CHANNEL            stable, testing or unstable
#   REQUESTED_TAG      release tag for the channel release (optional; derived
#                      from the bundle id when empty)
#   RELEASE_DIR        the directory the signed artifact was downloaded into
#
# A build that produced no signed bundle (a branch whose build makes none)
# publishes nothing and succeeds.
set -euo pipefail

[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || { echo "::error::SHA must be a full commit hash, got '$SHA'; refusing to create a release with an unpinned target" >&2; exit 1; }
case "$CHANNEL" in stable|testing|unstable) ;; *) echo "unknown channel $CHANNEL" >&2; exit 1 ;; esac

mkdir -p "$RELEASE_DIR"
bundle="$(find "$RELEASE_DIR" -name '*.enginehost.tar.xz' -print -quit)"
if [ -z "$bundle" ]; then
  echo "This branch's build produced no signed bundle; nothing to publish."
  exit 0
fi
id="$(basename "$bundle" .enginehost.tar.xz)"
envelope="$(find "$RELEASE_DIR" -name 'enginehost-release.json' -print -quit)"
keydoc="$(find "$RELEASE_DIR" -name 'enginehost-public-key.json' -print -quit)"
test -f "$envelope"

# The envelope names its channel; the app filters on it. The signed manifest
# inside is untouched.
python3 - "$envelope" "$CHANNEL" <<'PY'
import json, sys
path, channel = sys.argv[1], sys.argv[2]
data = json.load(open(path))
data["channel"] = channel
json.dump(data, open(path, "w"), indent=2)
PY

tag="${REQUESTED_TAG:-}"
if [ -z "$tag" ]; then
  tag="$(printf '%s' "$id" | sed 's/^dev\.enginehost\.//; s/\./-/g')"
fi
case "$CHANNEL" in
  stable) prerelease="" ;;
  testing) [[ "$tag" == *-testing ]] || tag="$tag-testing"; prerelease="--prerelease" ;;
  unstable) [[ "$tag" == *-unstable ]] || tag="$tag-unstable"; prerelease="--prerelease" ;;
esac
assets=("$bundle" "$envelope")
[ -z "$keydoc" ] || assets+=("$keydoc")

notes="Signed Enginehost bundle \`$id\` on the **$CHANNEL** channel, built from \`$REF_NAME\` at \`$SHA\` (build $RUN_NUMBER). Install it through Enginehost rather than unpacking it by hand: the bundle is verified file by file at install time against the key pinned for this repository."
if [ "$CHANNEL" = unstable ] || [ "$CHANNEL" = testing ]; then
  # Rolling: the newest green build of the line is the whole release.
  gh release delete "$tag" --yes --cleanup-tag 2>/dev/null || true
  gh release create "$tag" "${assets[@]}" --target "$SHA" \
    --title "$id ($CHANNEL)" $prerelease --notes "$notes"
elif gh release view "$tag" >/dev/null 2>&1; then
  gh release upload "$tag" "${assets[@]}" --clobber
  gh release edit "$tag" --notes "$notes"
else
  gh release create "$tag" "${assets[@]}" --target "$SHA" \
    --title "$id ($CHANNEL)" $prerelease --notes "$notes"
fi

# The channel releases above are moving pointers: the next push or promotion
# replaces them, so they are not history. This is the record that a given
# bundle build ever existed, never overwritten except by a rerun of this exact
# workflow run, and it covers a promotion of a commit the push never built.
slug="$(printf '%s' "$id" | sed 's/^dev\.enginehost\.//; s/\./-/g')"
history_tag="${slug}-build.${RUN_NUMBER}"
history_notes="Signed Enginehost bundle \`$id\`, build $RUN_NUMBER, built from \`$REF_NAME\` at \`$SHA\`. This is permanent build history, never overwritten; install from the unstable, testing or stable release instead, whichever tracks what you want."
if gh release view "$history_tag" >/dev/null 2>&1; then
  gh release delete "$history_tag" --yes --cleanup-tag
fi
gh release create "$history_tag" "${assets[@]}" --target "$SHA" \
  --title "$id (build $RUN_NUMBER)" --prerelease --notes "$history_notes"
