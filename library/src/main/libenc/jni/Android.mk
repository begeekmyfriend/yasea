LOCAL_PATH := $(call my-dir)

############# prebuilt ###############
include $(CLEAR_VARS)
LOCAL_MODULE := libyuv
LOCAL_SRC_FILES := lib/libyuv.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libx264
LOCAL_SRC_FILES := lib/libx264.a
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
include $(BUILD_SHARED_LIBRARY)
