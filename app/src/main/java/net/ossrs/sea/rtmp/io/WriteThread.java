package net.ossrs.sea.rtmp.io;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import android.util.Log;
import net.ossrs.sea.rtmp.packets.Command;
import net.ossrs.sea.rtmp.packets.RtmpPacket;

/**
 * RTMPConnection's write thread
 * 
 * @author francois, leo
 */
public class WriteThread extends Thread {

    private static final String TAG = "WriteThread";

    private RtmpSessionInfo rtmpSessionInfo;
    private OutputStream out;
    private ConcurrentLinkedQueue<RtmpPacket> writeQueue = new ConcurrentLinkedQueue<RtmpPacket>();
    private final Object txPacketLock = new Object();
    private volatile boolean active = true;
    private ThreadController threadController;

    public WriteThread(RtmpSessionInfo rtmpSessionInfo, OutputStream out, ThreadController threadController) {
        super("RtmpWriteThread");
        this.rtmpSessionInfo = rtmpSessionInfo;
        this.out = out;
        this.threadController = threadController;
    }

    @Override
    public void run() {

        while (active) {
            try {
                while (!writeQueue.isEmpty()) {
                    RtmpPacket rtmpPacket = writeQueue.poll();
                    final ChunkStreamInfo chunkStreamInfo = rtmpSessionInfo.getChunkStreamInfo(rtmpPacket.getHeader().getChunkStreamId());
                    chunkStreamInfo.setPrevHeaderTx(rtmpPacket.getHeader());
                    rtmpPacket.writeTo(out, rtmpSessionInfo.getChunkSize(), chunkStreamInfo);
                    Log.d(TAG, "WriteThread: wrote packet: " + rtmpPacket + ", size: " + rtmpPacket.getHeader().getPacketLength());
                    if (rtmpPacket instanceof Command) {
                        rtmpSessionInfo.addInvokedCommand(((Command) rtmpPacket).getTransactionId(), ((Command) rtmpPacket).getCommandName());
                    }
                }
                out.flush();
            } catch (SocketException se) {
                Log.e(TAG, "Caught SocketException during write loop, shutting down", se);
                active = false;
                continue;
            } catch (IOException ex) {
                Log.e(TAG, "Caught IOException during write loop, shutting down", ex);
                active = false;
                continue;  // Exit this thread
            }

            // Waiting for next packet
            synchronized (txPacketLock) {
                try {
                    txPacketLock.wait();
                } catch (InterruptedException ex) {
                    Log.w(TAG, "Interrupted", ex);
                }
            }
        }

        // Close outputstream
        try {
            out.close();
        } catch (Exception ex) {
            Log.w(TAG, "Failed to close outputstream", ex);
        }
        Log.d(TAG, "exiting");
        if (threadController != null) {
            threadController.threadHasExited(this);
        }
    }

    /** Transmit the specified RTMP packet (thread-safe) */
    public void send(RtmpPacket rtmpPacket) {
        if (rtmpPacket != null) {
            writeQueue.offer(rtmpPacket);
        }
        synchronized (txPacketLock) {
            txPacketLock.notify();
        }
    }
    
    /** Transmit the specified RTMP packet (thread-safe) */
    public void send(RtmpPacket... rtmpPackets) {
        writeQueue.addAll(Arrays.asList(rtmpPackets));
        synchronized (txPacketLock) {
            txPacketLock.notify();
        }
    }

    public void shutdown() {
        Log.d(TAG, "Stopping write thread...");
        active = false;
        synchronized (txPacketLock) {
            txPacketLock.notify();
        }
    }
}
