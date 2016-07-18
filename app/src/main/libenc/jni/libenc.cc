#include <jni.h>
#include <libyuv.h>
#include <x264.h>

#include <android/log.h>
#define LIBENC_LOGD(...) ((void)__android_log_print(ANDROID_LOG_DEBUG, "libenc", __VA_ARGS__))
#define LIBENC_LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO , "libenc", __VA_ARGS__))
#define LIBENC_LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN , "libenc", __VA_ARGS__))
#define LIBENC_LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, "libenc", __VA_ARGS__))

#define LIBENC_ARRAY_ELEMS(a)  (sizeof(a) / sizeof(a[0]))

using namespace libyuv;

struct YuvFrame {
    int width;
    int height;
    uint8_t *data;
    uint8_t *y;
    uint8_t *u;
    uint8_t *v;
};

typedef struct x264_context {
    // encode parameter
    x264_param_t params;
    x264_t *encoder;
    x264_picture_t picture;
    // sei buffer
    uint8_t sei[256];
    int sei_size;
    // input
    int width;
    int height;
    int bitrate;
    int fps;
    int gop;
    // output
    int pts;
    int dts;
    bool is_key_frame;
} x264_context;

static JavaVM* jvm;
static JNIEnv *jenv;

static struct x264_context x264_ctx;
static uint8_t h264_es[1024 * 1024];

static struct YuvFrame i420_rotated_frame;
static struct YuvFrame i420_scaled_frame;
static struct YuvFrame nv12_frame;

static bool nv21_to_i420(jbyte *nv21_frame, jint src_width, jint src_height,
                         jboolean need_flip, jint rotate_degree) {
    int y_size = src_width * src_height;

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

    jint ret = ConvertToI420((uint8_t *) nv21_frame, y_size,
                             i420_rotated_frame.y, i420_rotated_frame.width,
                             i420_rotated_frame.u, i420_rotated_frame.width / 2,
                             i420_rotated_frame.v, i420_rotated_frame.width / 2,
                             0, 0,
                             src_width, src_height,
                             src_width, src_height,
                             (RotationMode) rotate_degree, FOURCC_NV21);
    if (ret < 0) {
        LIBENC_LOGE("ConvertToI420 failure");
        return false;
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
         LIBENC_LOGE("I420Scale failure");
         return false;
    }

    return true;
}

static void libenc_setOutputBitrate(JNIEnv* env, jobject thiz, jint bitrate) {
    // Default setting i_rc_method as X264_RC_CRF which is better than X264_RC_ABR
    x264_ctx.bitrate = bitrate / 1000;  // kbps
}

static void libenc_setOutputFps(JNIEnv* env, jobject thiz, jint fps) {
    x264_ctx.fps = fps;
}

static void libenc_setOutputGop(JNIEnv* env, jobject thiz, jint gop_size) {
    x264_ctx.gop = gop_size;
}

static void libenc_setOutputResolution(JNIEnv* env, jobject thiz, jint out_width, jint out_height) {
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

    if (nv12_frame.width != out_width || nv12_frame.height != out_height) {
        free(nv12_frame.data);
        nv12_frame.width = out_width;
        nv12_frame.height = out_height;
        nv12_frame.data = (uint8_t *) malloc(y_size * 3 / 2);
        nv12_frame.y = nv12_frame.data;
        nv12_frame.u = nv12_frame.y + y_size;
        nv12_frame.v = nv12_frame.u + y_size / 4;
    }

    x264_ctx.width = out_width;
    x264_ctx.height = out_height;
}

// For COLOR_FormatYUV420Planar
static jbyteArray libenc_NV21ToI420(JNIEnv* env, jobject thiz, jbyteArray frame, jint src_width,
                                    jint src_height, jboolean need_flip, jint rotate_degree) {
    jbyte* nv21_frame = env->GetByteArrayElements(frame, NULL);

    if (!nv21_to_i420(nv21_frame, src_width, src_height, need_flip, rotate_degree)) {
        return NULL;
    }

    int y_size = i420_scaled_frame.width * i420_scaled_frame.height;
    jbyteArray i420Frame = env->NewByteArray(y_size * 3 / 2);
    env->SetByteArrayRegion(i420Frame, 0, y_size * 3 / 2, (jbyte *) i420_scaled_frame.data);

    env->ReleaseByteArrayElements(frame, nv21_frame, JNI_ABORT);
    return i420Frame;
}

