package net.ossrs.sea;

import android.graphics.ImageFormat;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by Leo Ma on 4/1/2016.
 */
public class SrsEncoder {
    private static final String TAG = "SrsEncoder";

    public static final String VCODEC = "video/avc";
    public static final String ACODEC = "audio/mp4a-latm";
    public static String rtmpUrl = "rtmp://10.10.10.135/ivp/test";//"ossrs.net:1935/live/sea"

    public static final int VWIDTH = 640;
    public static final int VHEIGHT = 480;
    public static int vbitrate = 800 * 1000;  // 800kbps
    public static final int VENC_WIDTH = 368;
    public static final int VENC_HEIGHT = 640;
    public static final int VFPS = 24;
    public static final int VGOP = 60;
    public static final int VFORMAT = ImageFormat.NV21;
    public static final int ASAMPLERATE = 44100;
    public static final int ACHANNEL = AudioFormat.CHANNEL_IN_STEREO;
    public static final int AFORMAT = AudioFormat.ENCODING_PCM_16BIT;
    public static final int ABITRATE = 128 * 1000;  // 128kbps
    
    private SrsRtmp muxer;

    private MediaCodec vencoder;
    private MediaCodecInfo vmci;
    private MediaCodec.BufferInfo vebi;
    private MediaCodec aencoder;
    private MediaCodec.BufferInfo aebi;

    private byte[] mRotatedFrameBuffer;
    private byte[] mFlippedFrameBuffer;
    private byte[] mCroppedFrameBuffer;
    private boolean mCameraFaceFront = true;
    private long mPresentTimeMs;
    private int vtrack;
    private int vcolor;
    private int atrack;

    public SrsEncoder() {
        vcolor = chooseVideoEncoder();
        mRotatedFrameBuffer = new byte[VWIDTH * VHEIGHT * 3 / 2];
        mFlippedFrameBuffer = new byte[VWIDTH * VHEIGHT * 3 / 2];
        mCroppedFrameBuffer = new byte[VENC_WIDTH * VENC_HEIGHT * 3 / 2];
    }

