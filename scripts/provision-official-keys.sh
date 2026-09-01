#!/usr/bin/env bash
set -euo pipefail

workspace_root="${1:?workspace root required}"
key_root="$workspace_root/_work/signing-keys"
mkdir -p "$key_root/official"
if [[ ! -f "$key_root/official/master-seed.bin" ]]; then
  openssl rand 32 > "$key_root/official/master-seed.bin"
  chmod 600 "$key_root/official/master-seed.bin"
fi
python3 "$workspace_root/scripts/derive-official-key.py" \
  --master-seed "$key_root/official/master-seed.bin" --official-root \
  --output "$key_root/official/root-private.pem"

# Enginehost itself is signed by a dedicated operational subkey. The primary
# key never signs APKs or bundles directly.
mkdir -p "$key_root/enginehost-android"
python3 "$workspace_root/scripts/derive-official-key.py" \
  --master-seed "$key_root/official/master-seed.bin" \
  --application-id dev.enginehost \
  --output "$key_root/enginehost-android/private.pem"

provision_repo() {
  local name="$1" origin="$2" metadata="$3" public_document="$4"
  local temporary="/tmp/enginehost-key-$name"
  mkdir -p "$key_root/$name" "$temporary/payload" "$temporary/out"
  python3 "$workspace_root/scripts/derive-official-key.py" \
    --master-seed "$key_root/official/master-seed.bin" \
    --repository-origin "$origin" --output "$key_root/$name/private.pem"
  : > "$temporary/payload/classes.dex"
  python3 "$workspace_root/scripts/build-engine-bundle.py" \
    --metadata "$metadata" --payload "$temporary/payload" \
    --private-key "$key_root/$name/private.pem" \
    --output "$temporary/out/dummy.enginehost.tar.xz" \
    --public-key-document "$public_document"
  python3 "$workspace_root/scripts/certify-repository-key.py" \
    --official-private-key "$key_root/official/root-private.pem" \
    --repository-key-document "$public_document" \
    --official-public-document "$workspace_root/app/src/main/res/raw/official_plugin_root_key.json"
  chmod 600 "$key_root/$name/private.pem"
}

provision_repo twine \
  https://github.com/droidtop/enginehost-twine-plugin \
  "$workspace_root/_work/twine-standalone/enginehost/bundle-metadata.json" \
  "$workspace_root/_work/twine-standalone/enginehost-public-key.json"
provision_repo flash-air \
  https://github.com/droidtop/enginehost-flash-air-plugin \
  "$workspace_root/_work/enginehost-flash-air-plugin/enginehost/bundle-metadata.json" \
  "$workspace_root/_work/enginehost-flash-air-plugin/enginehost-public-key.json"
provision_repo catsystem2 \
  https://github.com/droidtop/enginehost-catsystem2-plugin \
  "$workspace_root/_work/catsystem2-standalone/enginehost/bundle-metadata.json" \
  "$workspace_root/_work/catsystem2-standalone/enginehost-public-key.json"
provision_repo cmvs \
  https://github.com/droidtop/enginehost-cmvs-plugin \
  "$workspace_root/_work/cmvs-standalone/enginehost/bundle-metadata.json" \
  "$workspace_root/_work/cmvs-standalone/enginehost-public-key.json"
provision_repo godot \
  https://github.com/droidtop/enginehost-godot-plugin \
  "$workspace_root/_work/enginehost-godot-plugin/enginehost/bundle-metadata.json" \
  "$workspace_root/_work/enginehost-godot-plugin/enginehost-public-key.json"
provision_repo buriko \
  https://github.com/droidtop/enginehost-buriko-plugin \
  "$workspace_root/_work/openbgi/enginehost/bundle-metadata.json" \
  "$workspace_root/_work/openbgi/enginehost-public-key.json"
provision_repo rpgmaker-mv-mz \
  https://github.com/droidtop/enginehost-rpgmaker-mv-mz-plugin \
  "$workspace_root/_work/enginehost-plugin-rpgmaker-standalone/enginehost/bundle-metadata.json" \
  "$workspace_root/_work/enginehost-plugin-rpgmaker-standalone/enginehost-public-key.json"

chmod 600 "$key_root/official/root-private.pem"
