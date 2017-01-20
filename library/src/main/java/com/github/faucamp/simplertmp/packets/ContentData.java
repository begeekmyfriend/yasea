package com.github.faucamp.simplertmp.packets;

import android.support.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.github.faucamp.simplertmp.Util;

/**
 * Content (audio/video) data packet base
 *  
 * @author francois
 */
public abstract class ContentData extends RtmpPacket {

    protected byte[] data;
    protected int size;

    public ContentData(RtmpHeader header) {
        super(header);
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data, int size) {
        this.data = data;
        this.size = size;
    }

    @Override
    public void readBody(InputStream in) throws IOException {
        data = new byte[this.header.getPacketLength()];
        Util.readBytesUntilFull(in, data);
    }

    /**
     * Method is public for content (audio/video)
     * Write this packet body without chunking;
     * useful for dumping audio/video streams
     */
    @Override
    public void writeBody(OutputStream out) throws IOException {
    }

    @Override
    public byte[] array() {
        return data;
    }

    @Override
    public int size() {
        return size;
    }
}
