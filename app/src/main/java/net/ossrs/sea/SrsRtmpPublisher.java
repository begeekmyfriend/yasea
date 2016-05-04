package net.ossrs.sea;

import java.io.IOException;
import net.ossrs.sea.rtmp.RtmpPublisher;
import net.ossrs.sea.rtmp.io.RtmpConnection;

/**
 * Srs implementation of an RTMP publisher
 * 
 * @author francois, leoma
 */
public class SrsRtmpPublisher implements RtmpPublisher {

    private RtmpConnection rtmpConnection;

    public SrsRtmpPublisher(RtmpPublisher.EventHandler handler) {
        rtmpConnection = new RtmpConnection(handler);
    }

    @Override
    public void connect(String url) throws IOException {
        rtmpConnection.connect(url);
    }

    @Override
    public void shutdown() {
        rtmpConnection.shutdown();
    }

    @Override
    public void publish(String publishType) throws IllegalStateException, IOException {
        if (publishType == null) {
            throw new IllegalStateException("No publish type specified");
        }
        rtmpConnection.publish(publishType);
    }

    @Override
    public void closeStream() throws IllegalStateException {
        rtmpConnection.closeStream();
    }

    @Override
    public void publishVideoData(byte[] data) throws IllegalStateException {
        if (data == null || data.length == 0) {
            throw new IllegalStateException("Invalid Video Data");
        }
        rtmpConnection.publishVideoData(data);
    }

    @Override
    public void publishAudioData(byte[] data) throws IllegalStateException {
        if (data == null || data.length == 0) {
            throw new IllegalStateException("Invalid Audio Data");
        }
        rtmpConnection.publishAudioData(data);
    }

    @Override
    public final int getVideoFrameCacheNumber() {
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
}
