cmake_minimum_required(VERSION 3.22.1)
project(enginehost_sandbox C)

# The :runtime process's network filter and its lockdown against a file
# broker in the main process (runtime_sandbox.c over the shared sandbox
# library), the isolated launch's native-library seam
# (isolated_native_bridge.c) and its file layer (isolated_vfs.c,
# isolated_vfs_jni.c): one library, loaded by both runtime processes and,
# for the broker side, by the main process.
add_library(enginehost_sandbox SHARED
    runtime_sandbox.c isolated_native_bridge.c isolated_vfs.c isolated_vfs_jni.c)
# The sandbox library Enginehost shares with droidtop (bi0shacker001/sandbox,
# Droidtop/tracker#470), pinned as a submodule; only its static library is built.
set(SBX_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/sandbox ${CMAKE_CURRENT_BINARY_DIR}/sandbox)
# The isolated file layer's table (isolated_vfs.c) is declared in the header
# plugins copy (plugin-native/enginehost_vfs.h); the host builds against the
# same file.
target_include_directories(enginehost_sandbox PRIVATE ../../../../plugin-native)
target_link_libraries(enginehost_sandbox PRIVATE sbx log)
