# Created by jianxi on 2017/6/4
# https://github.com/mabeijianxi
# mabeijianxi@gmail.com
# Edit by obarong on 2020/4/15

chmod a+x android_*.sh

# Build armeabi
#./android_build_armeabi.sh

# Build arm v6 v7a
./android_build_armeabi_v7a.sh

# Build arm64 v8a
./android_build_arm64_v8a.sh

# Build mips
./android_build_mips.sh

# Build x86
./android_build_x86.sh

# Build x86_64
#./android_x86_64_build.sh
