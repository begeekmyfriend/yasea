package com.googlecode.mp4parser.authoring.tracks;

import com.coremedia.iso.boxes.*;
import com.googlecode.mp4parser.authoring.Mp4TrackImpl;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.TrackMetaData;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * This is just a basic idea how things could work but they don't.
 */
public class SilenceTrackImpl implements Track {
    Track source;

    List<ByteBuffer> samples = new LinkedList<ByteBuffer>();
    TimeToSampleBox.Entry entry;

    public SilenceTrackImpl(Track ofType, long ms) {
        source = ofType;
        if ("mp4a".equals(ofType.getSampleDescriptionBox().getSampleEntry().getType())) {
            long numFrames = getTrackMetaData().getTimescale() * ms / 1000 / 1024;
            long standZeit = getTrackMetaData().getTimescale() * ms / numFrames / 1000;
            entry = new TimeToSampleBox.Entry(numFrames, standZeit);


            while (numFrames-- > 0) {
                samples.add((ByteBuffer) ByteBuffer.wrap(new byte[]{
                        0x21, 0x10, 0x04, 0x60, (byte) 0x8c, 0x1c,
                }).rewind());
            }

        } else {
            throw new RuntimeException("Tracks of type " + ofType.getClass().getSimpleName() + " are not supported");
        }
    }

    public SampleDescriptionBox getSampleDescriptionBox() {
        return source.getSampleDescriptionBox();
    }

    public List<TimeToSampleBox.Entry> getDecodingTimeEntries() {
        return Collections.singletonList(entry);

    }

    public TrackMetaData getTrackMetaData() {
        return source.getTrackMetaData();
    }

    public String getHandler() {
        return source.getHandler();
    }

    public boolean isEnabled() {
        return source.isEnabled();
    }

    public boolean isInMovie() {
        return source.isInMovie();
    }

    public boolean isInPreview() {
        return source.isInPreview();
    }

    public boolean isInPoster() {
        return source.isInPoster();
    }

    public List<ByteBuffer> getSamples() {
        return samples;
    }

    public Box getMediaHeaderBox() {
        return source.getMediaHeaderBox();
    }

    public SubSampleInformationBox getSubsampleInformationBox() {
        return null;
    }

    public List<CompositionTimeToSample.Entry> getCompositionTimeEntries() {
        return null;
    }

    public long[] getSyncSamples() {
        return null;
    }

    public List<SampleDependencyTypeBox.Entry> getSampleDependencies() {
        return null;
    }

}
