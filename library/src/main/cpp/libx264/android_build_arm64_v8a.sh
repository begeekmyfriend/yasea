#!/bin/sh

ANDROID_NDK=$HOME/Android/android-ndk-r14b
SYSROOT=$ANDROID_NDK/platforms/android-21/arch-arm64
CROSS_PREFIX=$ANDROID_NDK/toolchains/aarch64-linux-android-4.9/prebuilt/darwin-x86_64/bin/aarch64-linux-android-
EXTRA_CFLAGS="-march=armv8-a -D__ANDROID__"
EXTRA_LDFLAGS="-nostdlib"
PREFIX=`pwd`/libs/arm64-v8a

./configure --prefix=$PREFIX \
        --host=aarch64-linux \
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
        --disable-lsmash

make clean
make STRIP= -j8 install || exit 1

cp -f $PREFIX/lib/libx264.a $PREFIX
rm -rf $PREFIX/include $PREFIX/lib $PREFIX/pkgconfig
