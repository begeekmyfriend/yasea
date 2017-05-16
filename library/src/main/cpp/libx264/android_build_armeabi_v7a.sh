#!/bin/sh

ANDROID_NDK=$HOME/Android/Sdk/ndk-bundle
SYSROOT=$ANDROID_NDK/platforms/android-19/arch-arm
CROSS_PREFIX=$ANDROID_NDK/toolchains/arm-linux-androideabi-4.9/prebuilt/linux-x86_64/bin/arm-linux-androideabi-
EXTRA_CFLAGS="-march=armv7-a -mfloat-abi=softfp -mfpu=neon -D__ANDROID__ -D__ARM_ARCH_7__ -D__ARM_ARCH_7A__"
EXTRA_LDFLAGS="-nostdlib"
PREFIX=`pwd`/libs/armeabi-v7a

./configure --prefix=$PREFIX \
        --host=arm-linux \
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
        --disable-opencl

make clean
make STRIP= -j8 install || exit 1

cp -f $PREFIX/lib/libx264.a $PREFIX
rm -rf $PREFIX/include $PREFIX/lib $PREFIX/pkgconfig
