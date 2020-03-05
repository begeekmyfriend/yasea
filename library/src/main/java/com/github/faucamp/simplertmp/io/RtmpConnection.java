package com.github.faucamp.simplertmp.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;

import android.util.Log;

import com.github.faucamp.simplertmp.RtmpHandler;
import com.github.faucamp.simplertmp.RtmpPublisher;
import com.github.faucamp.simplertmp.amf.AmfMap;
import com.github.faucamp.simplertmp.amf.AmfNull;
import com.github.faucamp.simplertmp.amf.AmfNumber;
import com.github.faucamp.simplertmp.amf.AmfObject;
import com.github.faucamp.simplertmp.amf.AmfString;
import com.github.faucamp.simplertmp.packets.Abort;
import com.github.faucamp.simplertmp.packets.Data;
import com.github.faucamp.simplertmp.packets.Handshake;
import com.github.faucamp.simplertmp.packets.Command;
import com.github.faucamp.simplertmp.packets.Audio;
import com.github.faucamp.simplertmp.packets.SetPeerBandwidth;
import com.github.faucamp.simplertmp.packets.Video;
import com.github.faucamp.simplertmp.packets.UserControl;
import com.github.faucamp.simplertmp.packets.RtmpPacket;
import com.github.faucamp.simplertmp.packets.WindowAckSize;

/**
 * Main RTMP connection implementation class
 * 
 * @author francois, leoma
 */
public class RtmpConnection implements RtmpPublisher {

    private static final String TAG = "RtmpConnection";
    private static final Pattern rtmpUrlPattern = Pattern.compile("^rtmp://([^/:]+)(:(\\d+))*/([^/]+)(/(.*))*$");

    private RtmpHandler mHandler;
    private int port;
    private String host;
    private String appName;
    private String streamName;
    private String publishType;
    private String swfUrl;
    private String tcUrl;
    private String pageUrl;
    private Socket socket;
    private String srsServerInfo = "";
    private String socketExceptionCause = "";
    private RtmpSessionInfo rtmpSessionInfo;
    private RtmpDecoder rtmpDecoder;
    private BufferedInputStream inputStream;
    private BufferedOutputStream outputStream;
    private Thread rxPacketHandler;
    private volatile boolean connected = false;
    private volatile boolean publishPermitted = false;
    private final Object connectingLock = new Object();
    private final Object publishLock = new Object();
    private AtomicInteger videoFrameCacheNumber = new AtomicInteger(0);
    private int currentStreamId = 0;
    private int transactionIdCounter = 0;
    private AmfString serverIpAddr;
    private AmfNumber serverPid;
    private AmfNumber serverId;
    private int videoWidth;
    private int videoHeight;
    private int videoFrameCount;
    private int videoDataLength;
    private int audioFrameCount;
    private int audioDataLength;
    private long videoLastTimeMillis;
    private long audioLastTimeMillis;

    public RtmpConnection(RtmpHandler handler) {
        mHandler = handler;
    }

    private void handshake(InputStream in, OutputStream out) throws IOException {
        Handshake handshake = new Handshake();
        handshake.writeC0(out);
        handshake.writeC1(out); // Write C1 without waiting for S0
        out.flush();
        handshake.readS0(in);
        handshake.readS1(in);
        handshake.writeC2(out);
        out.flush();
        handshake.readS2(in);
    }

