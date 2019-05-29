package net.ossrs.yasea;

import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.util.Log;

import com.github.faucamp.simplertmp.RtmpHandler;

import java.io.File;
import java.util.Map;

/**
 * Created by Leo Ma on 2016/7/25.
 * Modify by yicm on 2019/5/28.
 * Note: 1. 双摄像头状态管理
 *       2. 双摄像头数据回调
 *       3. 推流切换
 *       4. 录制切换
 */
public class SrsMediaControler {
    private final static String TAG = "SrsMediaControler";
    private static AudioRecord mic;
    private static AcousticEchoCanceler aec;
    private static AutomaticGainControl agc;
    private byte[] mPcmBuffer = new byte[4096];
    private Thread aworker;

    private SrsCameraView mCameraView;
    private Map<String, SrsCameraView> mCameraViewList;

    private boolean sendVideoOnly = false;
    private boolean sendAudioOnly = false;
    private boolean mIsStartEncode = false;
    private int videoFrameCount;
    private long lastTimeMillis;
    private double mSamplingFps;

    private SrsFlvMuxer mFlvMuxer;
    private SrsMp4Muxer mMp4Muxer;
    private SrsEncoder mEncoder;
    private static int count = 0;

    public SrsMediaControler(Map<String, SrsCameraView> views) {
        mCameraViewList = views;
    }

    public void setPreviewCallback(String viewKey, SrsCameraView.PreviewCallback callback) {
        if (!mCameraViewList.isEmpty()) {
            SrsCameraView view = mCameraViewList.get(viewKey);
            if (view != null) {
                Log.d(TAG, "set preview callback success");
                view.setPreviewCallback(callback);
            } else {
                Log.e(TAG, "not exist the key " + viewKey);
            }
        }
        /*
        mCameraView.setPreviewCallback(new SrsCameraView.PreviewCallback() {
            @Override
            public void onGetYuvFrame(byte[] data) {
                calcSamplingFps();
                Log.d("Test", "count: " + count++);
                if (!sendAudioOnly && mIsStartEncode) {
                    mEncoder.onGetYuvFrame(data);
                }
            }
        });
        */
    }

    public boolean isSendAudioOnly() {
        return sendAudioOnly;
    }

    public boolean isStartEncode() {
        return mIsStartEncode;
    }

    public SrsEncoder getEncoder() {
        return mEncoder;
    }

    public void calcSamplingFps() {
        // Calculate sampling FPS
        if (videoFrameCount == 0) {
            lastTimeMillis = System.nanoTime() / 1000000;
            videoFrameCount++;
        } else {
            if (++videoFrameCount >= SrsEncoder.VGOP) {
                long diffTimeMillis = System.nanoTime() / 1000000 - lastTimeMillis;
                mSamplingFps = (double) videoFrameCount * 1000 / diffTimeMillis;
                videoFrameCount = 0;
            }
        }
    }

    public void startCamera() {
        if (mCameraViewList != null) {
            for (SrsCameraView view : mCameraViewList.values()) {
                view.startCamera();
            }
        }
    }

    public void stopCamera() {
        if (mCameraViewList != null) {
            for (SrsCameraView view : mCameraViewList.values()) {
                view.stopCamera();
            }
        }
    }

