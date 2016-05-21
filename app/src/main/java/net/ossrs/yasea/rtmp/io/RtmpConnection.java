package net.ossrs.yasea.rtmp.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;

import android.util.Log;

import net.ossrs.yasea.rtmp.RtmpPublisher;
import net.ossrs.yasea.rtmp.amf.AmfMap;
import net.ossrs.yasea.rtmp.amf.AmfNull;
import net.ossrs.yasea.rtmp.amf.AmfNumber;
import net.ossrs.yasea.rtmp.amf.AmfObject;
import net.ossrs.yasea.rtmp.amf.AmfString;
import net.ossrs.yasea.rtmp.packets.Abort;
import net.ossrs.yasea.rtmp.packets.Acknowledgement;
import net.ossrs.yasea.rtmp.packets.Data;
import net.ossrs.yasea.rtmp.packets.Handshake;
import net.ossrs.yasea.rtmp.packets.Command;
import net.ossrs.yasea.rtmp.packets.Audio;
import net.ossrs.yasea.rtmp.packets.Video;
import net.ossrs.yasea.rtmp.packets.UserControl;
import net.ossrs.yasea.rtmp.packets.RtmpPacket;
import net.ossrs.yasea.rtmp.packets.WindowAckSize;

/**
 * Main RTMP connection implementation class
 * 
 * @author francois, leoma
 */
public class RtmpConnection implements RtmpPublisher, PacketRxHandler {

    private static final String TAG = "RtmpConnection";
    private static final Pattern rtmpUrlPattern = Pattern.compile("^rtmp://([^/:]+)(:(\\d+))*/([^/]+)(/(.*))*$");

    private RtmpPublisher.EventHandler mHandler;
    private String appName;
    private String streamName;
    private String publishType;
    private String swfUrl;
    private String tcUrl;
    private String pageUrl;
    private Socket socket;
    private RtmpSessionInfo rtmpSessionInfo;
    private ReadThread readThread;
    private WriteThread writeThread;
    private final ConcurrentLinkedQueue<RtmpPacket> rxPacketQueue = new ConcurrentLinkedQueue<>();
    private final Object rxPacketLock = new Object();
    private volatile boolean active = false;
    private volatile boolean connecting = false;
    private volatile boolean fullyConnected = false;
    private volatile boolean publishPermitted = false;
    private final Object connectingLock = new Object();
    private final Object publishLock = new Object();
    private AtomicInteger videoFrameCacheNumber = new AtomicInteger(0);
    private int currentStreamId = -1;
    private int transactionIdCounter = 0;
    private AmfString serverIpAddr;
    private AmfNumber serverPid;
    private AmfNumber serverId;
    private int videoWidth;
    private int videoHeight;

    public RtmpConnection(RtmpPublisher.EventHandler handler) {
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
        handshake.readS2(in);
    }

    @Override
    public void connect(String url) throws IOException {
        int port;
        String host;
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
            throw new IllegalArgumentException("Invalid RTMP URL. Must be in format: rtmp://host[:port]/application[/streamName]");
        }

        // socket connection
        Log.d(TAG, "connect() called. Host: " + host + ", port: " + port + ", appName: " + appName + ", publishPath: " + streamName);
        socket = new Socket();
        SocketAddress socketAddress = new InetSocketAddress(host, port);
        socket.connect(socketAddress, 3000);
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
        Log.d(TAG, "connect(): socket connection established, doing handhake...");
        handshake(in, out);
        active = true;
        Log.d(TAG, "connect(): handshake done");
        rtmpSessionInfo = new RtmpSessionInfo();
        readThread = new ReadThread(rtmpSessionInfo, in, this);
        writeThread = new WriteThread(rtmpSessionInfo, out, this);
        readThread.start();
        writeThread.start();