    @Override
    public boolean connect(String url) {
        Matcher matcher = rtmpUrlPattern.matcher(url);
        if (matcher.matches()) {
            tcUrl = url.substring(0, url.lastIndexOf('/'));
            swfUrl = "";
            pageUrl = "";
            host = matcher.group(1);
            String portStr = matcher.group(3);
            port = portStr != null ? Integer.parseInt(portStr) : 1935;
            appName = matcher.group(4);
            streamName = matcher.group(6);
        } else {
            mHandler.notifyRtmpIllegalArgumentException(new IllegalArgumentException(
                "Invalid RTMP URL. Must be in format: rtmp://host[:port]/application/streamName"));
            return false;
        }

        if (streamName == null || appName == null) {
            mHandler.notifyRtmpIllegalArgumentException(new IllegalArgumentException(
                "Invalid RTMP URL. Must be in format: rtmp://host[:port]/application/streamName"));
            return false;
        }

        // socket connection
        Log.d(TAG, "connect() called. Host: " + host + ", port: " + port + ", appName: " + appName + ", publishPath: " + streamName);
        rtmpSessionInfo = new RtmpSessionInfo();
        rtmpDecoder = new RtmpDecoder(rtmpSessionInfo);
        socket = new Socket();
        SocketAddress socketAddress = new InetSocketAddress(host, port);
        try {
            socket.connect(socketAddress, 3000);
            inputStream = new BufferedInputStream(socket.getInputStream());
            outputStream = new BufferedOutputStream(socket.getOutputStream());
            Log.d(TAG, "connect(): socket connection established, doing handhake...");
            handshake(inputStream, outputStream);
            Log.d(TAG, "connect(): handshake done");
        } catch (IOException e) {
            e.printStackTrace();
            mHandler.notifyRtmpIOException(e);
            return false;
        }

        // Start the "main" handling thread
        rxPacketHandler = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    Log.d(TAG, "starting main rx handler loop");
                    handleRxPacketLoop();
                } catch (IOException ex) {
                    Logger.getLogger(RtmpConnection.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });
        rxPacketHandler.start();

