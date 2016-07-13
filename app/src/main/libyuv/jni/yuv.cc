#include <jni.h>
#include <libyuv.h>

#include <android/log.h>
#define LIBYUV_LOGD(...) ((void)__android_log_print(ANDROID_LOG_DEBUG, "libyuv", __VA_ARGS__))
#define LIBYUV_LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO , "libyuv", __VA_ARGS__))
#define LIBYUV_LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN , "libyuv", __VA_ARGS__))
#define LIBYUV_LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, "libyuv", __VA_ARGS__))
#define LIBYUV_ARRAY_ELEMS(a)  (sizeof(a) / sizeof(a[0]))

using namespace libyuv;

struct YuvFrame {
    int width;
    int height;
    uint8_t *data;
    uint8_t *y;
    uint8_t *u;
    uint8_t *v;
};

static JavaVM* jvm;
static JNIEnv *jenv;

static struct YuvFrame i420_rotated_frame;
static struct YuvFrame i420_scaled_frame;
static struct YuvFrame output_frame;

static void libyuv_setOutputResolution(JNIEnv* env, jobject thiz, jint out_width, jint out_height) {
    int y_size = out_width * out_height;

    if (i420_scaled_frame.width != out_width || i420_scaled_frame.height != out_height) {
        free(i420_scaled_frame.data);
        i420_scaled_frame.width = out_width;
        i420_scaled_frame.height = out_height;
        i420_scaled_frame.data = (uint8_t *) malloc(y_size * 3 / 2);
        i420_scaled_frame.y = i420_scaled_frame.data;
        i420_scaled_frame.u = i420_scaled_frame.y + y_size;
        i420_scaled_frame.v = i420_scaled_frame.u + y_size / 4;
    }

    if (output_frame.width != out_width || output_frame.height != out_height) {
        free(output_frame.data);
        output_frame.width = out_width;
        output_frame.height = out_height;
        output_frame.data = (uint8_t *) malloc(out_width * out_height * 3 / 2);
        output_frame.y = output_frame.data;
        output_frame.u = output_frame.y + y_size;
        output_frame.v = output_frame.u + y_size / 4;
    }
}

// For COLOR_FormatYUV420Planar
static jbyteArray libyuv_NV21ToI420(JNIEnv* env, jobject thiz, jbyteArray frame, jint src_width, jint src_height,
                                                        jboolean need_flip, jint rotate_degree) {
	int y_size = src_width * src_height;
    jbyte* input_frame = env->GetByteArrayElements(frame, NULL);

    if (rotate_degree % 180 == 0) {
        if (i420_rotated_frame.width != src_width || i420_rotated_frame.height != src_height) {
            free(i420_rotated_frame.data);
            i420_rotated_frame.width = src_width;
            i420_rotated_frame.height = src_height;
            i420_rotated_frame.data = (uint8_t *) malloc(y_size * 3 / 2);
            i420_rotated_frame.y = i420_rotated_frame.data;
            i420_rotated_frame.u = i420_rotated_frame.y + y_size;
            i420_rotated_frame.v = i420_rotated_frame.u + y_size / 4;
        }
    } else {
        if (i420_rotated_frame.width != src_height || i420_rotated_frame.height != src_width) {
            free(i420_rotated_frame.data);
            i420_rotated_frame.width = src_height;
            i420_rotated_frame.height = src_width;
            i420_rotated_frame.data = (uint8_t *) malloc(y_size * 3 / 2);
            i420_rotated_frame.y = i420_rotated_frame.data;
            i420_rotated_frame.u = i420_rotated_frame.y + y_size;
            i420_rotated_frame.v = i420_rotated_frame.u + y_size / 4;
        }        
    }

    jint ret = ConvertToI420((uint8_t *) input_frame, y_size,
                             i420_rotated_frame.y, i420_rotated_frame.width,
                             i420_rotated_frame.u, i420_rotated_frame.width / 2,
                             i420_rotated_frame.v, i420_rotated_frame.width / 2,
                             0, 0,
                             src_width, src_height,
                             src_width, src_height,
                             (RotationMode) rotate_degree, FOURCC_NV21);
    if (ret < 0) {
        LIBYUV_LOGE("ConvertToI420 failure");
        return NULL;
    }

    ret = I420Scale(i420_rotated_frame.y, i420_rotated_frame.width,
                    i420_rotated_frame.u, i420_rotated_frame.width / 2,
                    i420_rotated_frame.v, i420_rotated_frame.width / 2,
                    need_flip ? -i420_rotated_frame.width : i420_rotated_frame.width, i420_rotated_frame.height,
                    i420_scaled_frame.y, i420_scaled_frame.width,
                    i420_scaled_frame.u, i420_scaled_frame.width / 2,
                    i420_scaled_frame.v, i420_scaled_frame.width / 2,
                    i420_scaled_frame.width, i420_scaled_frame.height,
                    kFilterNone);
    if (ret < 0) {
         LIBYUV_LOGE("I420Scale failure");
         return NULL;
    }

    y_size = output_frame.width * output_frame.height;
    jbyteArray outputFrame = env->NewByteArray(y_size * 3 / 2);
    env->SetByteArrayRegion(outputFrame, 0, y_size * 3 / 2, (jbyte *) output_frame.data);

	env->ReleaseByteArrayElements(frame, input_frame, JNI_ABORT);
	return outputFrame;
}

