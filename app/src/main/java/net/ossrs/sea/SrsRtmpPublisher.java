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

    private RtmpPublisher rtmpConnection;

    /** 
     * Constructor for URLs in the format: rtmp://host[:port]/application[?streamName]
     * 
     * @param url a RTMP URL in the format: rtmp://host[:port]/application[?streamName]
     */
    public SrsRtmpPublisher(String url) {
        rtmpConnection = new RtmpConnection(url);
    }

    @Override
    public void connect() throws IOException {
        rtmpConnection.connect();
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
    public void publishVideoData(byte[] data, int dts) throws IllegalStateException {
        if (data == null || data.length == 0) {
            throw new IllegalStateException("Invalid Video Data");
        }
        if (dts < 0) {
            throw new IllegalStateException("Invalid DTS");
        }
        rtmpConnection.publishVideoData(data, dts);
    }

    @Override
    public void publishAudioData(byte[] data, int dts) throws IllegalStateException {
        if (data == null || data.length == 0) {
            throw new IllegalStateException("Invalid Audio Data");
        }
        if (dts < 0) {
            throw new IllegalStateException("Invalid DTS");
        }
        rtmpConnection.publishAudioData(data, dts);
    }

    public final int getVideoFrameCacheNumber() {
        return ((RtmpConnection) rtmpConnection).getVideoFrameCacheNumber();
    }

    public final String getRtmpUrl() {
        return ((RtmpConnection) rtmpConnection).getRtmpUrl();
    }
}
