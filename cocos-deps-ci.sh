# Build the dependency archives cocos2d-x 3.17.2 imports but does not build.
#
# cocos2d-x is the one library in this tree that does not build its own
# dependencies. Each external/<lib>/CMakeLists.txt declares an IMPORTED static
# library whose IMPORTED_LOCATION is
# external/<lib>/prebuilt/android/${ANDROID_ABI} (platform_spec_path, set at
# cmake/CocosExternalConfig.cmake:33), and the archives at those paths come
# from cocos2d/cocos2d-x-3rd-party-libs-bin. Every tag of that repository on
# the v3-deps line carries three Android ABIs and only three -- armeabi-v7a,
# arm64-v8a, x86 -- at v3-deps-158, which cocos2d-x 3.17.2 pins in
# external/config.json, and still at v3-deps-172, the last v3 tag. There is no
# upstream path to an x86_64 set either: the builder that produced them,
# cocos2d/cocos2d-x-3rd-party-libs-src branch v3, declares
# cfg_all_supported_arches=("armv7" "x86" "arm64" "mips") in build/android.ini
# and gives x86_64 no alias folder, no host triple and no API level. cocos2d-x
# 3.x never shipped Android x86_64.
#
# Patching x86_64 into that android.ini does not get a build either: its
# build.sh drives everything through
# "$ANDROID_NDK/build/tools/make-standalone-toolchain.sh" (build/build.sh:305),
# a script the NDK stopped shipping years before the NDK 25 this project uses,
# and reviving it would mean linking C++ archives from a decade-old toolchain
# into an NDK 25 build. So the VERSIONS come from that builder -- they are what
# pins each archive to the headers already sitting in
# external/<lib>/include/android, which are shared across ABIs and are not
# rebuilt here -- and the NDK this tree already uses does the compiling. Every
# version below cites the rules.mak it is taken from.
#
# Sourced by cross_android.sh, which sets PLATFORM, ABI, API, TRIPLE, NDK_HOME,
# CORE_NUM, CMAKELISTS_PATH and COCOS2DX_SRC.

COCOSDEPS_SRC=$CMAKELISTS_PATH/thirdparty/port/cocos_deps

cocosdeps_fetch_tar() # url, directory it unpacks to
{
    if [ -d "$COCOSDEPS_SRC/$2" ]; then return 0; fi
    echo "## cocos_deps fetch $1"
    mkdir -p $COCOSDEPS_SRC
    local name=$(basename "$1")
    curl --fail --location --retry 5 --output "$COCOSDEPS_SRC/$name" "$1" || return 1
    case "$name" in
        *.tar.xz|*.txz) tar xJf "$COCOSDEPS_SRC/$name" -C $COCOSDEPS_SRC ;;
        *)              tar xzf "$COCOSDEPS_SRC/$name" -C $COCOSDEPS_SRC ;;
    esac
    rm -f "$COCOSDEPS_SRC/$name"
}

cocosdeps_fetch_git() # url, ref, directory
{
    if [ -d "$COCOSDEPS_SRC/$3" ]; then return 0; fi
    echo "## cocos_deps fetch $1 @$2"
    mkdir -p $COCOSDEPS_SRC/$3
    git init -q $COCOSDEPS_SRC/$3
    # A full revision, never an abbreviation: a fetch by object name is served
    # only when the name is complete.
    git -C $COCOSDEPS_SRC/$3 fetch -q --depth 1 "$1" "$2" || return 1
    git -C $COCOSDEPS_SRC/$3 checkout -q FETCH_HEAD
}

cocosdeps_cmake() # source directory, then cache entries
{
    local src=$1; shift
    local build=$COCOSDEPS_SRC/$src/build_$PLATFORM
    mkdir -p $build
    # CMAKE_POLICY_VERSION_MINIMUM: every one of these projects declares a
    # cmake_minimum_required from the 2.x era, which CMake 3.31 warns about and
    # CMake 4 refuses outright. The flag says "read them as though they had
    # asked for 3.5" and is the supported way to configure an old tree.
    cmake -S $COCOSDEPS_SRC/$src -B $build -G "Unix Makefiles" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE=$NDK_HOME/build/cmake/android.toolchain.cmake \
        -DANDROID_PLATFORM=$API -DANDROID_ABI=$ABI \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DBUILD_SHARED_LIBS=OFF \
        "$@" || return 1
    make -C $build -j$CORE_NUM || return 1
}

