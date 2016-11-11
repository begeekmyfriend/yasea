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
 * roll_distance is a signed integer that gives the number of samples that must be decoded in order for
 * a sample to be decoded correctly. A positive value indicates the number of samples after the sample
 * that is a group member that must be decoded such that at the last of these recovery is complete, i.e.
 * the last sample is correct. A negative value indicates the number of samples before the sample that is
 * a group member that must be decoded in order for recovery to be complete at the marked sample.
 * The value zero must not be used; the sync sample table documents random access points for which
 * no recovery roll is needed.
 */
public class RollRecoveryEntry extends GroupEntry {
    public static final String TYPE = "roll";
    private short rollDistance;

    public short getRollDistance() {
        return rollDistance;
    }

    public void setRollDistance(short rollDistance) {
        this.rollDistance = rollDistance;
    }

    @Override
    public void parse(ByteBuffer byteBuffer) {
        rollDistance = byteBuffer.getShort();
    }

    @Override
    public ByteBuffer get() {
        ByteBuffer content = ByteBuffer.allocate(2);
        content.putShort(rollDistance);
        content.rewind();
        return content;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        RollRecoveryEntry entry = (RollRecoveryEntry) o;

        if (rollDistance != entry.rollDistance) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        return (int) rollDistance;
    }
}