// For COLOR_FormatYUV420SemiPlanar
static jbyteArray libenc_NV21ToNV12(JNIEnv* env, jobject thiz, jbyteArray frame, jint src_width,
                                    jint src_height, jboolean need_flip, jint rotate_degree) {
    jbyte* nv21_frame = env->GetByteArrayElements(frame, NULL);

    if (!nv21_to_i420(nv21_frame, src_width, src_height, need_flip, rotate_degree)) {
        return NULL;
    }

    int ret = ConvertFromI420(i420_scaled_frame.y, i420_scaled_frame.width,
                              i420_scaled_frame.u, i420_scaled_frame.width / 2,
                              i420_scaled_frame.v, i420_scaled_frame.width / 2,
                              nv12_frame.data, nv12_frame.width,
                              nv12_frame.width, nv12_frame.height,
                              FOURCC_NV12);
    if (ret < 0) {
        LIBENC_LOGE("ConvertFromI420 failure");
        return NULL;
    }

    int y_size = nv12_frame.width * nv12_frame.height;
    jbyteArray nv12Frame = env->NewByteArray(y_size * 3 / 2);
    env->SetByteArrayRegion(nv12Frame, 0, y_size * 3 / 2, (jbyte *) nv12_frame.data);

    env->ReleaseByteArrayElements(frame, nv21_frame, JNI_ABORT);
    return nv12Frame;
}

static int encode_nals(const x264_nal_t *nals, int nnal) {
    int i;
    uint8_t *p = h264_es;

    /* Write the SEI as part of the first frame. */
    if (x264_ctx.sei_size > 0 && nnal > 0) {
        memcpy(p, x264_ctx.sei, x264_ctx.sei_size);
        p += x264_ctx.sei_size;
        x264_ctx.sei_size = 0;
    }

    for (i = 0; i < nnal; i++) {
        if (nals[i].i_type != NAL_SEI) {
            memcpy(p, nals[i].p_payload, nals[i].i_payload);
            p += nals[i].i_payload;
        }
    }

    return p - h264_es;
}

static int x264_encode(struct YuvFrame *i420_frame, long pts) {
    int out_len, nnal;
    x264_nal_t *nal;
    x264_picture_t pic_out;
    int y_size = i420_frame->width * i420_frame->height;

    x264_ctx.picture.img.i_csp = X264_CSP_I420;
    x264_ctx.picture.img.i_plane = 3;
    x264_ctx.picture.img.plane[0] = i420_frame->y;
    x264_ctx.picture.img.i_stride[0] = i420_frame->width;
    x264_ctx.picture.img.plane[1] = i420_frame->u;
    x264_ctx.picture.img.i_stride[1] = i420_frame->width / 2;
    x264_ctx.picture.img.plane[2] = i420_frame->v;
    x264_ctx.picture.img.i_stride[2] = i420_frame->width / 2;
    x264_ctx.picture.i_pts = pts;
    x264_ctx.picture.i_type = X264_TYPE_AUTO;

    if (x264_encoder_encode(x264_ctx.encoder, &nal, &nnal, &x264_ctx.picture, &pic_out) < 0) {
        LIBENC_LOGE("Fail to encode in x264");
        return -1;
    }

    x264_ctx.pts = pic_out.i_pts;
    x264_ctx.dts = pic_out.i_dts;
    x264_ctx.is_key_frame = pic_out.i_type == X264_TYPE_IDR;

    return encode_nals(nal, nnal);
}

