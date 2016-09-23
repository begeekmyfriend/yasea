package net.ossrs.yasea.rtmp;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import net.ossrs.yasea.rtmp.io.RtmpConnection;

/**
 * Srs implementation of an RTMP publisher
 * 
 * @author francois, leoma
 */
public class DefaultRtmpPublisher implements RtmpPublisher {

    private RtmpConnection rtmpConnection;

    public DefaultRtmpPublisher(RtmpPublisher.EventHandler handler) {
        rtmpConnection = new RtmpConnection(handler);
    }

    @Override
    public boolean connect(String url) throws IOException {
        return rtmpConnection.connect(url);
    }

    @Override
    public void shutdown() {
        rtmpConnection.shutdown();
    }

    @Override
    public boolean publish(String publishType) throws IllegalStateException, IOException {
        if (publishType == null) {
            throw new IllegalStateException("No publish type specified");
        }
        return rtmpConnection.publish(publishType);
    }

    @Override
    public void closeStream() throws IllegalStateException {
        rtmpConnection.closeStream();
    }

    @Override
    public void publishVideoData(byte[] data, int dts) throws IllegalStateException {
        if (data == null || data.length == 0 || dts < 0) {
            throw new IllegalStateException("Invalid Video Data");
        }
        rtmpConnection.publishVideoData(data, dts);
    }

    @Override
    public void publishAudioData(byte[] data, int dts) throws IllegalStateException {
        if (data == null || data.length == 0 || dts < 0) {
            throw new IllegalStateException("Invalid Audio Data");
        }
        rtmpConnection.publishAudioData(data, dts);
    }

    @Override
    public AtomicInteger getVideoFrameCacheNumber() {
        return rtmpConnection.getVideoFrameCacheNumber();
    }

    @Override
    public final EventHandler getEventHandler() {
        return rtmpConnection.getEventHandler();
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
