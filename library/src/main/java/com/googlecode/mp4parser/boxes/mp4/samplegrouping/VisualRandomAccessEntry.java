/*
 * Copyright 2012 castLabs, Berlin
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

package com.googlecode.mp4parser.boxes.mp4.samplegrouping;

import com.coremedia.iso.IsoTypeReader;

import java.nio.ByteBuffer;

/**
 * For some coding systems a sync sample is specified to be a random access point after which all samples in decoding order can be correctly decoded. However, it may be possible to encode an “open” random access point, after which all samples in output order can be correctly decoded, but some samples following the random access point in decoding order and preceding the random access point in output order need not be correctly decodable. For example, an intra picture starting an open group of pictures can be followed in decoding order by (bi-)predicted pictures that however precede the intra picture in output order; though they possibly cannot be correctly decoded if the decoding starts from the intra picture, they are not needed.
 *
 * Such “open” random-access samples can be marked by being a member of this group. Samples marked by this group must be random access points, and may also be sync points (i.e. it is not required that samples marked by the sync sample table be excluded).
 *
 */
public class VisualRandomAccessEntry extends GroupEntry {
    public static final String TYPE = "rap ";
    private boolean numLeadingSamplesKnown;
    private short numLeadingSamples;

    public boolean isNumLeadingSamplesKnown() {
        return numLeadingSamplesKnown;
    }

    public void setNumLeadingSamplesKnown(boolean numLeadingSamplesKnown) {
        this.numLeadingSamplesKnown = numLeadingSamplesKnown;
    }

    public short getNumLeadingSamples() {
        return numLeadingSamples;
    }

    public void setNumLeadingSamples(short numLeadingSamples) {
        this.numLeadingSamples = numLeadingSamples;
    }

    @Override
    public void parse(ByteBuffer byteBuffer) {
        final byte b = byteBuffer.get();
        numLeadingSamplesKnown = ((b & 0x80) == 0x80);
        numLeadingSamples = (short) (b & 0x7f);
    }

    @Override
    public ByteBuffer get() {
        ByteBuffer content = ByteBuffer.allocate(1);
        content.put((byte) ((numLeadingSamplesKnown? 0x80 : 0x00)| (numLeadingSamples & 0x7f)));
        content.rewind();
        return content;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        VisualRandomAccessEntry that = (VisualRandomAccessEntry) o;

        if (numLeadingSamples != that.numLeadingSamples) return false;
        if (numLeadingSamplesKnown != that.numLeadingSamplesKnown) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (numLeadingSamplesKnown ? 1 : 0);
        result = 31 * result + (int) numLeadingSamples;
        return result;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("VisualRandomAccessEntry");
        sb.append("{numLeadingSamplesKnown=").append(numLeadingSamplesKnown);
        sb.append(", numLeadingSamples=").append(numLeadingSamples);
        sb.append('}');
        return sb.toString();
    }
}
