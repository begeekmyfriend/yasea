/*
 * Copyright 2011 castLabs, Berlin
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

package com.googlecode.mp4parser.boxes.mp4.objectdescriptors;

import com.coremedia.iso.Hex;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * abstract class DecoderSpecificInfo extends BaseDescriptor : bit(8)
 * tag=DecSpecificInfoTag
 * {
 * // empty. To be filled by classes extending this class.
 * }
 */
@Descriptor(tags = 0x05)
public class DecoderSpecificInfo extends BaseDescriptor {
    byte[] bytes;

    @Override
    public void parseDetail(ByteBuffer bb) throws IOException {
        if (sizeOfInstance > 0) {
            bytes = new byte[sizeOfInstance];
            bb.get(bytes);
        }
    }

    public int serializedSize() {
        return bytes.length;
    }

    public ByteBuffer serialize() {
        ByteBuffer out = ByteBuffer.wrap(bytes);

        return out;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("DecoderSpecificInfo");
        sb.append("{bytes=").append(bytes == null ? "null" : Hex.encodeHex(bytes));
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DecoderSpecificInfo that = (DecoderSpecificInfo) o;

        if (!Arrays.equals(bytes, that.bytes)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        return bytes != null ? Arrays.hashCode(bytes) : 0;
    }
}