cocosdeps_autotools() # source directory, then configure arguments
{
    local src=$1; shift
    local dir=$COCOSDEPS_SRC/$src
    # tiff 4.0.3 predates android as a system name in config.sub, which stops
    # its configure before it starts (system android not recognized), so give
    # both projects the config.sub and config.guess of whichever automake the
    # runner has. The CI job installs automake for exactly this.
    local auxdir
    for auxdir in $(find $dir -name config.sub -printf '%h\n'); do
        cp -f /usr/share/automake-*/config.sub   $auxdir/config.sub
        cp -f /usr/share/automake-*/config.guess $auxdir/config.guess
    done
    mkdir -p $dir/build_$PLATFORM
    pushd $dir/build_$PLATFORM || return 1
    ../configure --host=$TRIPLE \
        CC=$TRIPLE$API-clang CXX=$TRIPLE$API-clang++ \
        AR=llvm-ar RANLIB=llvm-ranlib NM=llvm-nm STRIP=llvm-strip \
        --enable-static --disable-shared --with-pic "$@" \
        && make -j$CORE_NUM
    local rc=$?
    popd
    return $rc
}

cocosdeps_stage() # cocos2d-x external directory, archive built, name to stage it as
{
    local dest=$COCOS2DX_SRC/external/$1/prebuilt/android/$ABI
    mkdir -p $dest
    cp -f "$2" "$dest/$3" || return 1
    echo "## staged $1/prebuilt/android/$ABI/$3"
}

# zlib 1.2.8 (contrib/src/zlib/rules.mak: ZLIB_VERSION := 1.2.8)
cocosdeps_zlib()
{
    cocosdeps_fetch_tar http://zlib.net/fossils/zlib-1.2.8.tar.gz zlib-1.2.8 || return 1
    cocosdeps_cmake zlib-1.2.8 || return 1
    cocosdeps_stage zlib $COCOSDEPS_SRC/zlib-1.2.8/build_$PLATFORM/libz.a libz.a
}

# libpng 1.6.16 (contrib/src/png/rules.mak: PNG_VERSION := 1.6.16)
cocosdeps_png()
{
    cocosdeps_fetch_tar https://downloads.sourceforge.net/project/libpng/libpng16/older-releases/1.6.16/libpng-1.6.16.tar.xz libpng-1.6.16 || return 1
    local zsrc=$COCOSDEPS_SRC/zlib-1.2.8
    # zconf.h is generated into the zlib build directory, so both go on the
    # include path.
    # SKIP_INSTALL_ALL: libpng creates a libpng.a symlink beside the archive
    # it installs and reads the target LOCATION property to find it, which
    # CMake has refused since 3.0 (CMP0026). Nothing here installs -- the
    # archive is copied straight into cocos2d-x -- so the whole install block
    # goes, rather than reviving a policy for one symlink we do not want.
    cocosdeps_cmake libpng-1.6.16 \
        -DPNG_SHARED=OFF -DPNG_STATIC=ON -DPNG_TESTS=OFF -DSKIP_INSTALL_ALL=ON \
        -DZLIB_INCLUDE_DIR="$zsrc;$zsrc/build_$PLATFORM" \
        -DZLIB_LIBRARY=$zsrc/build_$PLATFORM/libz.a || return 1
    # The static target is png16_static and its archive libpng16.a; cocos2d-x
    # imports it as libpng.a.
    cocosdeps_stage png $COCOSDEPS_SRC/libpng-1.6.16/build_$PLATFORM/libpng16.a libpng.a
}

# freetype 2.5.5 (contrib/src/freetype/rules.mak: FREETYPE2_VERSION := 2.5.5)
cocosdeps_freetype()
{
    cocosdeps_fetch_tar https://downloads.sourceforge.net/project/freetype/freetype2/2.5.5/freetype-2.5.5.tar.gz freetype-2.5.5 || return 1
    cocosdeps_cmake freetype-2.5.5 \
        -DWITH_ZLIB=OFF -DWITH_BZip2=OFF -DWITH_PNG=OFF -DWITH_HarfBuzz=OFF || return 1
    cocosdeps_stage freetype2 $COCOSDEPS_SRC/freetype-2.5.5/build_$PLATFORM/libfreetype.a libfreetype.a
}

# Chipmunk 7.0.1 (contrib/src/chipmunk/rules.mak: CHIPMUNK_VERSION := 7.0.1).
# chipmunk-physics.net no longer serves the release tarball; the tag of the
# same name in the upstream repository is that same source.
cocosdeps_chipmunk()
{
    cocosdeps_fetch_git https://github.com/slembcke/Chipmunk2D Chipmunk-7.0.1 Chipmunk2D || return 1
    # cpHastySpace.c includes sys/sysctl.h at the top of the file and uses it
    # in one place, inside an ifdef __APPLE__, to ask sysctlbyname how many
    # cores there are. bionic dropped that header years ago, so on any current
    # NDK the file stops on the include alone. Take the include out: nothing
    # outside that Apple branch refers to it, and this is never built for
    # Apple. The prebuilt sets cocos2d-x ships predate the removal, which is
    # why nobody met this before.
    sed -i "s|^#include <sys/sysctl.h>|// removed: bionic has no sys/sysctl.h, and only the __APPLE__ branch below used it|" \
        $COCOSDEPS_SRC/Chipmunk2D/src/cpHastySpace.c
    cocosdeps_cmake Chipmunk2D \
        -DBUILD_DEMOS=OFF -DINSTALL_DEMOS=OFF \
        -DBUILD_SHARED=OFF -DBUILD_STATIC=ON -DINSTALL_STATIC=OFF || return 1
    cocosdeps_stage chipmunk $COCOSDEPS_SRC/Chipmunk2D/build_$PLATFORM/src/libchipmunk.a libchipmunk.a
}

