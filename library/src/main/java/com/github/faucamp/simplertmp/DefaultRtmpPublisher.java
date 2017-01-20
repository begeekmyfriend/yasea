package com.github.faucamp.simplertmp;

import java.util.concurrent.atomic.AtomicInteger;

import com.github.faucamp.simplertmp.io.RtmpConnection;

/**
 * Srs implementation of an RTMP publisher
 * 
 * @author francois, leoma
 */
public class DefaultRtmpPublisher implements RtmpPublisher {

    private RtmpConnection rtmpConnection;

    public DefaultRtmpPublisher(RtmpHandler handler) {
        rtmpConnection = new RtmpConnection(handler);
    }

    @Override
    public boolean connect(String url) {
        return rtmpConnection.connect(url);
    }

    @Override
    public boolean publish(String publishType) {
        return rtmpConnection.publish(publishType);
    }

    @Override
    public void close() {
        rtmpConnection.close();
    }

    @Override
    public void publishVideoData(byte[] data, int size, int dts) {
        rtmpConnection.publishVideoData(data, size, dts);
    }

    @Override
    public void publishAudioData(byte[] data, int size, int dts) {
        rtmpConnection.publishAudioData(data, size, dts);
    }

    @Override
    public AtomicInteger getVideoFrameCacheNumber() {
        return rtmpConnection.getVideoFrameCacheNumber();
    }

    @Override
    public final String getServerIpAddr() {
        return rtmpConnection.getServerIpAddr();
    }

    @Override
    public final int getServerPid() {
        return rtmpConnection.getServerPid();
    }

    @Override
    public final int getServerId() {
        return rtmpConnection.getServerId();
    }

    @Override
    public void setVideoResolution(int width, int height) {
        rtmpConnection.setVideoResolution(width, height);
    }

}
