package net.ossrs.sea.rtmp.io;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
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
    private Handler handler;
    private ThreadController threadController;
    private Thread t;

    public WriteThread(RtmpSessionInfo rtmpSessionInfo, OutputStream out, ThreadController threadController) {
        super("RtmpWriteThread");
        this.rtmpSessionInfo = rtmpSessionInfo;
        this.out = out;
        this.threadController = threadController;
    }

    @Override
    public void run() {
        t = this;
        Looper.prepare();
        handler = new Handler(Looper.myLooper()) {
            @Override
            public void handleMessage(Message msg) {
                try {
                    RtmpPacket rtmpPacket = (RtmpPacket) msg.obj;
                    ChunkStreamInfo chunkStreamInfo = rtmpSessionInfo.getChunkStreamInfo(rtmpPacket.getHeader().getChunkStreamId());
                    chunkStreamInfo.setPrevHeaderTx(rtmpPacket.getHeader());
                    rtmpPacket.writeTo(out, rtmpSessionInfo.getTxChunkSize(), chunkStreamInfo);
                    out.flush();
                    Log.d(TAG, "WriteThread: wrote packet: " + rtmpPacket + ", size: " + rtmpPacket.getHeader().getPacketLength());
                    if (rtmpPacket instanceof Command) {
                        rtmpSessionInfo.addInvokedCommand(((Command) rtmpPacket).getTransactionId(), ((Command) rtmpPacket).getCommandName());
                    }
                } catch (SocketException se) {
                    Log.e(TAG, "WriteThread: Caught SocketException during write loop, shutting down", se);
                    Thread.getDefaultUncaughtExceptionHandler().uncaughtException(t, se);
                } catch (IOException ex) {
                    Log.e(TAG, "WriteThread: Caught IOException during write loop, shutting down", ex);
                    Thread.getDefaultUncaughtExceptionHandler().uncaughtException(t, ex);
                }
            }
        };
        Looper.loop();

        // Close outputstream
        try {
            out.close();
        } catch (Exception ex) {
            Log.w(TAG, "WriteThread: Failed to close outputstream", ex);
        }
        Log.d(TAG, "exiting");
        if (threadController != null) {
            threadController.threadHasExited(this);
        }
    }

    /** Transmit the specified RTMP packet (thread-safe) */
    public void send(RtmpPacket rtmpPacket) {
        if (rtmpPacket != null) {
            Message msg = Message.obtain();
            msg.obj = rtmpPacket;
            handler.sendMessage(msg);
        }
    }

    public void shutdown() {
        Log.d(TAG, "Stopping write thread...");
        Looper.myLooper().quit();
    }
}
