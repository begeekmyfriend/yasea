#!/bin/sh

ANDROID_NDK=$HOME/Android/Sdk/ndk-bundle
SYSROOT=$ANDROID_NDK/platforms/android-21/arch-x86
CROSS_PREFIX=$ANDROID_NDK/toolchains/x86-4.9/prebuilt/linux-x86_64/bin/i686-linux-android-
EXTRA_CFLAGS="-D__ANDROID__ -D__i686__"
EXTRA_LDFLAGS="-nostdlib"
PREFIX=`pwd`/libs/x86

./configure --prefix=$PREFIX \
        --host=i686-linux \
        --sysroot=$SYSROOT \
        --cross-prefix=$CROSS_PREFIX \
        --extra-cflags="$EXTRA_CFLAGS" \
        --extra-ldflags="$EXTRA_LDFLAGS" \
        --enable-pic \
        --enable-static \
        --enable-strip \
        --disable-cli \
        --disable-win32thread \
        --disable-avs \
        --disable-swscale \
        --disable-lavf \
        --disable-ffms \
        --disable-gpac \
        --disable-lsmash \
        --disable-opencl \
        --disable-asm

make clean
make STRIP= -j8 install || exit 1

cp -f $PREFIX/lib/libx264.a $PREFIX
rm -rf $PREFIX/include $PREFIX/lib $PREFIX/pkgconfig