    public void startAudio() {
        mic = mEncoder.chooseAudioRecord();
        if (mic == null) {
            return;
        }

        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(mic.getAudioSessionId());
            if (aec != null) {
                aec.setEnabled(true);
            }
        }

        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(mic.getAudioSessionId());
            if (agc != null) {
                agc.setEnabled(true);
            }
        }

        aworker = new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                mic.startRecording();
                while (!Thread.interrupted()) {
                    if (sendVideoOnly) {
                        mEncoder.onGetPcmFrame(mPcmBuffer, mPcmBuffer.length);
                        try {
                            // This is trivial...
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            break;
                        }
                    } else {
                        int size = mic.read(mPcmBuffer, 0, mPcmBuffer.length);
                        if (size > 0) {
                            mEncoder.onGetPcmFrame(mPcmBuffer, size);
                        }
                    }
                }
            }
        });
        aworker.start();
    }

    public void stopAudio() {
        if (aworker != null) {
            aworker.interrupt();
            try {
                aworker.join();
            } catch (InterruptedException e) {
                aworker.interrupt();
            }
            aworker = null;
        }

        if (mic != null) {
            mic.setRecordPositionUpdateListener(null);
            mic.stop();
            mic.release();
            mic = null;
        }

        if (aec != null) {
            aec.setEnabled(false);
            aec.release();
            aec = null;
        }

        if (agc != null) {
            agc.setEnabled(false);
            agc.release();
            agc = null;
        }
    }

    public void startDataCallback() {
        for (SrsCameraView view : mCameraViewList.values()) {
            view.enableDataCallback();
        }
    }

    public void stopDataCallback() {
        for (SrsCameraView view : mCameraViewList.values()) {
            view.disableDataCallback();
        }
    }

    public void startEncode() {
        if (!mEncoder.start()) {
            return;
        }

        mIsStartEncode = true;
        startAudio();
    }

    public void stopEncode() {
        stopAudio();
        mIsStartEncode = false;
        mEncoder.stop();
    }

    public void startPublish(String rtmpUrl) {
        if (mFlvMuxer != null) {
            mFlvMuxer.start(rtmpUrl);
            Log.d(TAG, "encoder w = " + mEncoder.getOutputWidth() + " , h = " + mEncoder.getOutputHeight());
            mFlvMuxer.setVideoResolution(mEncoder.getOutputWidth(), mEncoder.getOutputHeight());
            if (!mIsStartEncode) {
                startEncode();
            }
            mEncoder.enableFlvMuxer();
        }
    }

    public void stopPublish() {
        if (mFlvMuxer != null) {
            mEncoder.disableFlvMuxer();
            mFlvMuxer.stop();
        }
    }

    public boolean startRecord(String recPath) {
        return mMp4Muxer != null && mMp4Muxer.record(new File(recPath));
    }

    public void stopRecord() {
        if (mMp4Muxer != null) {
            mMp4Muxer.stop();
        }
    }

    public void pauseRecord() {
        if (mMp4Muxer != null) {
            mMp4Muxer.pause();
        }
    }

    public void resumeRecord() {
        if (mMp4Muxer != null) {
            mMp4Muxer.resume();
        }
    }

    public void switchToSoftEncoder() {
        mEncoder.switchToSoftEncoder();
    }

    public void switchToHardEncoder() {
        mEncoder.switchToHardEncoder();
    }

    public boolean isSoftEncoder() {
        return mEncoder.isSoftEncoder();
    }

    public int getPreviewWidth() {
        return mEncoder.getPreviewWidth();
    }

    public int getPreviewHeight() {
        return mEncoder.getPreviewHeight();
    }

    public double getmSamplingFps() {
        return mSamplingFps;
    }

    public void setViewPreviewResolution(String key, int width, int height) {
        if (!mCameraViewList.isEmpty()) {
            SrsCameraView view = mCameraViewList.get(key);
            if (view != null) {
                int resolution[] = view.setPreviewResolution(width, height);
            } else {
                Log.e(TAG, "not exist the key " + key);
            }
        }
    }

    public void setEncoderPreviewResolution(int width, int height) {
        if (mEncoder != null) {
            mEncoder.setPreviewResolution(width, height);
        }
    }

    public void setOutputResolution(int width, int height) {
        if (width <= height) {
            mEncoder.setPortraitResolution(width, height);
        } else {
            mEncoder.setLandscapeResolution(width, height);
        }
    }

    public void setScreenOrientation(String key, int orientation) {
        if (!mCameraViewList.isEmpty()) {
            SrsCameraView view = mCameraViewList.get(key);
            if (view != null) {
                view.setPreviewOrientation(orientation);
            } else {
                Log.e(TAG, "not exist the key " + key);
            }
        }
    }

    public void setEncoderFrameOrientation(int orientation) {
        if (mEncoder != null) {
            mEncoder.setScreenOrientation(orientation);
        }
    }

    public void setVideoHDMode() {
        mEncoder.setVideoHDMode();
    }

    public void setVideoSmoothMode() {
        mEncoder.setVideoSmoothMode();
    }

    public void setSendVideoOnly(boolean flag) {
        if (mic != null) {
            if (flag) {
                mic.stop();
                mPcmBuffer = new byte[4096];
            } else {
                mic.startRecording();
            }
        }
        sendVideoOnly = flag;
    }

    public void setSendAudioOnly(boolean flag) {
        sendAudioOnly = flag;
    }

//    public void switchCameraFace(int id) {
//        mCameraView.stopCamera();
//        mCameraView.setCameraId(id);
//        if (id == 0) {
//            mEncoder.setCameraBackFace();
//        } else {
//            mEncoder.setCameraFrontFace();
//        }
//        if (mEncoder != null && mEncoder.isEnabled()) {
//            mCameraView.enableDataCallback();
//        }
//        mCameraView.startCamera();
//    }

    public void setRtmpHandler(RtmpHandler handler) {
        mFlvMuxer = new SrsFlvMuxer(handler);
        if (mEncoder != null) {
            mEncoder.setFlvMuxer(mFlvMuxer);
        }
    }

    public void setRecordHandler(SrsRecordHandler handler) {
        mMp4Muxer = new SrsMp4Muxer(handler);
        if (mEncoder != null) {
            mEncoder.setMp4Muxer(mMp4Muxer);
        }
    }

    public void setEncodeHandler(SrsEncodeHandler handler) {
        mEncoder = new SrsEncoder(handler);
        if (mFlvMuxer != null) {
            mEncoder.setFlvMuxer(mFlvMuxer);
        }
        if (mMp4Muxer != null) {
            mEncoder.setMp4Muxer(mMp4Muxer);
        }
    }
}