    public int start() {
        muxer = new SrsRtmp(rtmpUrl);
        try {
            muxer.start();
        } catch (IOException e) {
            Log.e(TAG, "start muxer failed.");
            e.printStackTrace();
            return -1;
        }

        // the referent PTS for video and audio encoder.
        mPresentTimeMs = System.currentTimeMillis();

        // aencoder yuv to aac raw stream.
        // requires sdk level 16+, Android 4.1, 4.1.1, the JELLY_BEAN
        try {
            aencoder = MediaCodec.createEncoderByType(ACODEC);
        } catch (IOException e) {
            Log.e(TAG, "create aencoder failed.");
            e.printStackTrace();
            return -1;
        }
        aebi = new MediaCodec.BufferInfo();

        // setup the aencoder.
        // @see https://developer.android.com/reference/android/media/MediaCodec.html
        int ach = ACHANNEL == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1;
        MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, ASAMPLERATE, ach);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, ABITRATE);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        aencoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // add the audio tracker to muxer.
        atrack = muxer.addTrack(audioFormat);

        // vencoder yuv to 264 es stream.
        // requires sdk level 16+, Android 4.1, 4.1.1, the JELLY_BEAN
        try {
            vencoder = MediaCodec.createByCodecName(vmci.getName());
        } catch (IOException e) {
            Log.e(TAG, "create vencoder failed.");
            e.printStackTrace();
            return -1;
        }
        vebi = new MediaCodec.BufferInfo();

        // setup the vencoder.
        // Note: landscape to portrait, 90 degree rotation, so we need to switch VWIDTH and VHEIGHT in configuration
        MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VENC_WIDTH, VENC_HEIGHT);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, vcolor);
        videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 300000);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VFPS);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VGOP);
        vencoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // add the video tracker to muxer.
        vtrack = muxer.addTrack(videoFormat);

        // start device and encoder.
        try {
            Log.i(TAG, "start avc vencoder");
            vencoder.start();
            Log.i(TAG, "start aac aencoder");
            aencoder.start();
        } catch (Exception e) {
            Log.e(TAG, "Encoder start failed!");
        }
        return 0;
    }

    public void stop() {
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

        if (muxer != null) {
            Log.i(TAG, "stop muxer to SRS over RTMP");
            muxer.release();
            muxer = null;
        }
    }

    public void swithCameraFace() {
        if (mCameraFaceFront) {
            mCameraFaceFront = false;
        } else {
            mCameraFaceFront = true;
        }
    }

    // when got encoded h264 es stream.
    private void onEncodedAnnexbFrame(ByteBuffer es, MediaCodec.BufferInfo bi) {
        try {
            muxer.writeSampleData(vtrack, es, bi);
        } catch (Exception e) {
            Log.e(TAG, "muxer write video sample failed.");
            e.printStackTrace();
        }
    }

    private int preProcessYuvFrame(byte[] data) {
        if (mCameraFaceFront) {
            switch (vcolor) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                flipYUV420PlannerFrame(data, mFlippedFrameBuffer, VHEIGHT, VWIDTH);
                rotateYUV420PlannerFrame(mFlippedFrameBuffer, mRotatedFrameBuffer, VWIDTH, VHEIGHT);
                cropYUV420PlannerFrame(mRotatedFrameBuffer, VHEIGHT, VWIDTH,
                                            mCroppedFrameBuffer, VENC_WIDTH, VENC_HEIGHT);
                break;
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
                flipYUV420SemiPlannerFrame(data, mFlippedFrameBuffer, VWIDTH, VHEIGHT);
                rotateYUV420SemiPlannerFrame(mFlippedFrameBuffer, mRotatedFrameBuffer, VWIDTH, VHEIGHT);
                cropYUV420SemiPlannerFrame(mRotatedFrameBuffer, VHEIGHT, VWIDTH,
                                                mCroppedFrameBuffer, VENC_WIDTH, VENC_HEIGHT);
                break;
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                flipYUV420SemiPlannerFrame(data, mFlippedFrameBuffer, VWIDTH, VHEIGHT);
                rotateYUV420SemiPlannerFrame(mFlippedFrameBuffer, mRotatedFrameBuffer, VWIDTH, VHEIGHT);
                cropYUV420SemiPlannerFrame(mRotatedFrameBuffer, VHEIGHT, VWIDTH,
                                                mCroppedFrameBuffer, VENC_WIDTH, VENC_HEIGHT);
                break;
            default:
                return -1;
            }
        } else {
            switch (vcolor) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                rotateYUV420PlannerFrame(data, mRotatedFrameBuffer, VWIDTH, VHEIGHT);
                cropYUV420PlannerFrame(mRotatedFrameBuffer, VHEIGHT, VWIDTH,
                                            mCroppedFrameBuffer, VENC_WIDTH, VENC_HEIGHT);
                break;
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
                rotateYUV420SemiPlannerFrame(data, mRotatedFrameBuffer, VWIDTH, VHEIGHT);
                cropYUV420SemiPlannerFrame(mRotatedFrameBuffer, VHEIGHT, VWIDTH,
                                                mCroppedFrameBuffer, VENC_WIDTH, VENC_HEIGHT);
                break;
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                rotateYUV420SemiPlannerFrame(data, mRotatedFrameBuffer, VWIDTH, VHEIGHT);
                cropYUV420SemiPlannerFrame(mRotatedFrameBuffer, VHEIGHT, VWIDTH,
                                                mCroppedFrameBuffer, VENC_WIDTH, VENC_HEIGHT);
                break;
            default:
                return -1;
            }
        }

        return 0;
    }

    public void onGetYuvFrame(byte[] data) {
        if (preProcessYuvFrame(data) >= 0) {
            ByteBuffer[] inBuffers = vencoder.getInputBuffers();
            ByteBuffer[] outBuffers = vencoder.getOutputBuffers();

            int inBufferIndex = vencoder.dequeueInputBuffer(-1);
            if (inBufferIndex >= 0) {
                ByteBuffer bb = inBuffers[inBufferIndex];
                bb.clear();
                bb.put(mCroppedFrameBuffer, 0, mCroppedFrameBuffer.length);
                long pts = System.currentTimeMillis() - mPresentTimeMs;
                vencoder.queueInputBuffer(inBufferIndex, 0, mCroppedFrameBuffer.length, pts, 0);
            }

            for (; ;) {
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
    }

    // when got encoded aac raw stream.
    private void onEncodedAacFrame(ByteBuffer es, MediaCodec.BufferInfo bi) {
        try {
            muxer.writeSampleData(atrack, es, bi);
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
            long pts = System.currentTimeMillis() - mPresentTimeMs;
            aencoder.queueInputBuffer(inBufferIndex, 0, size, pts, 0);
        }

        for (; ;) {
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

    // Y, U (Cb) and V (Cr)
    // yuv420                     yuv yuv yuv yuv
    // yuv420p (平面模式 planar)   yyyy*2 uu vv
    // yuv420sp(打包模式 packed)   yyyy*2 uv uv   SP(Semi-Planar)指的是YUV不是分成3个平面而是分成2个平面。Y数据一个平面，UV数据合用一个平面，数据格式UVUVUV
    // I420 -> YUV420P   yyyy*2 uu vv
    // YV12 -> YUV420P   yyyy*2 vv uu
    // NV21 -> YUV420SP  yyyy*2 vu vu
    // NV12 -> YUV420SP  yyyy*2 uv uv
    // NV16 -> YUV422SP  yyyy uv uv
    // YUY2 -> YUV422SP  yuyv yuyv

    private byte[] cropYUV420SemiPlannerFrame(byte[] input, int iw, int ih, byte[] output, int ow, int oh) {
        assert(iw >= ow && ih >= oh);

        int i = 0;
        int iFrameSize = iw * ih;
        int oFrameSize = ow * oh;

        for (int row = (ih - oh) / 2; row < oh + (ih - oh) / 2; row++) {
            for (int col = (iw - ow) / 2; col < ow + (iw - ow) / 2; col++) {
                output[i++] = input[iw * row + col];  // Y
            }
        }

        i = 0;
        for (int row = (ih - oh) / 4; row < oh / 2 + (ih - oh) / 4; row++) {
            for (int col = (iw - ow) / 4; col < ow / 2 + (iw - ow) / 4; col++) {
                output[oFrameSize + 2 * i] = input[iFrameSize + iw * row + 2 * col];  // U
                output[oFrameSize + 2 * i + 1] = input[iFrameSize + iw * row + 2 * col + 1];  // V
                i++;
            }
        }

        return output;
    }

    private byte[] cropYUV420PlannerFrame(byte[] input, int iw, int ih, byte[] output, int ow, int oh) {
        assert(iw >= ow && ih >= oh);

        int i = 0;
        int iFrameSize = iw * ih;
        int iQFrameSize = iFrameSize / 4;
        int oFrameSize = ow * oh;
        int oQFrameSize = oFrameSize / 4;

        for (int row = (ih - oh) / 2; row < oh + (ih - oh) / 2; row++) {
            for (int col = (iw - ow) / 2; col < ow + (iw - ow) / 2; col++) {
                output[i++] = input[iw * row + col];  // Y
            }
        }

        i = 0;
        for (int row = (ih - oh) / 4; row < oh / 2 + (ih - oh) / 4; row++) {
            for (int col = (iw - ow) / 4; col < ow / 2 + (iw - ow) / 4; col++) {
                output[oFrameSize + i++] = input[iFrameSize + iw * row + col];  // U
            }
        }

        i = 0;
        for (int row = (ih - oh) / 4; row < oh / 2 + (ih - oh) / 4; row++) {
            for (int col = (iw - ow) / 4; col < ow / 2 + (iw - ow) / 4; col++) {
                output[oFrameSize + oQFrameSize + i++] = input[iFrameSize + iQFrameSize + iw * row + col];  // V
            }
        }

        return output;
    }

    // 1. rotate 90 degree clockwise
    // 2. convert NV21 to NV12
    private byte[] rotateYUV420SemiPlannerFrame(byte[] input, byte[] output, int width, int height) {
        int frameSize = width * height;

        int i = 0;
        for (int col = 0; col < width; col++) {
            for (int row = height - 1; row >= 0; row--) {
                output[i++] = input[width * row + col]; // Y
            }
        }

        i = 0;
        for (int col = 0; col < width / 2; col++) {
            for (int row = height / 2 - 1; row >= 0; row--) {
                output[frameSize + i * 2 + 1] = input[frameSize + width * row + col * 2]; // Cb (U)
                output[frameSize + i * 2] = input[frameSize + width * row + col * 2 + 1]; // Cr (V)
                i++;
            }
        }

        return output;
    }

    // 1. rotate 90 degree clockwise
    // 2. convert NV21 to I420
    private byte[] rotateYUV420PlannerFrame(byte[] input, byte[] output, int width, int height) {
        int frameSize = width * height;
        int qFrameSize = frameSize / 4;

        int i = 0;
        for (int col = width - 1; col >= 0; col--) {
            for (int row = 0; row < height; row++) {
                output[i++] = input[width * row + col];
            }
        }

        i = 0;
        for (int col = width / 2 - 1; col >= 0; col--) {
            for (int row = 0; row < height / 2; row++) {
                output[frameSize + i++] = input[frameSize + width * row + col];
            }
        }

        i = 0;
        for (int col = width / 2 - 1; col >= 0; col--) {
            for (int row = 0; row < height / 2; row++) {
                output[frameSize + qFrameSize + i++] = input[frameSize + qFrameSize + width * row + col];
            }
        }

        return output;
    }

    private byte[] flipYUV420SemiPlannerFrame(byte[] input, byte[] output, int width, int height) {
        int frameSize = width * height;

        int i = 0;
        for (int row = 0; row < height; row++) {
            for (int col = width - 1; col >= 0; col--) {
                output[i++] = input[width * row + col]; // Y
            }
        }

        i = 0;
        for (int row = 0; row < height / 2; row++) {
            for (int col = width / 2 - 1; col >= 0; col--) {
                output[frameSize + i * 2] = input[frameSize + width * row + col * 2]; // Cb (U)
                output[frameSize + i * 2 + 1] = input[frameSize + width * row + col * 2 + 1]; // Cr (V)
                i++;
            }
        }

        return output;
    }

    private byte[] flipYUV420PlannerFrame(byte[] input, byte[] output, int width, int height) {
        int frameSize = width * height;
        int qFrameSize = frameSize / 4;

        int i = 0;
        for (int row = 0; row < height; row++) {
            for (int col = width - 1; col >= 0; col--) {
                output[i++] = input[width * row + col]; // Y
            }
        }

        i = 0;
        for (int row = 0; row < height / 2; row++) {
            for (int col = width / 2 - 1; col >= 0; col--) {
                output[frameSize + i] = input[frameSize + width * row + col]; // Cb (U)
                i++;
            }
        }

        i = 0;
        for (int row = 0; row < height / 2; row++) {
            for (int col = width / 2 - 1; col >= 0; col--) {
                output[frameSize + qFrameSize + i] = input[frameSize + qFrameSize + width * row + col]; // Cr (V)
                i++;
            }
        }

        return output;
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
                    //Log.i(TAG, String.format("vencoder %s types: %s", mci.getName(), types[j]));
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
        //      1. select one when type matched.
        //      2. perfer google avc.
        //      3. perfer qcom avc.
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
            if ((cf >= cc.COLOR_FormatYUV420Planar && cf <= cc.COLOR_FormatYUV420SemiPlanar)) {
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
}