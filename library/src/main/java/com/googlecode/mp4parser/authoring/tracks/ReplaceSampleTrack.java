/*
 * Copyright 2012 Sebastian Annies, Hamburg
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
package com.googlecode.mp4parser.authoring.tracks;

import com.coremedia.iso.boxes.*;
import com.googlecode.mp4parser.authoring.AbstractTrack;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.TrackMetaData;

import java.nio.ByteBuffer;
import java.util.AbstractList;
import java.util.LinkedList;
import java.util.List;

/**
 * Generates a Track where a single sample has been replaced by a given <code>ByteBuffer</code>.
 */

public class ReplaceSampleTrack extends AbstractTrack {
    Track origTrack;
    private long sampleNumber;
    private ByteBuffer sampleContent;
    private List<ByteBuffer>  samples;

    public ReplaceSampleTrack(Track origTrack, long sampleNumber, ByteBuffer content) {
        this.origTrack = origTrack;
        this.sampleNumber = sampleNumber;
        this.sampleContent = content;
        this.samples = new ReplaceASingleEntryList();

    }

    public List<ByteBuffer> getSamples() {
        return samples;
    }

    public SampleDescriptionBox getSampleDescriptionBox() {
        return origTrack.getSampleDescriptionBox();
    }

    public List<TimeToSampleBox.Entry> getDecodingTimeEntries() {
        return origTrack.getDecodingTimeEntries();

    }

    public List<CompositionTimeToSample.Entry> getCompositionTimeEntries() {
        return origTrack.getCompositionTimeEntries();

    }

    synchronized public long[] getSyncSamples() {
        return origTrack.getSyncSamples();
    }

    public List<SampleDependencyTypeBox.Entry> getSampleDependencies() {
        return origTrack.getSampleDependencies();
    }

    public TrackMetaData getTrackMetaData() {
        return origTrack.getTrackMetaData();
    }

    public String getHandler() {
        return origTrack.getHandler();
    }

    public Box getMediaHeaderBox() {
        return origTrack.getMediaHeaderBox();
    }

    public SubSampleInformationBox getSubsampleInformationBox() {
        return origTrack.getSubsampleInformationBox();
    }

    private class ReplaceASingleEntryList extends AbstractList<ByteBuffer> {
        @Override
        public ByteBuffer get(int index) {
            if (ReplaceSampleTrack.this.sampleNumber == index) {
                return ReplaceSampleTrack.this.sampleContent;
            } else {
                return ReplaceSampleTrack.this.origTrack.getSamples().get(index);
            }
        }

        @Override
        public int size() {
            return ReplaceSampleTrack.this.origTrack.getSamples().size();
        }
    }

}
