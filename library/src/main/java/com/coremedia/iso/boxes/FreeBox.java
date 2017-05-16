/*  
 * Copyright 2008 CoreMedia AG, Hamburg
 *
 * Licensed under the Apache License, Version 2.0 (the License); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0 
 * 
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an AS IS BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 */

package com.coremedia.iso.boxes;


import com.coremedia.iso.BoxParser;
import com.coremedia.iso.ChannelHelper;
import com.coremedia.iso.IsoTypeWriter;
import com.googlecode.mp4parser.AbstractBox;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.LinkedList;
import java.util.List;

import static com.googlecode.mp4parser.util.CastUtils.l2i;

/**
 * A free box. Just a placeholder to enable editing without rewriting the whole file.
 */
public class FreeBox implements Box {
    public static final String TYPE = "free";
    ByteBuffer data;
    List<Box> replacers = new LinkedList<Box>();
    private ContainerBox parent;

    public FreeBox() {
    }

    public FreeBox(int size) {
        this.data = ByteBuffer.allocate(size);
    }


    public ByteBuffer getData() {
        return data;
    }

    public void setData(ByteBuffer data) {
        this.data = data;
    }

    public void getBox(WritableByteChannel os) throws IOException {
        for (Box replacer : replacers) {
            replacer.getBox(os);
        }
        ByteBuffer header = ByteBuffer.allocate(8);
        IsoTypeWriter.writeUInt32(header, 8 + data.limit());
        header.put(TYPE.getBytes());
        header.rewind();
        os.write(header);
        data.rewind();
        os.write(data);

    }

    public ContainerBox getParent() {
        return parent;
    }

    public void setParent(ContainerBox parent) {
        this.parent = parent;
    }

    public long getSize() {
        long size = 8;
        for (Box replacer : replacers) {
            size += replacer.getSize();
        }
        size += data.limit();
        return size;
    }

    public String getType() {
        return TYPE;
    }

    public void parse(ReadableByteChannel readableByteChannel, ByteBuffer header, long contentSize, BoxParser boxParser) throws IOException {
        if (readableByteChannel instanceof FileChannel && contentSize > 1024 * 1024) {
            // It's quite expensive to map a file into the memory. Just do it when the box is larger than a MB.
            data = ((FileChannel) readableByteChannel).map(FileChannel.MapMode.READ_ONLY, ((FileChannel) readableByteChannel).position(), contentSize);
            ((FileChannel) readableByteChannel).position(((FileChannel) readableByteChannel).position() + contentSize);
        } else {
            assert contentSize < Integer.MAX_VALUE;
            data = ChannelHelper.readFully(readableByteChannel, contentSize);
        }
    }


    public void addAndReplace(Box box) {
        data.position(l2i(box.getSize()));
        data = data.slice();
        replacers.add(box);
    }


}