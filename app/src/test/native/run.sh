#!/bin/sh
# Builds and runs the isolated file layer's end-to-end test (vfs_test_engine.c)
# with the host's C compiler: isolated_vfs.c and the test broker go into an
# unwrapped shared library, as they do in libenginehost_sandbox.so, and the
# "engine" is linked with the forwarder and the same --wrap list
# plugin-native/enginehost_vfs.cmake gives every plugin.
set -eu
root=$(cd "$(dirname "$0")/../../../.." && pwd)
out=${1:-${TMPDIR:-/tmp}/enginehost-vfs-test}
mkdir -p "$out"
cc=${CC:-cc}
flags="-std=c11 -O1 -g -Wall -Wextra -Werror -Wno-unused-parameter -Wno-format-truncation -I$root/plugin-native -I$root/app/src/main/cpp"

wraps=$(sed -n '/^set(ENGINEHOST_VFS_WRAPPED/,/)/p' "$root/plugin-native/enginehost_vfs.cmake" \
    | sed 's/set(ENGINEHOST_VFS_WRAPPED//; s/)//' | tr -s ' \n' '\n' | sed '/^$/d' \
    | sed 's/^/-Wl,--wrap=/' | tr '\n' ' ')

$cc $flags -fPIC -shared -o "$out/libehvfs.so" \
    "$root/app/src/main/cpp/isolated_vfs.c" "$root/app/src/test/native/vfs_test_broker.c" -lpthread
# shellcheck disable=SC2086
$cc $flags -o "$out/vfs_test" \
    "$root/app/src/test/native/vfs_test_engine.c" "$root/plugin-native/enginehost_vfs_forward.c" \
    $wraps -L"$out" -lehvfs -Wl,-rpath,"$out" -lpthread
"$out/vfs_test"

# The audio ring writer plugins copy (plugin-native/enginehost_audio_ring.c).
$cc $flags -o "$out/audio_ring_test" \
    "$root/app/src/test/native/audio_ring_test.c" "$root/plugin-native/enginehost_audio_ring.c"
"$out/audio_ring_test"

# The process lockdown and its broker (app/src/main/cpp/sbx), on this host's kernel.
$cc $flags -o "$out/sbx_test"     "$root/app/src/test/native/sbx_test.c" "$root/app/src/main/cpp/sbx/sbx_lockdown.c"     "$root/app/src/main/cpp/sbx/sbx_broker.c" -lpthread
TMPDIR="$out" "$out/sbx_test"
