#!/bin/sh

ANDROID_NDK=$HOME/Android/android-ndk-r14b
SYSROOT=$ANDROID_NDK/platforms/android-19/arch-mips
CROSS_PREFIX=$ANDROID_NDK/toolchains/mipsel-linux-android-4.9/prebuilt/darwin-x86_64/bin/mipsel-linux-android-
EXTRA_CFLAGS="-D__ANDROID__ -D__mipsel__"
EXTRA_LDFLAGS="-nostdlib"
PREFIX=`pwd`/libs/mips

./configure --prefix=$PREFIX \
        --host=mipsel-linux \
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
