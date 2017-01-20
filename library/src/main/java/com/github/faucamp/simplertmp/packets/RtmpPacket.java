package com.github.faucamp.simplertmp.packets;

import android.content.res.Configuration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.github.faucamp.simplertmp.io.ChunkStreamInfo;

/**
 *
 * @author francois, leo
 */
public abstract class RtmpPacket {
     
    protected RtmpHeader header;

    public RtmpPacket(RtmpHeader header) {
        this.header = header;
    }

    public RtmpHeader getHeader() {
        return header;
    }
    
    public abstract void readBody(InputStream in) throws IOException;    
    
    protected abstract void writeBody(OutputStream out) throws IOException;

    protected abstract byte[] array();

    protected abstract int size();

    public void writeTo(OutputStream out, final int chunkSize, final ChunkStreamInfo chunkStreamInfo) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeBody(baos);
        byte[] body = this instanceof ContentData ? array() : baos.toByteArray();
        int length = this instanceof ContentData ? size() : body.length;
        header.setPacketLength(length);
        // Write header for first chunk
        header.writeTo(out, RtmpHeader.ChunkType.TYPE_0_FULL, chunkStreamInfo);
        int pos = 0;
        while (length > chunkSize) {
            // Write packet for chunk
            out.write(body, pos, chunkSize);
            length -= chunkSize;
            pos += chunkSize;
            // Write header for remain chunk
            header.writeTo(out, RtmpHeader.ChunkType.TYPE_3_RELATIVE_SINGLE_BYTE, chunkStreamInfo);
        }
        out.write(body, pos, length);
    }
}
