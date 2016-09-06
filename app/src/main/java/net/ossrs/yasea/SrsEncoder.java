package net.ossrs.yasea;

import android.content.res.Configuration;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Leo Ma on 4/1/2016.
 */
public class SrsEncoder {
    private static final String TAG = "SrsEncoder";

    public static final String VCODEC = "video/avc";
    public static final String ACODEC = "audio/mp4a-latm";
    public static String x264Preset = "veryfast";
    public static int vPrevWidth = 1280;
    public static int vPrevHeight = 720;
    public static int vPortraitWidth = 384;
    public static int vPortraitHeight = 640;
    public static int vOutWidth = 384;   // Note: the stride of resolution must be set as 16x for hard encoding with some chip like MTK
    public static int vOutHeight = 640;  // Since Y component is quadruple size as U and V component, the stride must be set as 32x
    public static int vBitrate = 500 * 1000;  // 500kbps
    public static final int VFPS = 24;
    public static final int VGOP = 48;
    public static final int ASAMPLERATE = 44100;
    public static int aChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
    public static final int ABITRATE = 32 * 1000;  // 32kbps

    private int mOrientation = Configuration.ORIENTATION_PORTRAIT;

    private SrsFlvMuxer flvMuxer;
    private SrsMp4Muxer mp4Muxer;

    private MediaCodecInfo vmci;
    private MediaCodec vencoder;
    private MediaCodec aencoder;
    private MediaCodec.BufferInfo vebi = new MediaCodec.BufferInfo();
    private MediaCodec.BufferInfo aebi = new MediaCodec.BufferInfo();

    private EventHandler mHandler;
    private boolean networkWeakTriggered = false;
    private boolean mCameraFaceFront = true;
    private boolean useSoftEncoder = false;

    private long mPresentTimeUs;

    private int mVideoColorFormat;

    private int videoFlvTrack;
    private int videoMp4Track;
    private int audioFlvTrack;
    private int audioMp4Track;

    public interface EventHandler {

        void onNetworkResume(String msg);

        void onNetworkWeak(String msg);
    }

    // Y, U (Cb) and V (Cr)
    // yuv420                     yuv yuv yuv yuv
    // yuv420p (planar)   yyyy*2 uu vv
    // yuv420sp(semi-planner)   yyyy*2 uv uv
    // I420 -> YUV420P   yyyy*2 uu vv
    // YV12 -> YUV420P   yyyy*2 vv uu
    // NV12 -> YUV420SP  yyyy*2 uv uv
    // NV21 -> YUV420SP  yyyy*2 vu vu
    // NV16 -> YUV422SP  yyyy uv uv
    // YUY2 -> YUV422SP  yuyv yuyv

    public SrsEncoder() {
        mVideoColorFormat = chooseVideoEncoder();
    }

    public void setFlvMuxer(SrsFlvMuxer flvMuxer) {
        this.flvMuxer = flvMuxer;
    }

    public void setMp4Muxer(SrsMp4Muxer mp4Muxer) {
        this.mp4Muxer = mp4Muxer;
    }

    public void setNetworkEventHandler(EventHandler handler) {
        mHandler = handler;
    }

    public boolean start() {
        if (flvMuxer == null || mp4Muxer == null) {
            return false;
        }

        // the referent PTS for video and audio encoder.
        mPresentTimeUs = System.nanoTime() / 1000;

        // Note: the stride of resolution must be set as 16x for hard encoding with some chip like MTK
        // Since Y component is quadruple size as U and V component, the stride must be set as 32x
        if (!useSoftEncoder && vOutWidth % 32 != 0 || vOutHeight % 32 != 0) {
            if (vmci.getName().contains("MTK")) {
                throw new AssertionError("MTK encoding revolution stride must be 32x");
            }
        }

        setEncoderResolution(vOutWidth, vOutHeight);
        setEncoderFps(VFPS);
        setEncoderGop(VGOP);
        // Unfortunately for some android phone, the output fps is less than 10 lSrsted by the
        // capacity of poor cheap chips even with x264. So for the sake of quick appearance of
        // the first picture on the player, a spare lower GOP value is suggested. But note that
        // lower GOP will produce more I frames and therefore more streaming data flow.
        // setEncoderGop(15);
        setEncoderBitrate(vBitrate);
        setEncoderPreset(x264Preset);

        if (useSoftEncoder && !openSoftEncoder()) {
            return false;
        }

        // aencoder pcm to aac raw stream.
        // requires sdk level 16+, Android 4.1, 4.1.1, the JELLY_BEAN
        try {
            aencoder = MediaCodec.createEncoderByType(ACODEC);
        } catch (IOException e) {
            Log.e(TAG, "create aencoder failed.");
            e.printStackTrace();
            return false;
        }

        // setup the aencoder.
        // @see https://developer.android.com/reference/android/media/MediaCodec.html
        int ach = aChannelConfig == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1;
        MediaFormat audioFormat = MediaFormat.createAudioFormat(ACODEC, ASAMPLERATE, ach);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, ABITRATE);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        aencoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // add the audio tracker to muxer.
        audioFlvTrack = flvMuxer.addTrack(audioFormat);
        audioMp4Track = mp4Muxer.addTrack(audioFormat);