        // Start the "main" handling thread
        new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    Log.d(TAG, "starting main rx handler loop");
                    handleRxPacketLoop();
                } catch (IOException ex) {
                    Logger.getLogger(RtmpConnection.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }).start();

        rtmpConnect();
    }

    private void rtmpConnect() throws IOException, IllegalStateException {
        if (fullyConnected || connecting) {
            throw new IllegalStateException("Already connected or connecting to RTMP server");
        }

        // Mark session timestamp of all chunk stream information on connection.
        ChunkStreamInfo.markSessionTimestampTx();

        Log.d(TAG, "rtmpConnect(): Building 'connect' invoke packet");
        ChunkStreamInfo chunkStreamInfo = rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL);
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
        writeThread.send(invoke);

        connecting = true;
        mHandler.onRtmpConnecting("connecting");
    }

    @Override
    public void publish(String type) throws IllegalStateException, IOException {
        if (connecting) {
            synchronized (connectingLock) {
                try {
                    connectingLock.wait(5000);
                } catch (InterruptedException ex) {
                    // do nothing
                }
            }
        }

        publishType = type;
        createStream();
    }

    private void createStream() {
        if (!fullyConnected) {
            throw new IllegalStateException("Not connected to RTMP server");
        }
        if (currentStreamId != -1) {
            throw new IllegalStateException("Current stream object has existed");
        }

        Log.d(TAG, "createStream(): Sending releaseStream command...");
        // transactionId == 2
        Command releaseStream = new Command("releaseStream", ++transactionIdCounter);
        releaseStream.getHeader().setChunkStreamId(ChunkStreamInfo.RTMP_STREAM_CHANNEL);
        releaseStream.addData(new AmfNull());  // command object: null for "createStream"
        releaseStream.addData(streamName);  // command object: null for "releaseStream"
        writeThread.send(releaseStream);

        Log.d(TAG, "createStream(): Sending FCPublish command...");
        // transactionId == 3
        Command FCPublish = new Command("FCPublish", ++transactionIdCounter);
        FCPublish.getHeader().setChunkStreamId(ChunkStreamInfo.RTMP_STREAM_CHANNEL);
        FCPublish.addData(new AmfNull());  // command object: null for "FCPublish"
        FCPublish.addData(streamName);
        writeThread.send(FCPublish);

        Log.d(TAG, "createStream(): Sending createStream command...");
        ChunkStreamInfo chunkStreamInfo = rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL);
        // transactionId == 4
        Command createStream = new Command("createStream", ++transactionIdCounter, chunkStreamInfo);
        createStream.addData(new AmfNull());  // command object: null for "createStream"
        writeThread.send(createStream);

        // Waiting for "NetStream.Publish.Start" response.
        synchronized (publishLock) {
            try {
                publishLock.wait(5000);
            } catch (InterruptedException ex) {
                // do nothing
            }
        }
    }

    private void fmlePublish() throws IllegalStateException {
        if (!fullyConnected) {
            throw new IllegalStateException("Not connected to RTMP server");
        }
        if (currentStreamId == -1) {
            throw new IllegalStateException("No current stream object exists");
        }

        Log.d(TAG, "fmlePublish(): Sending publish command...");
        // transactionId == 0
        Command publish = new Command("publish", 0);
        publish.getHeader().setChunkStreamId(ChunkStreamInfo.RTMP_STREAM_CHANNEL);
        publish.getHeader().setMessageStreamId(currentStreamId);
        publish.addData(new AmfNull());  // command object: null for "publish"
        publish.addData(streamName);
        publish.addData(publishType);
        writeThread.send(publish);
    }

    private void onMetaData() throws IllegalStateException {
        if (!fullyConnected) {
            throw new IllegalStateException("Not connected to RTMP server");
        }
        if (currentStreamId == -1) {
            throw new IllegalStateException("No current stream object exists");
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
        writeThread.send(metadata);
    }

    @Override
    public void closeStream() throws IllegalStateException {
        if (!fullyConnected) {
            throw new IllegalStateException("Not connected to RTMP server");
        }
        if (currentStreamId == -1) {
            throw new IllegalStateException("No current stream object exists");
        }
        if (!publishPermitted) {
            throw new IllegalStateException("Not get the _result(Netstream.Publish.Start)");
        }
        Log.d(TAG, "closeStream(): setting current stream ID to -1");
        Command closeStream = new Command("closeStream", 0);
        closeStream.getHeader().setChunkStreamId(ChunkStreamInfo.RTMP_STREAM_CHANNEL);
        closeStream.getHeader().setMessageStreamId(currentStreamId);
        closeStream.addData(new AmfNull());
        writeThread.send(closeStream);
        mHandler.onRtmpStopped("stopped");
    }

    @Override
    public void shutdown() {
        if (active) {
            readThread.shutdown();
            writeThread.shutdown();

            try {
                // It will invoke EOFException in read thread
                socket.shutdownInput();
                // It will invoke SocketException in write thread
                socket.shutdownOutput();
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }

            try {
                readThread.join();
            } catch (InterruptedException ie) {
                ie.printStackTrace();
                readThread.interrupt();
            }

            try {
                writeThread.join();
            } catch (InterruptedException ie) {
                ie.printStackTrace();
                writeThread.interrupt();
            }

            // shutdown handleRxPacketLoop
            rxPacketQueue.clear();
            active = false;
            synchronized (rxPacketLock) {
                rxPacketLock.notify();
            }

            // shutdown socket as well as its input and output stream
            if (socket != null) {
                try {
                    socket.close();
                    Log.d(TAG, "socket closed");
                } catch (IOException ex) {
                    Log.e(TAG, "shutdown(): failed to close socket", ex);
                }
            }

            mHandler.onRtmpDisconnected("disconnected");
        }

        reset();
    }

    private void reset() {
        active = false;
        connecting = false;
        fullyConnected = false;
        publishPermitted = false;
        tcUrl = null;
        swfUrl = null;            
        pageUrl = null;            
        appName = null;
        streamName = null;
        publishType = null;
        currentStreamId = -1;
        transactionIdCounter = 0;
        videoFrameCacheNumber.set(0);
        serverIpAddr = null;
        serverPid = null;
        serverId = null;
        rtmpSessionInfo = null;
    }

    @Override
    public void notifyWindowAckRequired(final int numBytesReadThusFar) {
        Log.i(TAG, "notifyWindowAckRequired() called");
        // Create and send window bytes read acknowledgement
        writeThread.send(new Acknowledgement(numBytesReadThusFar));
    }

    @Override
    public void publishAudioData(byte[] data) throws IllegalStateException {
        if (!fullyConnected) {
            throw new IllegalStateException("Not connected to RTMP server");
        }
        if (currentStreamId == -1) {
            throw new IllegalStateException("No current stream object exists");
        }
        if (!publishPermitted) {
            throw new IllegalStateException("Not get the _result(Netstream.Publish.Start)");
        }
        Audio audio = new Audio();
        audio.setData(data);
        audio.getHeader().setMessageStreamId(currentStreamId);
        writeThread.send(audio);
        mHandler.onRtmpAudioStreaming("audio streaming");
    }

    @Override
    public void publishVideoData(byte[] data) throws IllegalStateException {
        if (!fullyConnected) {
            throw new IllegalStateException("Not connected to RTMP server");
        }
        if (currentStreamId == -1) {
            throw new IllegalStateException("No current stream object exists");
        }
        if (!publishPermitted) {
            throw new IllegalStateException("Not get the _result(Netstream.Publish.Start)");
        }
        Video video = new Video();
        video.setData(data);
        video.getHeader().setMessageStreamId(currentStreamId);
        writeThread.send(video);
        videoFrameCacheNumber.getAndIncrement();
        mHandler.onRtmpVideoStreaming("video streaming");
    }

    @Override
    public void handleRxPacket(RtmpPacket rtmpPacket) {
        if (rtmpPacket != null) {
            rxPacketQueue.add(rtmpPacket);
        }
        synchronized (rxPacketLock) {
            rxPacketLock.notify();
        }
    }

    private void handleRxPacketLoop() throws IOException {
        // Handle all queued received RTMP packets
        while (active) {
            while (!rxPacketQueue.isEmpty()) {
                RtmpPacket rtmpPacket = rxPacketQueue.poll();
                //Log.d(TAG, "handleRxPacketLoop(): RTMP rx packet message type: " + rtmpPacket.getHeader().getMessageType());
                switch (rtmpPacket.getHeader().getMessageType()) {
                    case ABORT:
                        rtmpSessionInfo.getChunkStreamInfo(((Abort) rtmpPacket).getChunkStreamId()).clearStoredChunks();
                        break;
                    case USER_CONTROL_MESSAGE:
                        UserControl ping = (UserControl) rtmpPacket;
                        switch (ping.getType()) {
                            case PING_REQUEST:
                                ChunkStreamInfo channelInfo = rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_CONTROL_CHANNEL);
                                Log.d(TAG, "handleRxPacketLoop(): Sending PONG reply..");
                                UserControl pong = new UserControl(ping, channelInfo);
                                writeThread.send(pong);
                                break;
                            case STREAM_EOF:
                                Log.i(TAG, "handleRxPacketLoop(): Stream EOF reached, closing RTMP writer...");
                                break;
                        }
                        break;
                    case WINDOW_ACKNOWLEDGEMENT_SIZE:
                        WindowAckSize windowAckSize = (WindowAckSize) rtmpPacket;
                        int size = windowAckSize.getAcknowledgementWindowSize();
                        Log.d(TAG, "handleRxPacketLoop(): Setting acknowledgement window size: " + size);
                        rtmpSessionInfo.setAcknowledgmentWindowSize(size);
                        // Set socket option
                        socket.setSendBufferSize(size);
                        break;
                    case SET_PEER_BANDWIDTH:
                        int acknowledgementWindowsize = rtmpSessionInfo.getAcknowledgementWindowSize();
                        final ChunkStreamInfo chunkStreamInfo = rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_CONTROL_CHANNEL);
                        Log.d(TAG, "handleRxPacketLoop(): Send acknowledgement window size: " + acknowledgementWindowsize);
                        writeThread.send(new WindowAckSize(acknowledgementWindowsize, chunkStreamInfo));
                        break;
                    case COMMAND_AMF0:
                        handleRxInvoke((Command) rtmpPacket);
                        break;
                    default:
                        Log.w(TAG, "handleRxPacketLoop(): Not handling unimplemented/unknown packet of type: " + rtmpPacket.getHeader().getMessageType());
                        break;
                }
            }
            // Wait for next received packet
            synchronized (rxPacketLock) {
                try {
                    rxPacketLock.wait(500);
                } catch (InterruptedException ex) {
                    Log.w(TAG, "handleRxPacketLoop: Interrupted", ex);
                }
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
                String serverInfo = onSrsServerInfo(invoke);
                mHandler.onRtmpConnected("connected" + serverInfo);
                // We can now send createStream commands
                connecting = false;
                fullyConnected = true;
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
    public EventHandler getEventHandler() {
        return mHandler;
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
