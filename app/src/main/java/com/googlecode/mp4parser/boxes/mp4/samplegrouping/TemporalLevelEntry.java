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

import java.nio.ByteBuffer;

/**
 * The Temporal Level sample grouping ('tele') provides a codec-independent sample grouping that can be used to group samples (access units) in a track (and potential track fragments) according to temporal level, where samples of one temporal level have no coding dependencies on samples of higher temporal levels. The temporal level equals the sample group description index (taking values 1, 2, 3, etc). The bitstream containing only the access units from the first temporal level to a higher temporal level remains conforming to the coding standard.
 *
 * A grouping according to temporal level facilitates easy extraction of temporal subsequences, for instance using the Subsegment Indexing box in 0.
 *
 */
public class TemporalLevelEntry extends GroupEntry {
    public static final String TYPE = "tele";
    private boolean levelIndependentlyDecodable;
    private short reserved;

    public boolean isLevelIndependentlyDecodable() {
        return levelIndependentlyDecodable;
    }

    public void setLevelIndependentlyDecodable(boolean levelIndependentlyDecodable) {
        this.levelIndependentlyDecodable = levelIndependentlyDecodable;
    }

    @Override
    public void parse(ByteBuffer byteBuffer) {
        final byte b = byteBuffer.get();
        levelIndependentlyDecodable = ((b & 0x80) == 0x80);
    }

    @Override
    public ByteBuffer get() {
        ByteBuffer content = ByteBuffer.allocate(1);
        content.put((byte) (levelIndependentlyDecodable ? 0x80 : 0x00));
        content.rewind();
        return content;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TemporalLevelEntry that = (TemporalLevelEntry) o;

        if (levelIndependentlyDecodable != that.levelIndependentlyDecodable) return false;
        if (reserved != that.reserved) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (levelIndependentlyDecodable ? 1 : 0);
        result = 31 * result + (int) reserved;
        return result;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("TemporalLevelEntry");
        sb.append("{levelIndependentlyDecodable=").append(levelIndependentlyDecodable);
        sb.append('}');
        return sb.toString();
    }
}
