package com.github.faucamp.simplertmp;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple RTMP publisher, using vanilla Java networking (no NIO)
 * This was created primarily to address a NIO bug in Android 2.2 when
 * used with Apache Mina, but also to provide an easy-to-use way to access
 * RTMP streams
 * 
 * @author francois, leo
 */
public interface RtmpPublisher {
    /**
     * Issues an RTMP "connect" command and wait for the response.
     *
     * @param url specify the RTMP url
     * @return If succeeded return true else return false
     */
    boolean connect(String url);
    
    /**
     * Issues an RTMP "publish" command and write the media content stream packets (audio and video). 
     * 
     * @param publishType specify the way to publish raw RTMP packets among "live", "record" and "append"
     * @return If succeeded return true else return false
     * @throws IllegalStateException if the client is not connected to a RTMP server
     */
    boolean publish(String publishType);
     
    /**
     * Stop and close the current RTMP streaming client.
     */
    void close();

    /**
     * publish a video content packet to server
     *
     * @param data video stream byte array
     * @param dts video stream decoding timestamp
     */
    void publishVideoData(byte[] data, int dts);

    /**
     * publish an audio content packet to server
     *
     * @param data audio stream byte array
     * @param dts audio stream decoding timestamp
     */
    void publishAudioData(byte[] data, int dts);

    /**
     * obtain video frame number cached in publisher
     */
    AtomicInteger getVideoFrameCacheNumber();

    /**
     * obtain the IP address of the peer if any
     */
    String getServerIpAddr();

    /**
     * obtain the PID of the peer if any
     */
    int getServerPid();

    /**
     * obtain the ID of the peer if any
     */
    int getServerId();

    /**
     * set video resolution
     *
     * @param width video width
     * @param height video height
     */
    void setVideoResolution(int width, int height);

}
