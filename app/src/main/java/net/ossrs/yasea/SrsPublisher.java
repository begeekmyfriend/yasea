package net.ossrs.yasea;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecord;

import net.ossrs.yasea.rtmp.RtmpPublisher;

import java.io.File;
import java.io.IOException;

/**
 * Created by Leo Ma on 2016/7/25.
 */
public class SrsPublisher {
    private static final String TAG = "SrsPublisher";

    private AudioRecord mic;
    private boolean aloop = false;
    private Thread aworker;

    private SrsCameraView mCameraView;

    private boolean sendAudioOnly = false;
    private int videoFrameCount;
    private long lastTimeMillis;
    private double mSamplingFps;

    private SrsFlvMuxer mFlvMuxer;
    private SrsMp4Muxer mMp4Muxer;
    private SrsEncoder mEncoder = new SrsEncoder();

    public SrsPublisher(SrsCameraView view) {
        mCameraView = view;
        mCameraView.setPreviewResolution(mEncoder.getPreviewWidth(), mEncoder.getPreviewHeight());
        mCameraView.setPreviewCallback(new SrsCameraView.PreviewCallback() {
            @Override
            public void onGetYuvFrame(byte[] data) {
                // Calculate YUV sampling FPS
                if (videoFrameCount == 0) {
                    lastTimeMillis = System.nanoTime() / 1000000;
                    videoFrameCount++;
                } else {
                    if (++videoFrameCount >= 48) {
                        long diffTimeMillis = System.nanoTime() / 1000000 - lastTimeMillis;
                        mSamplingFps = (double) videoFrameCount * 1000 / diffTimeMillis;
                        videoFrameCount = 0;
                    }
                }

                if (!sendAudioOnly) {
                    mEncoder.onGetYuvFrame(data);
                }
            }
        });
    }

    public void startEncode() {
        if (!mEncoder.start()) {
            return;
        }

        mic = mEncoder.chooseAudioRecord();
        if (mic == null) {
            return;
        }

        if (!mCameraView.startCamera()) {
            mEncoder.stop();
            return;
        }

        aworker = new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                startAudio();
            }
        });
        aloop = true;
        aworker.start();
    }

    public void stopEncode() {
        stopAudio();
        mCameraView.stopCamera();
        mEncoder.stop();
    }

    public void startPublish(String rtmpUrl) {
        if (mFlvMuxer != null) {
            try {
                mFlvMuxer.start(rtmpUrl);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
            mFlvMuxer.setVideoResolution(mEncoder.getOutputWidth(), mEncoder.getOutputHeight());

            startEncode();
        }
    }

    public void stopPublish() {
        if (mFlvMuxer != null) {
            stopEncode();
            mFlvMuxer.stop();
        }
    }

    public void startRecord(String recPath) {
        if (mMp4Muxer != null) {
            try {
                mMp4Muxer.record(new File(recPath));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
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

    public void swithToSoftEncoder() {
        mEncoder.swithToSoftEncoder();
    }

    public void swithToHardEncoder() {
        mEncoder.swithToHardEncoder();
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

    public int getCamraId() {
        return mCameraView.getCameraId();
    }

    public void setPreviewResolution(int width, int height) {
        mEncoder.setPreviewResolution(width, height);
    }

    public void setOutputResolution(int width, int height) {
        if (width <= height) {
            mEncoder.setPortraitResolution(width, height);
        } else {
            mEncoder.setLandscapeResolution(width, height);
        }
    }

    public void setScreenOrientation(int orientation) {
        mEncoder.setScreenOrientation(orientation);
    }

    public void setPreviewRotation(int rotation) {
        mCameraView.setPreviewRotation(rotation);
    }

    public void setVideoHDMode() {
        mEncoder.setVideoHDMode();
    }

    public void setVideoSmoothMode() {
        mEncoder.setVideoSmoothMode();
    }

    public void setSendAudioOnly(boolean flag) {
        sendAudioOnly = flag;
    }    

    public void switchCameraFace(int id) {
        mCameraView.setCameraId(id);
        mCameraView.stopCamera();
        if (id == 0) {
            mEncoder.setCameraBackFace();
        } else {
            mEncoder.setCameraFrontFace();
        }
        mCameraView.startCamera();
    }

    private void startAudio() {
        if (mic != null) {
            mic.startRecording();

            byte pcmBuffer[] = new byte[4096];
            while (aloop && !Thread.interrupted()) {
                int size = mic.read(pcmBuffer, 0, pcmBuffer.length);
                if (size <= 0) {
                    break;
                }
                mEncoder.onGetPcmFrame(pcmBuffer, size);
            }
        }
    }

    private void stopAudio() {
        aloop = false;
        if (aworker != null) {
            aworker.interrupt();
            try {
                aworker.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
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
    }

    public void switchMute() {
        AudioManager audioManager = (AudioManager) mCameraView.getContext().getSystemService(Context.AUDIO_SERVICE);
        int oldMode = audioManager.getMode();
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        boolean isMute = !audioManager.isMicrophoneMute();
        audioManager.setMicrophoneMute(isMute);
        audioManager.setMode(oldMode);
    }

    public void setPublishEventHandler(RtmpPublisher.EventHandler handler) {
        mFlvMuxer = new SrsFlvMuxer(handler);
        mEncoder.setFlvMuxer(mFlvMuxer);
    }

    public void setRecordEventHandler(SrsMp4Muxer.EventHandler handler) {
        mMp4Muxer = new SrsMp4Muxer(handler);
        mEncoder.setMp4Muxer(mMp4Muxer);
    }

    public void setNetworkEventHandler(SrsEncoder.EventHandler handler) {
        mEncoder.setNetworkEventHandler(handler);
    }
}
