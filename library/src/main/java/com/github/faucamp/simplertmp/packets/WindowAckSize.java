package com.github.faucamp.simplertmp.packets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.github.faucamp.simplertmp.Util;
import com.github.faucamp.simplertmp.io.ChunkStreamInfo;

/**
 * Window Acknowledgement Size
 * 
 * Also known as ServerBW ("Server bandwidth") in some RTMP implementations.
 * 
 * @author francois
 */
public class WindowAckSize extends RtmpPacket {

    private int acknowledgementWindowSize;

    public WindowAckSize(RtmpHeader header) {
        super(header);
    }
    
    public WindowAckSize(int acknowledgementWindowSize, ChunkStreamInfo channelInfo) {
        super(new RtmpHeader(channelInfo.canReusePrevHeaderTx(RtmpHeader.MessageType.WINDOW_ACKNOWLEDGEMENT_SIZE) ? RtmpHeader.ChunkType.TYPE_2_RELATIVE_TIMESTAMP_ONLY : RtmpHeader.ChunkType.TYPE_0_FULL, ChunkStreamInfo.RTMP_CID_PROTOCOL_CONTROL, RtmpHeader.MessageType.WINDOW_ACKNOWLEDGEMENT_SIZE));
        this.acknowledgementWindowSize = acknowledgementWindowSize;
    }


    public int getAcknowledgementWindowSize() {
        return acknowledgementWindowSize;
    }

    public void setAcknowledgementWindowSize(int acknowledgementWindowSize) {
        this.acknowledgementWindowSize = acknowledgementWindowSize;
    }

    @Override
    public void readBody(InputStream in) throws IOException {
        acknowledgementWindowSize = Util.readUnsignedInt32(in);
    }

    @Override
    protected void writeBody(OutputStream out) throws IOException {
        Util.writeUnsignedInt32(out, acknowledgementWindowSize);
    }

    @Override
    protected byte[] array() {
        return null;
    }

    @Override
    protected int size() {
        return 0;
    }

    @Override
    public String toString() {
        return "RTMP Window Acknowledgment Size";
    }
}