# bullet at 19f999a (contrib/src/bullet/rules.mak: a download_git of
# bulletphysics/bullet3, master, 19f999a), written out in full because a fetch
# by object name needs the whole name.
cocosdeps_bullet()
{
    cocosdeps_fetch_git https://github.com/bulletphysics/bullet3 \
        19f999ac087e68ffc2551ffb73e35e60271a0d27 bullet3 || return 1
    # BUILD_MULTITHREADING is what adds MiniCL and BulletMultiThreaded
    # (src/CMakeLists.txt), and this engine links both through cocos2d-x.
    cocosdeps_cmake bullet3 \
        -DBUILD_DEMOS=OFF -DBUILD_CPU_DEMOS=OFF -DBUILD_EXTRAS=OFF \
        -DBUILD_UNIT_TESTS=OFF -DUSE_GLUT=OFF \
        -DUSE_GRAPHICAL_BENCHMARK=OFF -DBUILD_MULTITHREADING=ON || return 1
    local b=$COCOSDEPS_SRC/bullet3/build_$PLATFORM/src
    cocosdeps_stage bullet $b/BulletCollision/libBulletCollision.a         libBulletCollision.a
    cocosdeps_stage bullet $b/BulletDynamics/libBulletDynamics.a           libBulletDynamics.a
    cocosdeps_stage bullet $b/LinearMath/libLinearMath.a                   libLinearMath.a
    cocosdeps_stage bullet $b/BulletMultiThreaded/libBulletMultiThreaded.a libBulletMultiThreaded.a
    cocosdeps_stage bullet $b/MiniCL/libMiniCL.a                           libMiniCL.a
}

# tiff 4.0.3 (contrib/src/tiff/rules.mak: TIFF_VERSION := 4.0.3). CMake support
# arrives in 4.0.5, after this release, so autotools.
cocosdeps_tiff()
{
    cocosdeps_fetch_tar http://download.osgeo.org/libtiff/old/tiff-4.0.3.tar.gz tiff-4.0.3 || return 1
    cocosdeps_autotools tiff-4.0.3 \
        --disable-lzma --disable-jbig --disable-cxx --without-x || return 1
    cocosdeps_stage tiff $COCOSDEPS_SRC/tiff-4.0.3/build_$PLATFORM/libtiff/.libs/libtiff.a libtiff.a
}

# libwebp 0.5.0 (contrib/src/webp/rules.mak: WEBP_VERSION := 0.5.0). Its CMake
# build arrives after this release, so autotools.
cocosdeps_webp()
{
    cocosdeps_fetch_tar http://downloads.webmproject.org/releases/webp/libwebp-0.5.0.tar.gz libwebp-0.5.0 || return 1
    cocosdeps_autotools libwebp-0.5.0 \
        --disable-libwebpmux --disable-libwebpdemux --disable-libwebpdecoder \
        --disable-gl --disable-sdl --disable-png --disable-jpeg \
        --disable-tiff --disable-gif --disable-wic || return 1
    cocosdeps_stage webp $COCOSDEPS_SRC/libwebp-0.5.0/build_$PLATFORM/src/.libs/libwebp.a libwebp.a
}

# Build every archive cocos2d-x would otherwise have imported from a prebuilt
# set, then say plainly whether the set is complete. The names checked for are
# taken from the x86 directory cocos2d-x does ship, so a library that failed
# becomes a name in one message here rather than a link error four libraries
# later. One failure does not stop the others: the rest of the tree is still
# worth having, and what is missing is named.
build_cocos_deps()
{
    local lib rc=0
    for lib in zlib png freetype chipmunk bullet tiff webp; do
        echo "##### cocos_deps: $lib ($ABI) #####"
        if ! cocosdeps_$lib; then
            echo "::warning::cocos2d-x dependency $lib failed to build for $ABI" >&2
            rc=1
        fi
    done

    # x86 is the 32-bit half of this same architecture and the closest thing
    # upstream ships to what is being produced here, so its file list is the
    # answer to what belongs in this directory.
    local want got missing=""
    for lib in zlib png tiff webp freetype2 chipmunk bullet; do
        for want in $(ls $COCOS2DX_SRC/external/$lib/prebuilt/android/x86/*.a 2>/dev/null); do
            got=$COCOS2DX_SRC/external/$lib/prebuilt/android/$ABI/$(basename $want)
            [ -f "$got" ] || missing="$missing $lib/$(basename $want)"
        done
    done
    if [ -n "$missing" ]; then
        echo "::error::cocos2d-x dependencies missing for $ABI:$missing" >&2
        return 1
    fi
    echo "## cocos2d-x dependency set for $ABI is complete"
    return $rc
}
