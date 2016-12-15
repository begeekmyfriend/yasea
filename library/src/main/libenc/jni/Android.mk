LOCAL_PATH := $(call my-dir)

############# prebuilt ###############

include $(CLEAR_VARS)
LOCAL_MODULE := libyuv

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    LOCAL_SRC_FILES := libs/armeabi-v7a/libyuv.so
endif

ifeq ($(TARGET_ARCH_ABI),x86)
    LOCAL_SRC_FILES := libs/x86/libyuv.so
endif

include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libx264

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    LOCAL_SRC_FILES := libs/armeabi-v7a/libx264.a
endif

ifeq ($(TARGET_ARCH_ABI),x86)
    LOCAL_SRC_FILES := libs/x86/libx264.a
endif

include $(PREBUILT_STATIC_LIBRARY)

############# build libenc ###########
include $(CLEAR_VARS)

LOCAL_MODULE := libenc
LOCAL_SRC_FILES := libenc.cc
LOCAL_CFLAGS    :=
LOCAL_LDLIBS    := -llog
LOCAL_C_INCLUDES += $(LOCAL_PATH)/libyuv/jni/include $(LOCAL_PATH)/libx264
LOCAL_STATIC_LIBRARIES := libx264
LOCAL_SHARED_LIBRARIES := libyuv
LOCAL_DISABLE_FORMAT_STRING_CHECKS := true
LOCAL_DISABLE_FATAL_LINKER_WARNINGS := true
include $(BUILD_SHARED_LIBRARY)
