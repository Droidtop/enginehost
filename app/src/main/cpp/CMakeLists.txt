cmake_minimum_required(VERSION 3.22.1)
project(enginehost_sandbox C)

# The :runtime process's network filter and its lockdown against a file
# broker in the main process (runtime_sandbox.c over sbx/), the
# isolated launch's native-library seam (isolated_native_bridge.c) and its
# file layer (isolated_vfs.c, isolated_vfs_jni.c): one library, loaded by
# both runtime processes and, for the broker side, by the main process.
add_library(enginehost_sandbox SHARED
    runtime_sandbox.c isolated_native_bridge.c isolated_vfs.c isolated_vfs_jni.c
    sbx/sbx_lockdown.c sbx/sbx_broker.c)
# The isolated file layer's table (isolated_vfs.c) is declared in the header
# plugins copy (plugin-native/enginehost_vfs.h); the host builds against the
# same file.
target_include_directories(enginehost_sandbox PRIVATE ../../../../plugin-native)
target_link_libraries(enginehost_sandbox PRIVATE log)
