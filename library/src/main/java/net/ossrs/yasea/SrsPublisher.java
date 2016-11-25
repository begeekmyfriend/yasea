package net.ossrs.yasea;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;

import com.github.faucamp.simplertmp.RtmpHandler;

import java.io.File;

/**
 * Created by Leo Ma on 2016/7/25.
 */
public class SrsPublisher {

    private static AudioRecord mic;
    private static AcousticEchoCanceler aec;
    private static AutomaticGainControl agc;
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
            mFlvMuxer.start(rtmpUrl);
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
            mMp4Muxer.record(new File(recPath));
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
        int[] resolution = mCameraView.setPreviewResolution(width, height);
        mEncoder.setPreviewResolution(resolution[0], resolution[1]);
    }

    public void setOutputResolution(int width, int height) {
        if (width <= height) {
            mEncoder.setPortraitResolution(width, height);
        } else {
            mEncoder.setLandscapeResolution(width, height);
        }
    }

    public void setScreenOrientation(int orientation) {
        mCameraView.setPreviewOrientation(orientation);
        mEncoder.setScreenOrientation(orientation);
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

    public void switchMute() {
        AudioManager audioManager = (AudioManager) mCameraView.getContext().getSystemService(Context.AUDIO_SERVICE);
        int oldMode = audioManager.getMode();
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        boolean isMute = !audioManager.isMicrophoneMute();
        audioManager.setMicrophoneMute(isMute);
        audioManager.setMode(oldMode);
    }

    public void setRtmpHandler(RtmpHandler handler) {
        mFlvMuxer = new SrsFlvMuxer(handler);
        mEncoder.setFlvMuxer(mFlvMuxer);
    }

    public void setRecordHandler(SrsRecordHandler handler) {
        mMp4Muxer = new SrsMp4Muxer(handler);
        mEncoder.setMp4Muxer(mMp4Muxer);
    }

    public void setEncodeHandler(SrsEncodeHandler handler) {
        mEncoder.setEncodeHandler(handler);
    }
}
