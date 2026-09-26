cmake_minimum_required(VERSION 3.22.1)
project(enginehost_sandbox C)

# The :runtime process's network filter (runtime_sandbox.c) and the
# isolated launch's dex/native-library seam (isolated_native_bridge.c);
# both are small enough to share one library rather than adding a
# second one just to keep them apart.
add_library(enginehost_sandbox SHARED runtime_sandbox.c isolated_native_bridge.c)
