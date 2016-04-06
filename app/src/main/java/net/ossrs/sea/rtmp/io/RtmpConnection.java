package net.ossrs.sea.rtmp.io;

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
import android.util.Log;
import net.ossrs.sea.rtmp.RtmpPublisher;
import net.ossrs.sea.rtmp.amf.AmfNull;
import net.ossrs.sea.rtmp.amf.AmfNumber;
import net.ossrs.sea.rtmp.amf.AmfObject;
import net.ossrs.sea.rtmp.packets.Abort;
import net.ossrs.sea.rtmp.packets.Acknowledgement;
import net.ossrs.sea.rtmp.packets.Handshake;
import net.ossrs.sea.rtmp.packets.Command;
import net.ossrs.sea.rtmp.packets.Audio;
import net.ossrs.sea.rtmp.packets.Video;
import net.ossrs.sea.rtmp.packets.UserControl;
import net.ossrs.sea.rtmp.packets.RtmpPacket;
import net.ossrs.sea.rtmp.packets.WindowAckSize;

/**
 * Main RTMP connection implementation class
 * 
 * @author francois, leoma
 */
public class RtmpConnection implements RtmpPublisher, PacketRxHandler, ThreadController {

    private static final String TAG = "RtmpConnection";
    private static final Pattern rtmpUrlPattern = Pattern.compile("^rtmp://([^/:]+)(:(\\d+))*/([^/]+)(/(.*))*$");
    
    private String appName;
    private String host;
    private String streamName;
    private String publishType;
    private String swfUrl = "";
    private String tcUrl = "";
    private String pageUrl = "";
    private int port;
    private Socket socket;
    private RtmpSessionInfo rtmpSessionInfo;
    private int transactionIdCounter = 0;
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 3000;
    private WriteThread writeThread;
    private final ConcurrentLinkedQueue<RtmpPacket> rxPacketQueue;
    private final Object rxPacketLock = new Object();
    private boolean active = false;
    private volatile boolean fullyConnected = false;
    private final Object connectingLock = new Object();
    private final Object publishLock = new Object();
    private volatile boolean connecting = false;
    private int currentStreamId = -1;

    public RtmpConnection(String url) {
        this.tcUrl = url.substring(0, url.lastIndexOf('/'));
        Matcher matcher = rtmpUrlPattern.matcher(url);
        if (matcher.matches()) {
            this.host = matcher.group(1);
            String portStr = matcher.group(3);
            this.port = portStr != null ? Integer.parseInt(portStr) : 1935;            
            this.appName = matcher.group(4);
            this.streamName = matcher.group(6);
            rtmpSessionInfo = new RtmpSessionInfo();
            rxPacketQueue = new ConcurrentLinkedQueue<RtmpPacket>();
        } else {
            throw new RuntimeException("Invalid RTMP URL. Must be in format: rtmp://host[:port]/application[/streamName]");
        }
    }

