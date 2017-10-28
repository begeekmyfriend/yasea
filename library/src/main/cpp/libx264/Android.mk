LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := libx264

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    LOCAL_SRC_FILES := libs/armeabi-v7a/libx264.a
endif

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
    LOCAL_SRC_FILES := libs/arm64-v8a/libx264.a
endif

ifeq ($(TARGET_ARCH_ABI),x86)
    LOCAL_SRC_FILES := libs/x86/libx264.a
endif

include $(PREBUILT_STATIC_LIBRARY)
