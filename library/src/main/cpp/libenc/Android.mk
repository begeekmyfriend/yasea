LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := libenc
LOCAL_SRC_FILES := libenc.cc
LOCAL_CFLAGS    :=
LOCAL_C_INCLUDES += $(LOCAL_PATH)/../libyuv/include $(LOCAL_PATH)/../libx264
LOCAL_STATIC_LIBRARIES := libx264
LOCAL_SHARED_LIBRARIES := libyuv

include $(BUILD_SHARED_LIBRARY)