static jint libenc_NV21SoftEncode(JNIEnv* env, jobject thiz, jbyteArray frame, jint src_width,
                                  jint src_height, jboolean need_flip, jint rotate_degree, jlong pts) {
    jbyte* nv21_frame = env->GetByteArrayElements(frame, NULL);

    if (!nv21_to_i420(nv21_frame, src_width, src_height, need_flip, rotate_degree)) {
        return JNI_ERR;
    }

    int es_len = x264_encode(&i420_scaled_frame, pts);
    if (es_len <= 0) {
        LIBENC_LOGE("Fail to encode nalu");
        return JNI_ERR;
    }

    jbyteArray outputFrame = env->NewByteArray(es_len);
    env->SetByteArrayRegion(outputFrame, 0, es_len, (jbyte *) h264_es);

    jclass clz = env->GetObjectClass(thiz);
    jmethodID mid = env->GetMethodID(clz, "onSoftEncodedData", "([BJZ)V");
    env->CallVoidMethod(thiz, mid, outputFrame, x264_ctx.pts, x264_ctx.is_key_frame);

    env->ReleaseByteArrayElements(frame, nv21_frame, JNI_ABORT);
    return JNI_OK;
}

static void libenc_closeSoftEncoder(JNIEnv* env, jobject thiz) {
    int nnal;
    x264_nal_t *nal;
    x264_picture_t pic_out;

    if (x264_ctx.encoder != NULL) {
        while(x264_encoder_delayed_frames(x264_ctx.encoder)) {
            x264_encoder_encode(x264_ctx.encoder, &nal, &nnal, NULL, &pic_out);
        }
        x264_encoder_close(x264_ctx.encoder);
        x264_ctx.encoder = NULL;
    }
}

static jboolean libenc_openSoftEncoder(JNIEnv* env, jobject thiz) {
    // Presetting
    x264_param_default_preset(&x264_ctx.params, "veryfast", "zerolatency");

    // Resolution
    x264_ctx.params.i_width = x264_ctx.width;
    x264_ctx.params.i_height = x264_ctx.height;

    // Default setting i_rc_method as X264_RC_CRF which is better than X264_RC_ABR
    //x264_ctx.params.rc.i_bitrate = x264_ctx.bitrate;  // kbps
    //x264_ctx.params.rc.i_rc_method = X264_RC_ABR;

    // fps
    x264_ctx.params.i_fps_num = x264_ctx.fps;
    x264_ctx.params.i_fps_den = 1;

    // gop
    x264_ctx.params.i_keyint_max = x264_ctx.gop;

    if (x264_param_apply_profile(&x264_ctx.params, "baseline" ) < 0) {
        LIBENC_LOGE("Fail to apply profile");
        return JNI_FALSE;
    }

    x264_ctx.encoder = x264_encoder_open(&x264_ctx.params);
    if (x264_ctx.encoder == NULL) {
        LIBENC_LOGE("Fail to open x264 encoder!");
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static JNINativeMethod libenc_methods[] = {
    { "setOutputResolution", "(II)V", (void *)libenc_setOutputResolution },
    { "setOutputFps", "(I)V", (void *)libenc_setOutputFps },
    { "setOutputGop", "(I)V", (void *)libenc_setOutputGop },
    { "setOutputBitrate", "(I)V", (void *)libenc_setOutputBitrate },
    { "NV21ToI420", "([BIIZI)[B", (void *)libenc_NV21ToI420 },
    { "NV21ToNV12", "([BIIZI)[B", (void *)libenc_NV21ToNV12 },
    { "openSoftEncoder", "()Z", (void *)libenc_openSoftEncoder },
    { "closeSoftEncoder", "()V", (void *)libenc_closeSoftEncoder },
    { "NV21SoftEncode", "([BIIZIJ)I", (void *)libenc_NV21SoftEncode },
};

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    jvm = vm;

    if (jvm->GetEnv((void **) &jenv, JNI_VERSION_1_6) != JNI_OK) {
        LIBENC_LOGE("Env not got");
    	return JNI_ERR;
    }

    jclass clz = jenv->FindClass("net/ossrs/yasea/SrsEncoder");
    if (clz == NULL) {
        LIBENC_LOGE("Class \"net/ossrs/yasea/SrsEncoder\" not found");
        return JNI_ERR;
    }

    if (jenv->RegisterNatives(clz, libenc_methods, LIBENC_ARRAY_ELEMS(libenc_methods))) {
        LIBENC_LOGE("methods not registered");
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
