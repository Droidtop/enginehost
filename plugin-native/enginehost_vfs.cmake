# Routes an engine library's libc file calls through Enginehost's isolated
# file layer (enginehost_vfs.h). Usage, after the target exists:
#
#   include(path/to/enginehost_vfs.cmake)
#   enginehost_vfs(<target>)
#
# --wrap rewrites the target's own undefined references at link time,
# static archives linked into it included, so vendored decoders are
# covered too. Calls made from inside another shared library (a
# libc++_shared.so, a separately loaded SDL) are not: link those
# statically into the target that uses this.

set(ENGINEHOST_VFS_DIR ${CMAKE_CURRENT_LIST_DIR})

set(ENGINEHOST_VFS_WRAPPED
    open open64 openat openat64 __open_2 __openat_2 creat close
    fopen fopen64 fclose
    stat stat64 lstat lstat64 fstatat fstatat64 access faccessat
    opendir readdir readdir64 closedir rewinddir dirfd scandir
    mkdir rmdir unlink unlinkat remove rename
    chdir getcwd realpath
    exit)

function(enginehost_vfs target)
    target_sources(${target} PRIVATE ${ENGINEHOST_VFS_DIR}/enginehost_vfs_forward.c)
    target_include_directories(${target} PRIVATE ${ENGINEHOST_VFS_DIR})
    foreach(symbol IN LISTS ENGINEHOST_VFS_WRAPPED)
        target_link_options(${target} PRIVATE "LINKER:--wrap=${symbol}")
    endforeach()
endfunction()
