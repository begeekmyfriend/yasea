package net.ossrs.yasea;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import net.ossrs.yasea.rtmp.RtmpPublisher;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Created by Leo Ma on 2016/7/25.
 */
public class SrsPublisher implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final String TAG = "SrsPublisher";

    private AudioRecord mic;
    private boolean aloop = false;
    private Thread aworker;

    private SurfaceView mCameraView;
    private Camera mCamera;

    private boolean sendAudioOnly = false;
    private int videoFrameCount;
    private long lastTimeMillis;
    private int mPreviewRotation = 90;
    private int mCamId = Camera.CameraInfo.CAMERA_FACING_FRONT;
    private double mSamplingFps;
    private byte[] mYuvPreviewFrame;

    private SrsFlvMuxer mFlvMuxer;
    private SrsMp4Muxer mMp4Muxer;
    private SrsEncoder mEncoder = new SrsEncoder();

    public SrsPublisher() {
    }

    public void startEncode() {
        if (!mEncoder.start()) {
            return;
        }

        mic = mEncoder.chooseAudioRecord();
        if (mic == null) {
            return;
        }

        startCamera();

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
        stopCamera();
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

    public double getmSamplingFps() {
        return mSamplingFps;
    }

    public int getCamraId() {
        return mCamId;
    }

    public int getNumberOfCameras() {
        return mCamera != null ? mCamera.getNumberOfCameras() : -1;
    }

    public void setPreviewResolution(int width, int height) {
        mEncoder.setPreviewResolution(width, height);
    }

    public void setOutputResolution(int width, int height) {
        mEncoder.setPortraitResolution(width, height);
    }

    public void setScreenOrientation(int orientation) {
        mEncoder.setScreenOrientation(orientation);
    }

    public void setPreviewRotation(int rotation) {
        mPreviewRotation = rotation;
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
        mCamId = id;
        stopCamera();
        mEncoder.swithCameraFace();
        startCamera();
    }

    private void startCamera() {
        if (mCamera != null) {
            return;
        }
        if (mCamId > (Camera.getNumberOfCameras() - 1) || mCamId < 0) {
            return;
        }

        mCamera = Camera.open(mCamId);

        Camera.Parameters params = mCamera.getParameters();
        Camera.Size size = mCamera.new Size(mEncoder.getPreviewWidth(), mEncoder.getPreviewHeight());
        if (!params.getSupportedPreviewSizes().contains(size) || !params.getSupportedPictureSizes().contains(size)) {
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(),
                    new IllegalArgumentException(String.format("Unsupported resolution %dx%d", size.width, size.height)));
        }

        mYuvPreviewFrame = new byte[mEncoder.getPreviewWidth() * mEncoder.getPreviewHeight() * 3 / 2];

        /***** set parameters *****/
        //params.set("orientation", "portrait");
        //params.set("orientation", "landscape");
        //params.setRotation(90);
        params.setPictureSize(mEncoder.getPreviewWidth(), mEncoder.getPreviewHeight());
        params.setPreviewSize(mEncoder.getPreviewWidth(), mEncoder.getPreviewHeight());
        int[] range = findClosestFpsRange(SrsEncoder.VFPS, params.getSupportedPreviewFpsRange());
        params.setPreviewFpsRange(range[0], range[1]);
        params.setPreviewFormat(ImageFormat.NV21);
        params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
        params.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);

        List<String> supportedFocusModes = params.getSupportedFocusModes();

        if (!supportedFocusModes.isEmpty()) {
            if(supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)){
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }else{
                params.setFocusMode(supportedFocusModes.get(0));
            }
        }

        mCamera.setParameters(params);

        mCamera.setDisplayOrientation(mPreviewRotation);

        mCamera.addCallbackBuffer(mYuvPreviewFrame);
        mCamera.setPreviewCallbackWithBuffer(this);
        try {
            if (mCameraView != null){
                mCamera.setPreviewDisplay(mCameraView.getHolder());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        mCamera.startPreview();
    }

    private static int[] findClosestFpsRange(int expectedFps, List<int[]> fpsRanges) {
        expectedFps *= 1000;
        int[] closestRange = fpsRanges.get(0);
        int measure = Math.abs(closestRange[0] - expectedFps) + Math.abs(closestRange[1] - expectedFps);
        for (int[] range : fpsRanges) {
            if (range[0] <= expectedFps && range[1] >= expectedFps) {
                int curMeasure = Math.abs(range[0] - expectedFps) + Math.abs(range[1] - expectedFps);
                if (curMeasure < measure) {
                    closestRange = range;
                    measure = curMeasure;
                }
            }
        }
        return closestRange;
    }

    private void stopCamera() {
        if (mCamera != null) {
            // need to SET NULL CB before stop preview!!!
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    private void onGetYuvFrame(byte[] data) {
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

    public void setSurfaceView(SurfaceView surfaceView) {
        mCameraView = surfaceView;
        mCameraView.getHolder().addCallback(this);
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        onGetYuvFrame(data);
        camera.addCallbackBuffer(mYuvPreviewFrame);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceCreated(SurfaceHolder arg0) {
        if (mCamera != null) {
            try {
                mCamera.setPreviewDisplay(mCameraView.getHolder());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder arg0) {
    }
}