        // vencoder yuv to 264 es stream.
        // requires sdk level 16+, Android 4.1, 4.1.1, the JELLY_BEAN
        try {
            vencoder = MediaCodec.createByCodecName(vmci.getName());
        } catch (IOException e) {
            Log.e(TAG, "create vencoder failed.");
            e.printStackTrace();
            return false;
        }

        // setup the vencoder.
        // Note: landscape to portrait, 90 degree rotation, so we need to switch width and height in configuration
        MediaFormat videoFormat = MediaFormat.createVideoFormat(VCODEC, vOutWidth, vOutHeight);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, mVideoColorFormat);
        videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, vBitrate);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VFPS);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VGOP / VFPS);
        vencoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // add the video tracker to muxer.
        videoFlvTrack = flvMuxer.addTrack(videoFormat);
        videoMp4Track = mp4Muxer.addTrack(videoFormat);

        // start device and encoder.
        vencoder.start();
        aencoder.start();
        return true;
    }

    public void stop() {
        if (useSoftEncoder) {
            closeSoftEncoder();
        }

        if (aencoder != null) {
            Log.i(TAG, "stop aencoder");
            aencoder.stop();
            aencoder.release();
            aencoder = null;
        }

        if (vencoder != null) {
            Log.i(TAG, "stop vencoder");
            vencoder.stop();
            vencoder.release();
            vencoder = null;
        }
    }

    public void swithCameraFace() {
        if (mCameraFaceFront) {
            mCameraFaceFront = false;
        } else {
            mCameraFaceFront = true;
        }
    }
    
    public void setCameraFront() {
        mCameraFaceFront = true;
    }

    public void setCameraBack() {
        mCameraFaceFront = false;
    }    

    public void swithToSoftEncoder() {
        useSoftEncoder = true;
    }

    public void swithToHardEncoder() {
        useSoftEncoder = false;
    }

    public boolean isSoftEncoder() {
        return useSoftEncoder;
    }

    public void setPreviewResolution(int width, int height) {
        vPrevWidth = width;
        vPrevHeight = height;
    }

    public void setPortraitResolution(int width, int height) {
        vOutWidth = width;
        vOutHeight = height;
        vPortraitWidth = width;
        vPortraitHeight = height;
    }

    public void setLandscapeResolution(int width, int height) {
        vOutWidth = width;
        vOutHeight = height;
        vPortraitWidth = height;
        vPortraitHeight = width;
    }

    public void setVideoHDMode() {
        vBitrate = 1200 * 1000;  // 1200 kbps
        x264Preset = "veryfast";
    }

    public void setVideoSmoothMode() {
        vBitrate = 500 * 1000;  // 500 kbps
        x264Preset = "superfast";
    }

    public int getPreviewWidth() {
        return vPrevWidth;
    }

    public int getPreviewHeight() {
        return vPrevHeight;
    }

    public int getOutputWidth() {
        return vOutWidth;
    }

    public int getOutputHeight() {
        return vOutHeight;
    }

    public void setScreenOrientation(int orientation) {
        mOrientation = orientation;
        if (mOrientation == Configuration.ORIENTATION_PORTRAIT) {
            vOutWidth = vPortraitWidth;
            vOutHeight = vPortraitHeight;
        } else if (mOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            vOutWidth = vPortraitHeight;
            vOutHeight = vPortraitWidth;
        }
        
        // Note: the stride of resolution must be set as 16x for hard encoding with some chip like MTK
        // Since Y component is quadruple size as U and V component, the stride must be set as 32x
        if (!useSoftEncoder && vOutWidth % 32 != 0 || vOutHeight % 32 != 0) {
            if (vmci.getName().contains("MTK")) {
                throw new AssertionError("MTK encoding revolution stride must be 32x");
            }
        }

        setEncoderResolution(vOutWidth, vOutHeight);
    }

    private void onProcessedYuvFrame(byte[] yuvFrame, long pts) {
        ByteBuffer[] inBuffers = vencoder.getInputBuffers();
        ByteBuffer[] outBuffers = vencoder.getOutputBuffers();

        int inBufferIndex = vencoder.dequeueInputBuffer(-1);
        if (inBufferIndex >= 0) {
            ByteBuffer bb = inBuffers[inBufferIndex];
            bb.clear();
            bb.put(yuvFrame, 0, yuvFrame.length);
            vencoder.queueInputBuffer(inBufferIndex, 0, yuvFrame.length, pts, 0);
        }

        for (; ; ) {
            int outBufferIndex = vencoder.dequeueOutputBuffer(vebi, 0);
            if (outBufferIndex >= 0) {
                ByteBuffer bb = outBuffers[outBufferIndex];
                onEncodedAnnexbFrame(bb, vebi);
                vencoder.releaseOutputBuffer(outBufferIndex, false);
            } else {
                break;
            }
        }
    }

    private void onSoftEncodedData(byte[] es, long pts, boolean isKeyFrame) {
        ByteBuffer bb = ByteBuffer.wrap(es);
        vebi.offset = 0;
        vebi.size = es.length;
        vebi.presentationTimeUs = pts;
        vebi.flags = isKeyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
        onEncodedAnnexbFrame(bb, vebi);
    }

    // when got encoded h264 es stream.
    private void onEncodedAnnexbFrame(ByteBuffer es, MediaCodec.BufferInfo bi) {
        try {
            ByteBuffer record = es.duplicate();
            mp4Muxer.writeSampleData(videoMp4Track, record, bi);
            flvMuxer.writeSampleData(videoFlvTrack, es, bi);
        } catch (Exception e) {
            Log.e(TAG, "muxer write video sample failed.");
            e.printStackTrace();
        }
    }

    public void onGetYuvFrame(byte[] data) {
        // Check video frame cache number to judge the networking situation.
        // Just cache GOP / FPS seconds data according to latency.
        AtomicInteger videoFrameCacheNumber = flvMuxer.getVideoFrameCacheNumber();
        if (videoFrameCacheNumber != null && videoFrameCacheNumber.get() < VGOP) {
            long pts = System.nanoTime() / 1000 - mPresentTimeUs;
            if (useSoftEncoder) {
                if (mOrientation == Configuration.ORIENTATION_PORTRAIT) {
                    swPortraitYuvFrame(data, pts);
                } else {
                    swLandscapeYuvFrame(data, pts);
                }
            } else {
                byte[] processedData = mOrientation == Configuration.ORIENTATION_PORTRAIT ?
                        hwPortraitYuvFrame(data) : hwLandscapeYuvFrame(data);
                if (processedData != null) {
                    onProcessedYuvFrame(processedData, pts);
                } else {
                    Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(),
                            new IllegalArgumentException("libyuv failure"));
                }
            }

            if (networkWeakTriggered) {
                networkWeakTriggered = false;
                mHandler.onNetworkResume("Network resume");
            }
        } else {
            mHandler.onNetworkWeak("Network weak");
            networkWeakTriggered = true;
        }
    }

    // when got encoded aac raw stream.
    private void onEncodedAacFrame(ByteBuffer es, MediaCodec.BufferInfo bi) {
        try {
            ByteBuffer record = es.duplicate();
            mp4Muxer.writeSampleData(audioMp4Track, record, bi);
            flvMuxer.writeSampleData(audioFlvTrack, es, bi);
        } catch (Exception e) {
            Log.e(TAG, "muxer write audio sample failed.");
            e.printStackTrace();
        }
    }

    public void onGetPcmFrame(byte[] data, int size) {
        ByteBuffer[] inBuffers = aencoder.getInputBuffers();
        ByteBuffer[] outBuffers = aencoder.getOutputBuffers();

        int inBufferIndex = aencoder.dequeueInputBuffer(-1);
        if (inBufferIndex >= 0) {
            ByteBuffer bb = inBuffers[inBufferIndex];
            bb.clear();
            bb.put(data, 0, size);
            long pts = System.nanoTime() / 1000 - mPresentTimeUs;
            aencoder.queueInputBuffer(inBufferIndex, 0, size, pts, 0);
        }

        for (; ; ) {
            int outBufferIndex = aencoder.dequeueOutputBuffer(aebi, 0);
            if (outBufferIndex >= 0) {
                ByteBuffer bb = outBuffers[outBufferIndex];
                onEncodedAacFrame(bb, aebi);
                aencoder.releaseOutputBuffer(outBufferIndex, false);
            } else {
                break;
            }
        }
    }

    private byte[] hwPortraitYuvFrame(byte[] data) {
        if (mCameraFaceFront) {
            switch (mVideoColorFormat) {
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                    return NV21ToI420(data, vPrevWidth, vPrevHeight, true, 270);
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                    return NV21ToNV12(data, vPrevWidth, vPrevHeight, true, 270);
                default:
                    throw new IllegalStateException("Unsupported color format!");
            }
        } else {
            switch (mVideoColorFormat) {
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                    return NV21ToI420(data, vPrevWidth, vPrevHeight, false, 90);
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                    return NV21ToNV12(data, vPrevWidth, vPrevHeight, false, 90);
                default:
                    throw new IllegalStateException("Unsupported color format!");
            }
        }
    }

    private byte[] hwLandscapeYuvFrame(byte[] data) {
        if (mCameraFaceFront) {
            switch (mVideoColorFormat) {
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                    return NV21ToI420(data, vPrevWidth, vPrevHeight, true, 0);
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                    return NV21ToNV12(data, vPrevWidth, vPrevHeight, true, 0);
                default:
                    throw new IllegalStateException("Unsupported color format!");
            }
        } else {
            switch (mVideoColorFormat) {
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                    return NV21ToI420(data, vPrevWidth, vPrevHeight, false, 0);
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                    return NV21ToNV12(data, vPrevWidth, vPrevHeight, false, 0);
                default:
                    throw new IllegalStateException("Unsupported color format!");
            }
        }
    }

    private void swPortraitYuvFrame(byte[] data, long pts) {
        if (mCameraFaceFront) {
            NV21SoftEncode(data, vPrevWidth, vPrevHeight, true, 270, pts);
        } else {
            NV21SoftEncode(data, vPrevWidth, vPrevHeight, false, 90, pts);
        }
    }

    private void swLandscapeYuvFrame(byte[] data, long pts) {
        if (mCameraFaceFront) {
            NV21SoftEncode(data, vPrevWidth, vPrevHeight, true, 0, pts);
        } else {
            NV21SoftEncode(data, vPrevWidth, vPrevHeight, false, 0, pts);
        }
    }

    public AudioRecord chooseAudioRecord() {
        int minBufferSize = AudioRecord.getMinBufferSize(SrsEncoder.ASAMPLERATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord mic = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SrsEncoder.ASAMPLERATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize);
        if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
            mic = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SrsEncoder.ASAMPLERATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize);
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                mic = null;
            } else {
                SrsEncoder.aChannelConfig = AudioFormat.CHANNEL_IN_MONO;
            }
        } else {
            SrsEncoder.aChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
        }

        return mic;
    }

    // choose the video encoder by name.
    private MediaCodecInfo chooseVideoEncoder(String name) {
        int nbCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < nbCodecs; i++) {
            MediaCodecInfo mci = MediaCodecList.getCodecInfoAt(i);
            if (!mci.isEncoder()) {
                continue;
            }

            String[] types = mci.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(VCODEC)) {
                    Log.i(TAG, String.format("vencoder %s types: %s", mci.getName(), types[j]));
                    if (name == null) {
                        return mci;
                    }

                    if (mci.getName().contains(name)) {
                        return mci;
                    }
                }
            }
        }

        return null;
    }

    // choose the right supported color format. @see below:
    private int chooseVideoEncoder() {
        // choose the encoder "video/avc":
        //      1. select default one when type matched.
        //      2. google avc is unusable.
        //      3. choose qcom avc.
        vmci = chooseVideoEncoder(null);
        //vmci = chooseVideoEncoder("google");
        //vmci = chooseVideoEncoder("qcom");

        int matchedColorFormat = 0;
        MediaCodecInfo.CodecCapabilities cc = vmci.getCapabilitiesForType(VCODEC);
        for (int i = 0; i < cc.colorFormats.length; i++) {
            int cf = cc.colorFormats[i];
            Log.i(TAG, String.format("vencoder %s supports color fomart 0x%x(%d)", vmci.getName(), cf, cf));

            // choose YUV for h.264, prefer the bigger one.
            // corresponding to the color space transform in onPreviewFrame
            if (cf >= cc.COLOR_FormatYUV420Planar && cf <= cc.COLOR_FormatYUV420SemiPlanar) {
                if (cf > matchedColorFormat) {
                    matchedColorFormat = cf;
                }
            }
        }

        for (int i = 0; i < cc.profileLevels.length; i++) {
            MediaCodecInfo.CodecProfileLevel pl = cc.profileLevels[i];
            Log.i(TAG, String.format("vencoder %s support profile %d, level %d", vmci.getName(), pl.profile, pl.level));
        }

        Log.i(TAG, String.format("vencoder %s choose color format 0x%x(%d)", vmci.getName(), matchedColorFormat, matchedColorFormat));
        return matchedColorFormat;
    }

    private native void setEncoderResolution(int outWidth, int outHeight);
    private native void setEncoderFps(int fps);
    private native void setEncoderGop(int gop);
    private native void setEncoderBitrate(int bitrate);
    private native void setEncoderPreset(String preset);
    private native byte[] NV21ToI420(byte[] yuvFrame, int width, int height, boolean flip, int rotate);
    private native byte[] NV21ToNV12(byte[] yuvFrame, int width, int height, boolean flip, int rotate);
    private native int NV21SoftEncode(byte[] yuvFrame, int width, int height, boolean flip, int rotate, long pts);
    private native boolean openSoftEncoder();
    private native void closeSoftEncoder();

    static {
        System.loadLibrary("yuv");
        System.loadLibrary("enc");
    }
}
