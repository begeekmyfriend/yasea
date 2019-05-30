package com.sensetime.sensedrive.ui;

/*
 * Created by yicm on 2019/5/29.
 */

import android.util.Log;
import com.github.faucamp.simplertmp.RtmpHandler;

import net.ossrs.yasea.SrsCameraView;
import net.ossrs.yasea.SrsEncodeHandler;
import net.ossrs.yasea.SrsMediaControler;
import net.ossrs.yasea.SrsRecordHandler;

import java.io.IOException;
import java.net.SocketException;
import java.util.Map;

public class MediaControlerBuilder implements RtmpHandler.RtmpListener,
        SrsRecordHandler.SrsRecordListener, SrsEncodeHandler.SrsEncodeListener {
    private static String TAG = "MediaControlerBuilder";
    private static MediaControlerBuilder sInstance;
    private SrsMediaControler mMediaControler;
    private Map<String, SrsCameraView> mCameraViews;
    private final String mDmsViewKey = "dms";
    private final String mAdasViewKey = "adas";

    public static MediaControlerBuilder getInstance() {
        if (sInstance == null) {
            sInstance = new MediaControlerBuilder();
        }
        return sInstance;
    }

    public String getDmsViewKey() {
        return mDmsViewKey;
    }

    public String getAdasViewKey() {
        return mAdasViewKey;
    }

    private MediaControlerBuilder() {

    }


    /************************
     * Settings and Gettings
     */
    public MediaControlerBuilder setCameraViews(Map<String, SrsCameraView> views) {
        mCameraViews = views;
        return this;
    }

    public MediaControlerBuilder setDmsPreviewCallback(SrsCameraView.PreviewCallback callback) {
        mMediaControler.setPreviewCallback(mDmsViewKey, callback);
        return this;
    }

    public MediaControlerBuilder setAdasPreviewCallback(SrsCameraView.PreviewCallback callback) {
        mMediaControler.setPreviewCallback(mAdasViewKey, callback);
        return this;
    }

    public MediaControlerBuilder setListeners(SrsEncodeHandler encodeHandler,
                                              RtmpHandler rtmpHandler,
                                              SrsRecordHandler recordHandler) {
        mMediaControler.setEncodeHandler(encodeHandler);
        mMediaControler.setRtmpHandler(rtmpHandler);
        mMediaControler.setRecordHandler(recordHandler);
        return this;
    }

    public MediaControlerBuilder setDmsPreviewResolution(final int w, final int h) {
        mMediaControler.setViewPreviewResolution(mDmsViewKey, w, h);
        return this;
    }

    public MediaControlerBuilder setAdasPreviewResolution(final int w, final int h) {
        mMediaControler.setViewPreviewResolution(mAdasViewKey, w, h);
        return this;
    }

    // the encoder resolution same with camera preview resolution
    public MediaControlerBuilder setEncoderPreviewResolution(final int w, final int h) {
        mMediaControler.setEncoderPreviewResolution(w, h);
        return this;
    }

    public MediaControlerBuilder setRecorderOutputResolution(final int w, final int h) {
        mMediaControler.setOutputResolution(w, h);
        return this;
    }

    public MediaControlerBuilder setEncoderSmoothMode() {
        mMediaControler.setVideoSmoothMode();
        return this;
    }

    public MediaControlerBuilder setEncoderHDMode() {
        mMediaControler.setVideoHDMode();
        return this;
    }

    /************************
     * Control
     */
    public MediaControlerBuilder startCamera() {
        mMediaControler.startCamera();
        return this;
    }

    public MediaControlerBuilder stopCamera() {
        mMediaControler.stopCamera();
        return this;
    }

    public MediaControlerBuilder startCameraDataCallback() {
        mMediaControler.startDataCallback();
        return this;
    }

    public MediaControlerBuilder stopCameraDataCallback() {
        mMediaControler.stopDataCallback();
        return this;
    }

    public MediaControlerBuilder startEncode() {
        mMediaControler.startEncode();
        return this;
    }

    public MediaControlerBuilder stopEncode() {
        mMediaControler.stopEncode();
        return this;
    }

    public MediaControlerBuilder startRtmpPublish(String url) {
        mMediaControler.startPublish(url);
        return this;
    }

    public MediaControlerBuilder stopRtmpPublish(String url) {
        mMediaControler.stopPublish();
        return this;
    }

    public MediaControlerBuilder startMp4Record(String filename) {
        mMediaControler.startRecord(filename);
        return this;
    }

    public MediaControlerBuilder pauseMp4Record() {
        mMediaControler.pauseRecord();
        return this;
    }

    public MediaControlerBuilder resumeMp4Record() {
        mMediaControler.resumeRecord();
        return this;
    }

    public MediaControlerBuilder stopMp4Record() {
        mMediaControler.stopRecord();
        return this;
    }

    /************************
     * Listeners
     */
    private void handleException(Exception e) {
        try {
            Log.e(TAG, e.getMessage());
            // TODO
            mMediaControler.stopPublish();
            mMediaControler.stopRecord();

        } catch (Exception e1) {
            // Ignore
            e1.printStackTrace();
        }
    }

    // Implementation of SrsRtmpListener.
    @Override
    public void onRtmpConnecting(String msg) {
        Log.i(TAG, msg);
    }

    @Override
    public void onRtmpConnected(String msg) {
        Log.i(TAG, msg);
    }

    @Override
    public void onRtmpVideoStreaming() {

    }

    @Override
    public void onRtmpAudioStreaming() {

    }

    @Override
    public void onRtmpStopped() {
        Log.i(TAG, "rtmp publiser stoped");
    }

    @Override
    public void onRtmpDisconnected() {
        Log.i(TAG, "rtmp live streaming is disconnected");
    }

    @Override
    public void onRtmpVideoFpsChanged(double fps) {
        Log.i(TAG, String.format("rtmp fps changed : %f", fps));
    }

    @Override
    public void onRtmpVideoBitrateChanged(double bitrate) {
        int rate = (int) bitrate;
        if (rate / 1000 > 0) {
            Log.i(TAG, String.format("rtmp video bitrate: %f kbps", bitrate / 1000));
        } else {
            Log.i(TAG, String.format("rtmp video bitrate: %d bps", rate));
        }
    }

    @Override
    public void onRtmpAudioBitrateChanged(double bitrate) {
        int rate = (int) bitrate;
        if (rate / 1000 > 0) {
            Log.i(TAG, String.format("rtmp audio bitrate: %f kbps", bitrate / 1000));
        } else {
            Log.i(TAG, String.format("rtmp audio bitrate: %d bps", rate));
        }
    }

    @Override
    public void onRtmpSocketException(SocketException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIOException(IOException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIllegalArgumentException(IllegalArgumentException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIllegalStateException(IllegalStateException e) {
        handleException(e);
    }

    // Implementation of SrsRecordHandler.
    @Override
    public void onRecordPause() {
        Log.i(TAG, "record paused");
    }

    @Override
    public void onRecordResume() {
        Log.i(TAG, "record resumed");
    }

    @Override
    public void onRecordStarted(String msg) {
        Log.i(TAG, "recording file: " + msg);
    }

    @Override
    public void onRecordFinished(String msg) {
        Log.i(TAG, "MP4 file saved : " + msg);
    }

    @Override
    public void onRecordIOException(IOException e) {
        handleException(e);
    }

    @Override
    public void onRecordIllegalArgumentException(IllegalArgumentException e) {
        handleException(e);
    }

    // Implementation of SrsEncodeHandler.
    @Override
    public void onNetworkWeak() {
        Log.w(TAG, "Network weak");
    }

    @Override
    public void onNetworkResume() {
        Log.i(TAG, "Network resume");
    }

    @Override
    public void onEncodeIllegalArgumentException(IllegalArgumentException e) {
        handleException(e);
    }
}