        return rtmpConnect();
    }

    private boolean rtmpConnect() {
        if (connected) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("Already connected to RTMP server"));
            return false;
        }

        // Mark session timestamp of all chunk stream information on connection.
        ChunkStreamInfo.markSessionTimestampTx();

        Log.d(TAG, "rtmpConnect(): Building 'connect' invoke packet");
        ChunkStreamInfo chunkStreamInfo = rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_CID_OVER_CONNECTION);
        Command invoke = new Command("connect", ++transactionIdCounter, chunkStreamInfo);
        invoke.getHeader().setMessageStreamId(0);
        AmfObject args = new AmfObject();
        args.setProperty("app", appName);
        args.setProperty("flashVer", "LNX 11,2,202,233"); // Flash player OS: Linux, version: 11.2.202.233
        args.setProperty("swfUrl", swfUrl);
        args.setProperty("tcUrl", tcUrl);
        args.setProperty("fpad", false);
        args.setProperty("capabilities", 239);
        args.setProperty("audioCodecs", 3575);
        args.setProperty("videoCodecs", 252);
        args.setProperty("videoFunction", 1);
        args.setProperty("pageUrl", pageUrl);
        args.setProperty("objectEncoding", 0);
        invoke.addData(args);
        sendRtmpPacket(invoke);
        mHandler.notifyRtmpConnecting("Connecting");

        synchronized (connectingLock) {
            try {
                connectingLock.wait(5000);
            } catch (InterruptedException ex) {
                // do nothing
            }
        }
        if (!connected) {
            shutdown();
        }
        return connected;
    }

    @Override
    public boolean publish(String type) {
        if (type == null) {
            mHandler.notifyRtmpIllegalArgumentException(new IllegalArgumentException("No publish type specified"));
            return false;
        }
        publishType = type;
        return createStream();
    }

    private boolean createStream() {
        if (!connected) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("Not connected to RTMP server"));
            return false;
        }
        if (currentStreamId != 0) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("Current stream object has existed"));
            return false;
        }

        Log.d(TAG, "createStream(): Sending releaseStream command...");
        // transactionId == 2
        Command releaseStream = new Command("releaseStream", ++transactionIdCounter);
        releaseStream.getHeader().setChunkStreamId(ChunkStreamInfo.RTMP_CID_OVER_STREAM);
        releaseStream.addData(new AmfNull());  // command object: null for "createStream"
        releaseStream.addData(streamName);  // command object: null for "releaseStream"
        sendRtmpPacket(releaseStream);

        Log.d(TAG, "createStream(): Sending FCPublish command...");
        // transactionId == 3
        Command FCPublish = new Command("FCPublish", ++transactionIdCounter);
        FCPublish.getHeader().setChunkStreamId(ChunkStreamInfo.RTMP_CID_OVER_STREAM);
        FCPublish.addData(new AmfNull());  // command object: null for "FCPublish"
        FCPublish.addData(streamName);
        sendRtmpPacket(FCPublish);

        Log.d(TAG, "createStream(): Sending createStream command...");
        ChunkStreamInfo chunkStreamInfo = rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_CID_OVER_CONNECTION);
        // transactionId == 4
        Command createStream = new Command("createStream", ++transactionIdCounter, chunkStreamInfo);
        createStream.addData(new AmfNull());  // command object: null for "createStream"
        sendRtmpPacket(createStream);

        // Waiting for "NetStream.Publish.Start" response.
        synchronized (publishLock) {
            try {
                publishLock.wait(5000);
            } catch (InterruptedException ex) {
                // do nothing
            }
        }
        if (publishPermitted) {
            mHandler.notifyRtmpConnected("Connected" + srsServerInfo);
        } else {
            shutdown();
        }
        return publishPermitted;
    }

    private void fmlePublish() {
        if (!connected) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("Not connected to RTMP server"));
            return;
        }
        if (currentStreamId == 0) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("No current stream object exists"));
            return;
        }

        Log.d(TAG, "fmlePublish(): Sending publish command...");
        // transactionId == 0
        Command publish = new Command("publish", 0);
        publish.getHeader().setChunkStreamId(ChunkStreamInfo.RTMP_CID_OVER_STREAM);
        publish.getHeader().setMessageStreamId(currentStreamId);
        publish.addData(new AmfNull());  // command object: null for "publish"
        publish.addData(streamName);
        publish.addData(publishType);
        sendRtmpPacket(publish);
    }

    private void onMetaData() {
        if (!connected) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("Not connected to RTMP server"));
            return;
        }
        if (currentStreamId == 0) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("No current stream object exists"));
            return;
        }

        Log.d(TAG, "onMetaData(): Sending empty onMetaData...");
        Data metadata = new Data("@setDataFrame");
        metadata.getHeader().setMessageStreamId(currentStreamId);
        metadata.addData("onMetaData");
        AmfMap ecmaArray = new AmfMap();
        ecmaArray.setProperty("duration", 0);
        ecmaArray.setProperty("width", videoWidth);
        ecmaArray.setProperty("height", videoHeight);
        ecmaArray.setProperty("videodatarate", 0);
        ecmaArray.setProperty("framerate", 0);
        ecmaArray.setProperty("audiodatarate", 0);
        ecmaArray.setProperty("audiosamplerate", 44100);
        ecmaArray.setProperty("audiosamplesize", 16);
        ecmaArray.setProperty("stereo", true);
        ecmaArray.setProperty("filesize", 0);
        metadata.addData(ecmaArray);
        sendRtmpPacket(metadata);
    }

    @Override
    public void close() {
        if (socket != null) {
            closeStream();
        }
        shutdown();
    }

    private void closeStream() {
        if (!connected) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("Not connected to RTMP server"));
            return;
        }
        if (currentStreamId == 0) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("No current stream object exists"));
            return;
        }
        if (!publishPermitted) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("Not get _result(Netstream.Publish.Start)"));
            return;
        }
        Log.d(TAG, "closeStream(): setting current stream ID to 0");
        Command closeStream = new Command("closeStream", 0);
        closeStream.getHeader().setChunkStreamId(ChunkStreamInfo.RTMP_CID_OVER_STREAM);
        closeStream.getHeader().setMessageStreamId(currentStreamId);
        closeStream.addData(new AmfNull());
        sendRtmpPacket(closeStream);
        mHandler.notifyRtmpStopped();
    }

    private void shutdown() {
        if (socket != null) {
            try {
                // It will raise EOFException in handleRxPacketThread
                socket.shutdownInput();
                // It will raise SocketException in sendRtmpPacket
                socket.shutdownOutput();
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }

            // shutdown rxPacketHandler
            if (rxPacketHandler != null) {
                rxPacketHandler.interrupt();
                try {
                    rxPacketHandler.join();
                } catch (InterruptedException ie) {
                    rxPacketHandler.interrupt();
                }
                rxPacketHandler = null;
            }

            // shutdown socket as well as its input and output stream
            try {
                socket.close();
                Log.d(TAG, "socket closed");
            } catch (IOException ex) {
                Log.e(TAG, "shutdown(): failed to close socket", ex);
            }

            mHandler.notifyRtmpDisconnected();
        }

        reset();
    }

    private void reset() {
        connected = false;
        publishPermitted = false;
        tcUrl = null;
        swfUrl = null;
        pageUrl = null;
        appName = null;
        streamName = null;
        publishType = null;
        currentStreamId = 0;
        transactionIdCounter = 0;
        videoFrameCacheNumber.set(0);
        socketExceptionCause = "";
        serverIpAddr = null;
        serverPid = null;
        serverId = null;
        socket = null;
        rtmpSessionInfo = null;
        rtmpDecoder = null;
    }

    @Override
    public void publishAudioData(byte[] data, int size, int dts) {
        if (data == null || data.length == 0 || dts < 0) {
            mHandler.notifyRtmpIllegalArgumentException(new IllegalArgumentException("Invalid Audio Data"));
            return;
        }
        if (!connected) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("Not connected to RTMP server"));
            return;
        }
        if (currentStreamId == 0) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("No current stream object exists"));
            return;
        }
        if (!publishPermitted) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("Not get _result(Netstream.Publish.Start)"));
            return;
        }
        Audio audio = new Audio();
        audio.setData(data, size);
        audio.getHeader().setAbsoluteTimestamp(dts);
        audio.getHeader().setMessageStreamId(currentStreamId);
        sendRtmpPacket(audio);
        calcAudioBitrate(audio.getHeader().getPacketLength());
        mHandler.notifyRtmpAudioStreaming();
    }

    @Override
    public void publishVideoData(byte[] data, int size, int dts) {
        if (data == null || data.length == 0 || dts < 0) {
            mHandler.notifyRtmpIllegalArgumentException(new IllegalArgumentException("Invalid Video Data"));
            return;
        }
        if (!connected) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("Not connected to RTMP server"));
            return;
        }
        if (currentStreamId == 0) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("No current stream object exists"));
            return;
        }
        if (!publishPermitted) {
            mHandler.notifyRtmpIllegalStateException(new IllegalStateException("Not get _result(Netstream.Publish.Start)"));
            return;
        }
        Video video = new Video();
        video.setData(data, size);
        video.getHeader().setAbsoluteTimestamp(dts);
        video.getHeader().setMessageStreamId(currentStreamId);
        sendRtmpPacket(video);
        videoFrameCacheNumber.decrementAndGet();
        calcVideoFpsAndBitrate(video.getHeader().getPacketLength());
        mHandler.notifyRtmpVideoStreaming();
    }

    private void calcVideoFpsAndBitrate(int length) {
        videoDataLength += length;
        if (videoFrameCount == 0) {
            videoLastTimeMillis = System.nanoTime() / 1000000;
            videoFrameCount++;
        } else {
            if (++videoFrameCount >= 48) {
                long diffTimeMillis = System.nanoTime() / 1000000 - videoLastTimeMillis;
                mHandler.notifyRtmpVideoFpsChanged((double) videoFrameCount * 1000 / diffTimeMillis);
                mHandler.notifyRtmpVideoBitrateChanged((double) videoDataLength * 8 * 1000 / diffTimeMillis);
                videoFrameCount = 0;
                videoDataLength = 0;
            }
        }
    }

    private void calcAudioBitrate(int length) {
        audioDataLength += length;
        if (audioFrameCount == 0) {
            audioLastTimeMillis = System.nanoTime() / 1000000;
            audioFrameCount++;
        } else {
            if (++audioFrameCount >= 48) {
                long diffTimeMillis = System.nanoTime() / 1000000 - audioLastTimeMillis;
                mHandler.notifyRtmpAudioBitrateChanged((double) audioDataLength * 8 * 1000 / diffTimeMillis);
                audioFrameCount = 0;
                audioDataLength = 0;
            }
        }
    }

    private void sendRtmpPacket(RtmpPacket rtmpPacket) {
        try {
            ChunkStreamInfo chunkStreamInfo = rtmpSessionInfo.getChunkStreamInfo(rtmpPacket.getHeader().getChunkStreamId());
            chunkStreamInfo.setPrevHeaderTx(rtmpPacket.getHeader());
            if (!(rtmpPacket instanceof Video || rtmpPacket instanceof Audio)) {
                rtmpPacket.getHeader().setAbsoluteTimestamp((int) chunkStreamInfo.markAbsoluteTimestampTx());
            }
            rtmpPacket.writeTo(outputStream, rtmpSessionInfo.getTxChunkSize(), chunkStreamInfo);
            Log.d(TAG, "wrote packet: " + rtmpPacket + ", size: " + rtmpPacket.getHeader().getPacketLength());
            if (rtmpPacket instanceof Command) {
                rtmpSessionInfo.addInvokedCommand(((Command) rtmpPacket).getTransactionId(), ((Command) rtmpPacket).getCommandName());
            }
            outputStream.flush();
        } catch (SocketException se) {
            // Since there are still remaining AV frame in the cache, we set a flag to guarantee the
            // socket exception only issue one time.
            if (!socketExceptionCause.contentEquals(se.getMessage())) {
                socketExceptionCause = se.getMessage();
                Log.e(TAG, "Caught SocketException during write loop, shutting down: " + se.getMessage());
                mHandler.notifyRtmpSocketException(se);
            }
        } catch (IOException ioe) {
            Log.e(TAG, "Caught IOException during write loop, shutting down: " + ioe.getMessage());
            mHandler.notifyRtmpIOException(ioe);
        }
    }

    private void handleRxPacketLoop() throws IOException {
        // Handle all queued received RTMP packets
        while (!Thread.interrupted()) {
            try {
                // It will be blocked when no data in input stream buffer
                RtmpPacket rtmpPacket = rtmpDecoder.readPacket(inputStream);
                if (rtmpPacket != null) {
                    //Log.d(TAG, "handleRxPacketLoop(): RTMP rx packet message type: " + rtmpPacket.getHeader().getMessageType());
                    switch (rtmpPacket.getHeader().getMessageType()) {
                        case ABORT:
                            rtmpSessionInfo.getChunkStreamInfo(((Abort) rtmpPacket).getChunkStreamId()).clearStoredChunks();
                            break;
                        case USER_CONTROL_MESSAGE:
                            UserControl user = (UserControl) rtmpPacket;
                            switch (user.getType()) {
                                case STREAM_BEGIN:
                                    Log.d(TAG, "handleRxPacketLoop(): Receive STREAM_BEGIN");
                                    break;
                                case PING_REQUEST:
                                    ChunkStreamInfo channelInfo = rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_CID_PROTOCOL_CONTROL);
                                    Log.d(TAG, "handleRxPacketLoop(): Sending PONG reply..");
                                    UserControl pong = new UserControl(user, channelInfo);
                                    sendRtmpPacket(pong);
                                    break;
                                case STREAM_EOF:
                                    Log.i(TAG, "handleRxPacketLoop(): Stream EOF reached, closing RTMP writer...");
                                    break;
                                default:
                                    // Ignore...
                                    break;
                            }
                            break;
                        case WINDOW_ACKNOWLEDGEMENT_SIZE:
                            WindowAckSize windowAckSize = (WindowAckSize) rtmpPacket;
                            int size = windowAckSize.getAcknowledgementWindowSize();
                            Log.d(TAG, "handleRxPacketLoop(): Setting acknowledgement window size: " + size);
                            rtmpSessionInfo.setAcknowledgmentWindowSize(size);
                            break;
                        case SET_PEER_BANDWIDTH:
                            SetPeerBandwidth bw = (SetPeerBandwidth) rtmpPacket;
                            rtmpSessionInfo.setAcknowledgmentWindowSize(bw.getAcknowledgementWindowSize());
                            int acknowledgementWindowsize = rtmpSessionInfo.getAcknowledgementWindowSize();
                            ChunkStreamInfo chunkStreamInfo = rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_CID_PROTOCOL_CONTROL);
                            Log.d(TAG, "handleRxPacketLoop(): Send acknowledgement window size: " + acknowledgementWindowsize);
                            sendRtmpPacket(new WindowAckSize(acknowledgementWindowsize, chunkStreamInfo));
                            // Set socket option
                            socket.setSendBufferSize(acknowledgementWindowsize);
                            break;
                        case COMMAND_AMF0:
                            handleRxInvoke((Command) rtmpPacket);
                            break;
                        default:
                            Log.w(TAG, "handleRxPacketLoop(): Not handling unimplemented/unknown packet of type: " + rtmpPacket.getHeader().getMessageType());
                            break;
                    }
                }
            } catch (EOFException eof) {
                Thread.currentThread().interrupt();
            } catch (SocketException se) {
                Log.e(TAG, "Caught SocketException while reading/decoding packet, shutting down: " + se.getMessage());
                mHandler.notifyRtmpSocketException(se);
            } catch (IOException ioe) {
                Log.e(TAG, "Caught exception while reading/decoding packet, shutting down: " + ioe.getMessage());
                mHandler.notifyRtmpIOException(ioe);
            }
        }
    }

    private void handleRxInvoke(Command invoke) throws IOException {
        String commandName = invoke.getCommandName();

        if (commandName.equals("_result")) {
            // This is the result of one of the methods invoked by us
            String method = rtmpSessionInfo.takeInvokedCommand(invoke.getTransactionId());

            Log.d(TAG, "handleRxInvoke: Got result for invoked method: " + method);
            if ("connect".equals(method)) {
                // Capture server ip/pid/id information if any
                srsServerInfo = onSrsServerInfo(invoke);
                // We can now send createStream commands
                connected = true;
                synchronized (connectingLock) {
                    connectingLock.notifyAll();
                }
            } else if ("createStream".contains(method)) {
                // Get stream id
                currentStreamId = (int) ((AmfNumber) invoke.getData().get(1)).getValue();
                Log.d(TAG, "handleRxInvoke(): Stream ID to publish: " + currentStreamId);
                if (streamName != null && publishType != null) {
                    fmlePublish();
                }
            } else if ("releaseStream".contains(method)) {
                Log.d(TAG, "handleRxInvoke(): 'releaseStream'");
            } else if ("FCPublish".contains(method)) {
                Log.d(TAG, "handleRxInvoke(): 'FCPublish'");
            } else {
                Log.w(TAG, "handleRxInvoke(): '_result' message received for unknown method: " + method);
            }
        } else if (commandName.equals("onBWDone")) {
            Log.d(TAG, "handleRxInvoke(): 'onBWDone'");
        } else if (commandName.equals("onFCPublish")) {
            Log.d(TAG, "handleRxInvoke(): 'onFCPublish'");
        } else if (commandName.equals("onStatus")) {
            String code = ((AmfString) ((AmfObject) invoke.getData().get(1)).getProperty("code")).getValue();
            Log.d(TAG, "handleRxInvoke(): onStatus " + code);
            if (code.equals("NetStream.Publish.Start")) {
                onMetaData();
                // We can now publish AV data
                publishPermitted = true;
                synchronized (publishLock) {
                    publishLock.notifyAll();
                }
            }
        } else {
            Log.e(TAG, "handleRxInvoke(): Unknown/unhandled server invoke: " + invoke);
        }
    }

    private String onSrsServerInfo(Command invoke) {
        // SRS server special information
        AmfObject objData = (AmfObject) invoke.getData().get(1);
        if ((objData).getProperty("data") instanceof AmfObject) {
            objData = ((AmfObject) objData.getProperty("data"));
            serverIpAddr = (AmfString) objData.getProperty("srs_server_ip");
            serverPid = (AmfNumber) objData.getProperty("srs_pid");
            serverId = (AmfNumber) objData.getProperty("srs_id");
        }
        String info = "";
        info += serverIpAddr == null ? "" : " ip: " + serverIpAddr.getValue();
        info += serverPid == null ? "" : " pid: " + (int) serverPid.getValue();
        info += serverId == null ? "" : " id: " + (int) serverId.getValue();
        return info;
    }

    @Override
    public AtomicInteger getVideoFrameCacheNumber() {
        return videoFrameCacheNumber;
    }

    @Override
    public final String getServerIpAddr() {
        return serverIpAddr == null ? null : serverIpAddr.getValue();
    }

    @Override
    public final int getServerPid() {
        return serverPid == null ? 0 : (int) serverPid.getValue();
    }

    @Override
    public final int getServerId() {
        return serverId == null ? 0 : (int) serverId.getValue();
    }

    @Override
    public void setVideoResolution(int width, int height) {
        videoWidth = width;
        videoHeight = height;
    }
}
