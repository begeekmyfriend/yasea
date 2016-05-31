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
package com.googlecode.mp4parser.authoring;

import com.coremedia.iso.boxes.*;
import com.coremedia.iso.boxes.fragment.*;
import com.coremedia.iso.boxes.mdat.SampleList;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import static com.googlecode.mp4parser.util.CastUtils.l2i;

/**
 * Represents a single track of an MP4 file.
 */
public class Mp4TrackImpl extends AbstractTrack {
    private List<ByteBuffer> samples;
    private SampleDescriptionBox sampleDescriptionBox;
    private List<TimeToSampleBox.Entry> decodingTimeEntries;
    private List<CompositionTimeToSample.Entry> compositionTimeEntries;
    private long[] syncSamples = new long[0];
    private List<SampleDependencyTypeBox.Entry> sampleDependencies;
    private TrackMetaData trackMetaData = new TrackMetaData();
    private String handler;
    private AbstractMediaHeaderBox mihd;

    public Mp4TrackImpl(TrackBox trackBox) {
        final long trackId = trackBox.getTrackHeaderBox().getTrackId();
        samples = new SampleList(trackBox);
        SampleTableBox stbl = trackBox.getMediaBox().getMediaInformationBox().getSampleTableBox();
        handler = trackBox.getMediaBox().getHandlerBox().getHandlerType();

        mihd = trackBox.getMediaBox().getMediaInformationBox().getMediaHeaderBox();
        decodingTimeEntries = new LinkedList<TimeToSampleBox.Entry>();
        compositionTimeEntries = new LinkedList<CompositionTimeToSample.Entry>();
        sampleDependencies = new LinkedList<SampleDependencyTypeBox.Entry>();

        decodingTimeEntries.addAll(stbl.getTimeToSampleBox().getEntries());
        if (stbl.getCompositionTimeToSample() != null) {
            compositionTimeEntries.addAll(stbl.getCompositionTimeToSample().getEntries());
        }
        if (stbl.getSampleDependencyTypeBox() != null) {
            sampleDependencies.addAll(stbl.getSampleDependencyTypeBox().getEntries());
        }
        if (stbl.getSyncSampleBox() != null) {
            syncSamples = stbl.getSyncSampleBox().getSampleNumber();
        }


        sampleDescriptionBox = stbl.getSampleDescriptionBox();
        final List<MovieExtendsBox> movieExtendsBoxes = trackBox.getParent().getBoxes(MovieExtendsBox.class);
        if (movieExtendsBoxes.size() > 0) {
            for (MovieExtendsBox mvex : movieExtendsBoxes) {
                final List<TrackExtendsBox> trackExtendsBoxes = mvex.getBoxes(TrackExtendsBox.class);
                for (TrackExtendsBox trex : trackExtendsBoxes) {
                    if (trex.getTrackId() == trackId) {
                        List<Long> syncSampleList = new LinkedList<Long>();

                        long sampleNumber = 1;
                        for (MovieFragmentBox movieFragmentBox : trackBox.getIsoFile().getBoxes(MovieFragmentBox.class)) {
                            List<TrackFragmentBox> trafs = movieFragmentBox.getBoxes(TrackFragmentBox.class);
                            for (TrackFragmentBox traf : trafs) {
                                if (traf.getTrackFragmentHeaderBox().getTrackId() == trackId) {
                                    List<TrackRunBox> truns = traf.getBoxes(TrackRunBox.class);
                                    for (TrackRunBox trun : truns) {
                                        final TrackFragmentHeaderBox tfhd = ((TrackFragmentBox) trun.getParent()).getTrackFragmentHeaderBox();
                                        boolean first = true;
                                        for (TrackRunBox.Entry entry : trun.getEntries()) {
                                            if (trun.isSampleDurationPresent()) {
                                                if (decodingTimeEntries.size() == 0 ||
                                                        decodingTimeEntries.get(decodingTimeEntries.size() - 1).getDelta() != entry.getSampleDuration()) {
                                                    decodingTimeEntries.add(new TimeToSampleBox.Entry(1, entry.getSampleDuration()));
                                                } else {
                                                    TimeToSampleBox.Entry e = decodingTimeEntries.get(decodingTimeEntries.size() - 1);
                                                    e.setCount(e.getCount() + 1);
                                                }
                                            } else {
                                                if (tfhd.hasDefaultSampleDuration()) {
                                                    decodingTimeEntries.add(new TimeToSampleBox.Entry(1, tfhd.getDefaultSampleDuration()));
                                                } else {
                                                    decodingTimeEntries.add(new TimeToSampleBox.Entry(1, trex.getDefaultSampleDuration()));
                                                }
                                            }

                                            if (trun.isSampleCompositionTimeOffsetPresent()) {
                                                if (compositionTimeEntries.size() == 0 ||
                                                        compositionTimeEntries.get(compositionTimeEntries.size() - 1).getOffset() != entry.getSampleCompositionTimeOffset()) {
                                                    compositionTimeEntries.add(new CompositionTimeToSample.Entry(1, l2i(entry.getSampleCompositionTimeOffset())));
                                                } else {
                                                    CompositionTimeToSample.Entry e = compositionTimeEntries.get(compositionTimeEntries.size() - 1);
                                                    e.setCount(e.getCount() + 1);
                                                }
                                            }
                                            final SampleFlags sampleFlags;
                                            if (trun.isSampleFlagsPresent()) {
                                                sampleFlags = entry.getSampleFlags();
                                            } else {
                                                if (first && trun.isFirstSampleFlagsPresent()) {
                                                    sampleFlags = trun.getFirstSampleFlags();
                                                } else {
                                                    if (tfhd.hasDefaultSampleFlags()) {
                                                        sampleFlags = tfhd.getDefaultSampleFlags();
                                                    } else {
                                                        sampleFlags = trex.getDefaultSampleFlags();
                                                    }
                                                }
                                            }
                                            if (sampleFlags != null && !sampleFlags.isSampleIsDifferenceSample()) {
                                                //iframe
                                                syncSampleList.add(sampleNumber);
                                            }
                                            sampleNumber++;
                                            first = false;
                                        }
                                    }
                                }
                            }
                        }
                        // Warning: Crappy code
                        long[] oldSS = syncSamples;
                        syncSamples = new long[syncSamples.length + syncSampleList.size()];
                        System.arraycopy(oldSS, 0, syncSamples, 0, oldSS.length);
                        final Iterator<Long> iterator = syncSampleList.iterator();
                        int i = oldSS.length;
                        while (iterator.hasNext()) {
                            Long syncSampleNumber = iterator.next();
                            syncSamples[i++] = syncSampleNumber;
                        }
                    }
                }
            }
        }
        MediaHeaderBox mdhd = trackBox.getMediaBox().getMediaHeaderBox();
        TrackHeaderBox tkhd = trackBox.getTrackHeaderBox();

        setEnabled(tkhd.isEnabled());
        setInMovie(tkhd.isInMovie());
        setInPoster(tkhd.isInPoster());
        setInPreview(tkhd.isInPreview());

        trackMetaData.setTrackId(tkhd.getTrackId());
        trackMetaData.setCreationTime(mdhd.getCreationTime());
        trackMetaData.setLanguage(mdhd.getLanguage());
/*        System.err.println(mdhd.getModificationTime());
        System.err.println(DateHelper.convert(mdhd.getModificationTime()));
        System.err.println(DateHelper.convert(DateHelper.convert(mdhd.getModificationTime())));
        System.err.println(DateHelper.convert(DateHelper.convert(DateHelper.convert(mdhd.getModificationTime()))));*/

        trackMetaData.setModificationTime(mdhd.getModificationTime());
        trackMetaData.setTimescale(mdhd.getTimescale());
        trackMetaData.setHeight(tkhd.getHeight());
        trackMetaData.setWidth(tkhd.getWidth());
        trackMetaData.setLayer(tkhd.getLayer());
        trackMetaData.setMatrix(tkhd.getMatrix());
    }

    public List<ByteBuffer> getSamples() {
        return samples;
    }


    public SampleDescriptionBox getSampleDescriptionBox() {
        return sampleDescriptionBox;
    }

    public List<TimeToSampleBox.Entry> getDecodingTimeEntries() {
        return decodingTimeEntries;
    }

    public List<CompositionTimeToSample.Entry> getCompositionTimeEntries() {
        return compositionTimeEntries;
    }

    public long[] getSyncSamples() {
        return syncSamples;
    }

    public List<SampleDependencyTypeBox.Entry> getSampleDependencies() {
        return sampleDependencies;
    }

    public TrackMetaData getTrackMetaData() {
        return trackMetaData;
    }

    public String getHandler() {
        return handler;
    }

    public AbstractMediaHeaderBox getMediaHeaderBox() {
        return mihd;
    }

    public SubSampleInformationBox getSubsampleInformationBox() {
        return null;
    }

    @Override
    public String toString() {
        return "Mp4TrackImpl{" +
                "handler='" + handler + '\'' +
                '}';
    }
}