// For COLOR_FormatYUV420SemiPlanar
static jbyteArray libyuv_NV21ToNV12(JNIEnv* env, jobject thiz, jbyteArray frame, jint src_width, jint src_height,
                                                        jboolean need_flip, jint rotate_degree) {
	int y_size = src_width * src_height;
    jbyte* input_frame = env->GetByteArrayElements(frame, NULL);

    if (rotate_degree % 180 == 0) {
        if (i420_rotated_frame.width != src_width || i420_rotated_frame.height != src_height) {
            free(i420_rotated_frame.data);
            i420_rotated_frame.width = src_width;
            i420_rotated_frame.height = src_height;
            i420_rotated_frame.data = (uint8_t *) malloc(y_size * 3 / 2);
            i420_rotated_frame.y = i420_rotated_frame.data;
            i420_rotated_frame.u = i420_rotated_frame.y + y_size;
            i420_rotated_frame.v = i420_rotated_frame.u + y_size / 4;
        }
    } else {
        if (i420_rotated_frame.width != src_height || i420_rotated_frame.height != src_width) {
            free(i420_rotated_frame.data);
            i420_rotated_frame.width = src_height;
            i420_rotated_frame.height = src_width;
            i420_rotated_frame.data = (uint8_t *) malloc(y_size * 3 / 2);
            i420_rotated_frame.y = i420_rotated_frame.data;
            i420_rotated_frame.u = i420_rotated_frame.y + y_size;
            i420_rotated_frame.v = i420_rotated_frame.u + y_size / 4;
        }
    }

    jint ret = ConvertToI420((uint8_t *) input_frame, y_size,
                             i420_rotated_frame.y, i420_rotated_frame.width,
                             i420_rotated_frame.u, i420_rotated_frame.width / 2,
                             i420_rotated_frame.v, i420_rotated_frame.width / 2,
                             0, 0,
                             src_width, src_height,
                             src_width, src_height,
                             (RotationMode) rotate_degree, FOURCC_NV21);
    if (ret < 0) {
        LIBYUV_LOGE("ConvertToI420 failure");
        return NULL;
    }

    ret = I420Scale(i420_rotated_frame.y, i420_rotated_frame.width,
                    i420_rotated_frame.u, i420_rotated_frame.width / 2,
                    i420_rotated_frame.v, i420_rotated_frame.width / 2,
                    need_flip ? -i420_rotated_frame.width : i420_rotated_frame.width, i420_rotated_frame.height,
                    i420_scaled_frame.y, i420_scaled_frame.width,
                    i420_scaled_frame.u, i420_scaled_frame.width / 2,
                    i420_scaled_frame.v, i420_scaled_frame.width / 2,
                    i420_scaled_frame.width, i420_scaled_frame.height,
                    kFilterNone);
    if (ret < 0) {
         LIBYUV_LOGE("I420Scale failure");
         return NULL;
    }

    ret = ConvertFromI420(i420_scaled_frame.y, i420_scaled_frame.width,
                          i420_scaled_frame.u, i420_scaled_frame.width / 2,
                          i420_scaled_frame.v, i420_scaled_frame.width / 2,
                          output_frame.data, output_frame.width,
                          output_frame.width, output_frame.height,
                          FOURCC_NV12);
    if (ret < 0) {
        LIBYUV_LOGE("ConvertFromI420 failure");
        return NULL;
    }

    y_size = output_frame.width * output_frame.height;
    jbyteArray outputFrame = env->NewByteArray(y_size * 3 / 2);
    env->SetByteArrayRegion(outputFrame, 0, y_size * 3 / 2, (jbyte *) output_frame.data);

	env->ReleaseByteArrayElements(frame, input_frame, JNI_ABORT);
	return outputFrame;
}

static JNINativeMethod libyuv_methods[] = {
	{ "setOutputResolution", "(II)V", (void *)libyuv_setOutputResolution },
	{ "NV21ToI420", "([BIIZI)[B", (void *)libyuv_NV21ToI420 },
	{ "NV21ToNV12", "([BIIZI)[B", (void *)libyuv_NV21ToNV12 },
};

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
	jvm = vm;

	if (jvm->GetEnv((void **) &jenv, JNI_VERSION_1_6) != JNI_OK) {
        LIBYUV_LOGE("Env not got");
		return JNI_ERR;
	}

	jclass clz = jenv->FindClass("net/ossrs/yasea/SrsEncoder");
	if (clz == NULL) {
	    LIBYUV_LOGE("Class \"net/ossrs/yasea/SrsEncoder\" not found");
	    return JNI_ERR;
	}

	if (jenv->RegisterNatives(clz, libyuv_methods, LIBYUV_ARRAY_ELEMS(libyuv_methods))) {
		LIBYUV_LOGE("methods not registered");
        return JNI_ERR;
	}

	return JNI_VERSION_1_6;
}