    @Override
    public void connect() throws IOException {
        Log.d(TAG, "connect() called. Host: " + host + ", port: " + port + ", appName: " + appName + ", publishPath: " + streamName);
        socket = new Socket();
        SocketAddress socketAddress = new InetSocketAddress(host, port);
        socket.connect(socketAddress, SOCKET_CONNECT_TIMEOUT_MS);
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
        Log.d(TAG, "connect(): socket connection established, doing handhake...");
        handshake(in, out);
        active = true;
        Log.d(TAG, "connect(): handshake done");
        ReadThread readThread = new ReadThread(rtmpSessionInfo, in, this, this);
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

    @Override
    public void publish(String type) throws IllegalStateException, IOException {
        if (connecting) {
            synchronized (connectingLock) {
                try {
                    connectingLock.wait();
                } catch (InterruptedException ex) {
                    // do nothing
                }
            }
        }

        this.publishType = type;
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
        final ChunkStreamInfo chunkStreamInfo = rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL);
        // transactionId == 4
        Command createStream = new Command("createStream", ++transactionIdCounter, chunkStreamInfo);
        createStream.addData(new AmfNull());  // command object: null for "createStream"
        writeThread.send(createStream);

        // Waiting for "publish" command response.
        synchronized (publishLock) {
            try {
                publishLock.wait();
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

    @Override
    public void closeStream() throws IllegalStateException {
        if (!fullyConnected) {
            throw new IllegalStateException("Not connected to RTMP server");
        }
        if (currentStreamId == -1) {
            throw new IllegalStateException("No current stream object exists");
        }
        streamName = null;
        Log.d(TAG, "closeStream(): setting current stream ID to -1");
        currentStreamId = -1;
        Command closeStream = new Command("closeStream", 0);
        closeStream.getHeader().setChunkStreamId(ChunkStreamInfo.RTMP_STREAM_CHANNEL);
        closeStream.getHeader().setMessageStreamId(currentStreamId);
        closeStream.addData(new AmfNull());  // command object: null for "closeStream"
        writeThread.send(closeStream);
    }

    /**
     * Performs the RTMP handshake sequence with the server 
     */
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

    private void rtmpConnect() throws IOException, IllegalStateException {
        if (fullyConnected || connecting) {
            throw new IllegalStateException("Already connecting, or connected to RTMP server");
        }
        Log.d(TAG, "rtmpConnect(): Building 'connect' invoke packet");
        Command invoke = new Command("connect", ++transactionIdCounter, rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL));
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

        connecting = true;

        Log.d(TAG, "rtmpConnect(): Writing 'connect' invoke packet");
        invoke.getHeader().setAbsoluteTimestamp(0);
        writeThread.send(invoke);
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
                    case USER_CONTROL_MESSAGE: {
                        UserControl ping = (UserControl) rtmpPacket;
                        switch (ping.getType()) {
                            case PING_REQUEST: {
                                ChunkStreamInfo channelInfo = rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_CONTROL_CHANNEL);
                                Log.d(TAG, "handleRxPacketLoop(): Sending PONG reply..");
                                UserControl pong = new UserControl(ping, channelInfo);
                                writeThread.send(pong);
                                break;
                            }
                            case STREAM_EOF:
                                Log.i(TAG, "handleRxPacketLoop(): Stream EOF reached, closing RTMP writer...");
                                break;
                        }
                        break;
                    }
                    case WINDOW_ACKNOWLEDGEMENT_SIZE:
                        WindowAckSize windowAckSize = (WindowAckSize) rtmpPacket;
                        Log.d(TAG, "handleRxPacketLoop(): Setting acknowledgement window size to: " + windowAckSize.getAcknowledgementWindowSize());
                        rtmpSessionInfo.setAcknowledgmentWindowSize(windowAckSize.getAcknowledgementWindowSize());
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
                    rxPacketLock.wait();
                } catch (InterruptedException ex) {
                    Log.w(TAG, "handleRxPacketLoop: Interrupted", ex);
                }
            }
        }

        shutdownImpl();
    }

    private void handleRxInvoke(Command invoke) throws IOException {
        String commandName = invoke.getCommandName();

        if (commandName.equals("_result")) {
            // This is the result of one of the methods invoked by us
            String method = rtmpSessionInfo.takeInvokedCommand(invoke.getTransactionId());

            Log.d(TAG, "handleRxInvoke: Got result for invoked method: " + method);
            if ("connect".equals(method)) {
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
                // Do nothing
            } else if ("FCPublish".contains(method)) {
                // Do nothing
            } else {
                Log.w(TAG, "handleRxInvoke(): '_result' message received for unknown method: " + method);
            }
        } else if (commandName.equals("onBWDone")) {
            // Do nothing
        } else if (commandName.equals("onFCPublish")) {
            Log.d(TAG, "handleRxInvoke(): 'onFCPublish'");
            synchronized (publishLock) {
                publishLock.notifyAll();
            }
        } else if (commandName.equals("onStatus")) {
            // Do nothing
        } else {
            Log.e(TAG, "handleRxInvoke(): Uknown/unhandled server invoke: " + invoke);
        }
    }

    @Override
    public void threadHasExited(Thread thread) {
        shutdown();
    }

    @Override
    public void shutdown() {
        active = false;
        synchronized (rxPacketLock) {
            rxPacketLock.notify();
        }
    }

    private void shutdownImpl() {
        // Shut down read/write threads, if necessary
        if (Thread.activeCount() > 1) {
            Log.i(TAG, "shutdown(): Shutting down read/write threads");
            Thread[] threads = new Thread[Thread.activeCount()];
            Thread.enumerate(threads);
            for (Thread thread : threads) {
                if (thread instanceof ReadThread && thread.isAlive()) {
                    ((ReadThread) thread).shutdown();
                } else if (thread instanceof WriteThread && thread.isAlive()) {
                    ((WriteThread) thread).shutdown();
                }
            }
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ex) {
                Log.w(TAG, "shutdown(): failed to close socket", ex);
            }
        }
    }

    @Override
    public void notifyWindowAckRequired(final int numBytesReadThusFar) {
        Log.i(TAG, "notifyWindowAckRequired() called");
        // Create and send window bytes read acknowledgement        
        writeThread.send(new Acknowledgement(numBytesReadThusFar));
    }

    @Override
    public void publishVideoData(byte[] data, int dts) throws IllegalStateException {
        if (!fullyConnected) {
            throw new IllegalStateException("Not connected to RTMP server");
        }
        if (currentStreamId == -1) {
            throw new IllegalStateException("No current stream object exists");
        }
        Video video = new Video();
        video.setData(data);
        video.getHeader().setMessageStreamId(currentStreamId);
        video.getHeader().setAbsoluteTimestamp(dts);
        writeThread.send(video);
    }

    @Override
    public void publishAudioData(byte[] data, int dts) throws IllegalStateException {
        if (!fullyConnected) {
            throw new IllegalStateException("Not connected to RTMP server");
        }
        if (currentStreamId == -1) {
            throw new IllegalStateException("No current stream object exists");
        }
        Audio audio = new Audio();
        audio.setData(data);
        audio.getHeader().setMessageStreamId(currentStreamId);
        audio.getHeader().setAbsoluteTimestamp(dts);
        writeThread.send(audio);
    }
}
