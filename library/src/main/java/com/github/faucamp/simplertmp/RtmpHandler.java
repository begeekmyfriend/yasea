package com.github.faucamp.simplertmp;

import android.os.Handler;
import android.os.Message;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.SocketException;

/**
 * Created by leo.ma on 2016/11/3.
 */

public class RtmpHandler extends Handler {

    private static final int MSG_RTMP_CONNECTING = 0;
    private static final int MSG_RTMP_CONNECTED = 1;
    private static final int MSG_RTMP_VIDEO_STREAMING = 2;
    private static final int MSG_RTMP_AUDIO_STREAMING = 3;
    private static final int MSG_RTMP_STOPPED = 4;
    private static final int MSG_RTMP_DISCONNECTED = 5;
    private static final int MSG_RTMP_VIDEO_FPS_CHANGED = 6;
    private static final int MSG_RTMP_VIDEO_BITRATE_CHANGED = 7;
    private static final int MSG_RTMP_AUDIO_BITRATE_CHANGED = 8;

    private static final int MSG_RTMP_SOCKET_EXCEPTION = 9;
    private static final int MSG_RTMP_IO_EXCEPTION = 10;
    private static final int MSG_RTMP_ILLEGAL_ARGUMENT_EXCEPTION = 11;
    private static final int MSG_RTMP_ILLEGAL_STATE_EXCEPTION = 12;

    private WeakReference<RtmpListener> mWeakListener;

    public RtmpHandler(RtmpListener listener) {
        mWeakListener = new WeakReference<>(listener);
    }

    public void notifyRtmpConnecting(String msg) {
        obtainMessage(MSG_RTMP_CONNECTING, msg).sendToTarget();
    }

    public void notifyRtmpConnected(String msg) {
        obtainMessage(MSG_RTMP_CONNECTED, msg).sendToTarget();
    }

    public void notifyRtmpVideoStreaming() {
        sendEmptyMessage(MSG_RTMP_VIDEO_STREAMING);
    }

    public void notifyRtmpAudioStreaming() {
        sendEmptyMessage(MSG_RTMP_AUDIO_STREAMING);
    }

    public void notifyRtmpStopped() {
        sendEmptyMessage(MSG_RTMP_STOPPED);
    }

    public void notifyRtmpDisconnected() {
        sendEmptyMessage(MSG_RTMP_DISCONNECTED);
    }

    public void notifyRtmpVideoFpsChanged(double fps) {
        obtainMessage(MSG_RTMP_VIDEO_FPS_CHANGED, fps).sendToTarget();
    }

    public void notifyRtmpVideoBitrateChanged(double bitrate) {
        obtainMessage(MSG_RTMP_VIDEO_BITRATE_CHANGED, bitrate).sendToTarget();
    }

    public void notifyRtmpAudioBitrateChanged(double bitrate) {
        obtainMessage(MSG_RTMP_AUDIO_BITRATE_CHANGED, bitrate).sendToTarget();
    }

    public void notifyRtmpSocketException(SocketException e) {
        obtainMessage(MSG_RTMP_SOCKET_EXCEPTION, e).sendToTarget();
    }

    public void notifyRtmpIOException(IOException e) {
        obtainMessage(MSG_RTMP_IO_EXCEPTION, e).sendToTarget();
    }

    public void notifyRtmpIllegalArgumentException(IllegalArgumentException e) {
        obtainMessage(MSG_RTMP_ILLEGAL_ARGUMENT_EXCEPTION, e).sendToTarget();
    }

    public void notifyRtmpIllegalStateException(IllegalStateException e) {
        obtainMessage(MSG_RTMP_ILLEGAL_STATE_EXCEPTION, e).sendToTarget();
    }

    @Override  // runs on UI thread
    public void handleMessage(Message msg) {
        RtmpListener listener = mWeakListener.get();
        if (listener == null) {
            return;
        }

        switch (msg.what) {
            case MSG_RTMP_CONNECTING:
                listener.onRtmpConnecting((String) msg.obj);
                break;
            case MSG_RTMP_CONNECTED:
                listener.onRtmpConnected((String) msg.obj);
                break;
            case MSG_RTMP_VIDEO_STREAMING:
                listener.onRtmpVideoStreaming();
                break;
            case MSG_RTMP_AUDIO_STREAMING:
                listener.onRtmpAudioStreaming();
                break;
            case MSG_RTMP_STOPPED:
                listener.onRtmpStopped();
                break;
            case MSG_RTMP_DISCONNECTED:
                listener.onRtmpDisconnected();
                break;
            case MSG_RTMP_VIDEO_FPS_CHANGED:
                listener.onRtmpVideoFpsChanged((double) msg.obj);
                break;
            case MSG_RTMP_VIDEO_BITRATE_CHANGED:
                listener.onRtmpVideoBitrateChanged((double) msg.obj);
                break;
            case MSG_RTMP_AUDIO_BITRATE_CHANGED:
                listener.onRtmpAudioBitrateChanged((double) msg.obj);
                break;
            case MSG_RTMP_SOCKET_EXCEPTION:
                listener.onRtmpSocketException((SocketException) msg.obj);
                break;
            case MSG_RTMP_IO_EXCEPTION:
                listener.onRtmpIOException((IOException) msg.obj);
                break;
            case MSG_RTMP_ILLEGAL_ARGUMENT_EXCEPTION:
                listener.onRtmpIllegalArgumentException((IllegalArgumentException) msg.obj);
                break;
            case MSG_RTMP_ILLEGAL_STATE_EXCEPTION:
                listener.onRtmpIllegalStateException((IllegalStateException) msg.obj);
                break;
            default:
                throw new RuntimeException("unknown msg " + msg.what);
        }
    }

    public interface RtmpListener {
        
        void onRtmpConnecting(String msg);

        void onRtmpConnected(String msg);

        void onRtmpVideoStreaming();

        void onRtmpAudioStreaming();

        void onRtmpStopped();

        void onRtmpDisconnected();

        void onRtmpVideoFpsChanged(double fps);

        void onRtmpVideoBitrateChanged(double bitrate);

        void onRtmpAudioBitrateChanged(double bitrate);

        void onRtmpSocketException(SocketException e);

        void onRtmpIOException(IOException e);

        void onRtmpIllegalArgumentException(IllegalArgumentException e);

        void onRtmpIllegalStateException(IllegalStateException e);
    }
}
