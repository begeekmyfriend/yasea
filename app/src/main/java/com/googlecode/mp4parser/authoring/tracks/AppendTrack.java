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
import com.coremedia.iso.boxes.sampleentry.AudioSampleEntry;
import com.googlecode.mp4parser.authoring.AbstractTrack;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.TrackMetaData;
import com.googlecode.mp4parser.boxes.mp4.ESDescriptorBox;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.DecoderConfigDescriptor;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.ESDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.*;

/**
 * Appends two or more <code>Tracks</code> of the same type. No only that the type must be equal
 * also the decoder settings must be the same.
 */
public class AppendTrack extends AbstractTrack {
    Track[] tracks;
    SampleDescriptionBox stsd;

    public AppendTrack(Track... tracks) throws IOException {
        this.tracks = tracks;

        for (Track track : tracks) {

            if (stsd == null) {
                stsd = track.getSampleDescriptionBox();
            } else {
                ByteArrayOutputStream curBaos = new ByteArrayOutputStream();
                ByteArrayOutputStream refBaos = new ByteArrayOutputStream();
                track.getSampleDescriptionBox().getBox(Channels.newChannel(curBaos));
                stsd.getBox(Channels.newChannel(refBaos));
                byte[] cur = curBaos.toByteArray();
                byte[] ref = refBaos.toByteArray();

                if (!Arrays.equals(ref, cur)) {
                    SampleDescriptionBox curStsd = track.getSampleDescriptionBox();
                    if (stsd.getBoxes().size() == 1 && curStsd.getBoxes().size() == 1) {
                        if (stsd.getBoxes().get(0) instanceof AudioSampleEntry && curStsd.getBoxes().get(0) instanceof AudioSampleEntry) {
                            AudioSampleEntry aseResult = mergeAudioSampleEntries((AudioSampleEntry) stsd.getBoxes().get(0), (AudioSampleEntry) curStsd.getBoxes().get(0));
                            if (aseResult != null) {
                                stsd.setBoxes(Collections.<Box>singletonList(aseResult));
                                return;
                            }
                        }
                    }
                    throw new IOException("Cannot append " + track + " to " + tracks[0] + " since their Sample Description Boxes differ: \n" + track.getSampleDescriptionBox() + "\n vs. \n" + tracks[0].getSampleDescriptionBox());
                }
            }
        }
    }

    private AudioSampleEntry mergeAudioSampleEntries(AudioSampleEntry ase1, AudioSampleEntry ase2) throws IOException {
        if (ase1.getType().equals(ase2.getType())) {
            AudioSampleEntry ase = new AudioSampleEntry(ase2.getType());
            if (ase1.getBytesPerFrame() == ase2.getBytesPerFrame()) {
                ase.setBytesPerFrame(ase1.getBytesPerFrame());
            } else {
                return null;
            }
            if (ase1.getBytesPerPacket() == ase2.getBytesPerPacket()) {
                ase.setBytesPerPacket(ase1.getBytesPerPacket());
            } else {
                return null;
            }
            if (ase1.getBytesPerSample() == ase2.getBytesPerSample()) {
                ase.setBytesPerSample(ase1.getBytesPerSample());
            } else {
                return null;
            }
            if (ase1.getChannelCount() == ase2.getChannelCount()) {
                ase.setChannelCount(ase1.getChannelCount());
            } else {
                return null;
            }
            if (ase1.getPacketSize() == ase2.getPacketSize()) {
                ase.setPacketSize(ase1.getPacketSize());
            } else {
                return null;
            }
            if (ase1.getCompressionId() == ase2.getCompressionId()) {
                ase.setCompressionId(ase1.getCompressionId());
            } else {
                return null;
            }
            if (ase1.getSampleRate() == ase2.getSampleRate()) {
                ase.setSampleRate(ase1.getSampleRate());
            } else {
                return null;
            }
            if (ase1.getSampleSize() == ase2.getSampleSize()) {
                ase.setSampleSize(ase1.getSampleSize());
            } else {
                return null;
            }
            if (ase1.getSamplesPerPacket() == ase2.getSamplesPerPacket()) {
                ase.setSamplesPerPacket(ase1.getSamplesPerPacket());
            } else {
                return null;
            }
            if (ase1.getSoundVersion() == ase2.getSoundVersion()) {
                ase.setSoundVersion(ase1.getSoundVersion());
            } else {
                return null;
            }
            if (Arrays.equals(ase1.getSoundVersion2Data(), ase2.getSoundVersion2Data())) {
                ase.setSoundVersion2Data(ase1.getSoundVersion2Data());
            } else {
                return null;
            }
            if (ase1.getBoxes().size() == ase2.getBoxes().size()) {
                Iterator<Box> bxs1 = ase1.getBoxes().iterator();
                Iterator<Box> bxs2 = ase2.getBoxes().iterator();
                while (bxs1.hasNext()) {
                    Box cur1 = bxs1.next();
                    Box cur2 = bxs2.next();
                    ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
                    ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
                    cur1.getBox(Channels.newChannel(baos1));
                    cur2.getBox(Channels.newChannel(baos2));
                    if (Arrays.equals(baos1.toByteArray(), baos2.toByteArray())) {
                        ase.addBox(cur1);
                    } else {
                        if (ESDescriptorBox.TYPE.equals(cur1.getType()) && ESDescriptorBox.TYPE.equals(cur2.getType())) {
                            ESDescriptorBox esdsBox1 = (ESDescriptorBox) cur1;
                            ESDescriptorBox esdsBox2 = (ESDescriptorBox) cur2;
                            ESDescriptor esds1 = esdsBox1.getEsDescriptor();
                            ESDescriptor esds2 = esdsBox2.getEsDescriptor();
                            if (esds1.getURLFlag() != esds2.getURLFlag()) {
                                return null;
                            }
                            if (esds1.getURLLength() != esds2.getURLLength()) {
                                return null;
                            }
                            if (esds1.getDependsOnEsId() != esds2.getDependsOnEsId()) {
                                return null;
                            }
                            if (esds1.getEsId() != esds2.getEsId()) {
                                return null;
                            }
                            if (esds1.getoCREsId() != esds2.getoCREsId()) {
                                return null;
                            }
                            if (esds1.getoCRstreamFlag() != esds2.getoCRstreamFlag()) {
                                return null;
                            }
                            if (esds1.getRemoteODFlag() != esds2.getRemoteODFlag()) {
                                return null;
                            }
                            if (esds1.getStreamDependenceFlag() != esds2.getStreamDependenceFlag()) {
                                return null;
                            }
                            if (esds1.getStreamPriority() != esds2.getStreamPriority()) {
                                return null;
                            }
                            if (esds1.getURLString() != null ? !esds1.getURLString().equals(esds2.getURLString()) : esds2.getURLString() != null) {
                                return null;
                            }
                            if (esds1.getDecoderConfigDescriptor() != null ? !esds1.getDecoderConfigDescriptor().equals(esds2.getDecoderConfigDescriptor()) : esds2.getDecoderConfigDescriptor() != null) {
                                DecoderConfigDescriptor dcd1 = esds1.getDecoderConfigDescriptor();
                                DecoderConfigDescriptor dcd2 = esds2.getDecoderConfigDescriptor();
                                if (!dcd1.getAudioSpecificInfo().equals(dcd2.getAudioSpecificInfo())) {
                                    return null;
                                }
                                if (dcd1.getAvgBitRate() != dcd2.getAvgBitRate()) {
                                    // I don't care
                                }
                                if (dcd1.getBufferSizeDB() != dcd2.getBufferSizeDB()) {
                                    // I don't care
                                }

                                if (dcd1.getDecoderSpecificInfo() != null ? !dcd1.getDecoderSpecificInfo().equals(dcd2.getDecoderSpecificInfo()) : dcd2.getDecoderSpecificInfo() != null) {
                                    return null;
                                }

                                if (dcd1.getMaxBitRate() != dcd2.getMaxBitRate()) {
                                    // I don't care
                                }
                                if (!dcd1.getProfileLevelIndicationDescriptors().equals(dcd2.getProfileLevelIndicationDescriptors())) {
                                    return null;
                                }

                                if (dcd1.getObjectTypeIndication() != dcd2.getObjectTypeIndication()) {
                                    return null;
                                }
                                if (dcd1.getStreamType() != dcd2.getStreamType()) {
                                    return null;
                                }
                                if (dcd1.getUpStream() != dcd2.getUpStream()) {
                                    return null;
                                }


                            }
                            if (esds1.getOtherDescriptors() != null ? !esds1.getOtherDescriptors().equals(esds2.getOtherDescriptors()) : esds2.getOtherDescriptors() != null) {
                                return null;
                            }
                            if (esds1.getSlConfigDescriptor() != null ? !esds1.getSlConfigDescriptor().equals(esds2.getSlConfigDescriptor()) : esds2.getSlConfigDescriptor() != null) {
                                return null;
                            }
                            ase.addBox(cur1);
                        }
                    }
                }
            }
            return ase;
        } else {
            return null;
        }


    }


    public List<ByteBuffer> getSamples() {
        ArrayList<ByteBuffer> lists = new ArrayList<ByteBuffer>();

        for (Track track : tracks) {
            lists.addAll(track.getSamples());
        }

        return lists;
    }

    public SampleDescriptionBox getSampleDescriptionBox() {
        return stsd;
    }

    public List<TimeToSampleBox.Entry> getDecodingTimeEntries() {
        if (tracks[0].getDecodingTimeEntries() != null && !tracks[0].getDecodingTimeEntries().isEmpty()) {
            List<long[]> lists = new LinkedList<long[]>();
            for (Track track : tracks) {
                lists.add(TimeToSampleBox.blowupTimeToSamples(track.getDecodingTimeEntries()));
            }

            LinkedList<TimeToSampleBox.Entry> returnDecodingEntries = new LinkedList<TimeToSampleBox.Entry>();
            for (long[] list : lists) {
                for (long nuDecodingTime : list) {
                    if (returnDecodingEntries.isEmpty() || returnDecodingEntries.getLast().getDelta() != nuDecodingTime) {
                        TimeToSampleBox.Entry e = new TimeToSampleBox.Entry(1, nuDecodingTime);
                        returnDecodingEntries.add(e);
                    } else {
                        TimeToSampleBox.Entry e = returnDecodingEntries.getLast();
                        e.setCount(e.getCount() + 1);
                    }
                }
            }
            return returnDecodingEntries;
        } else {
            return null;
        }
    }

    public List<CompositionTimeToSample.Entry> getCompositionTimeEntries() {
        if (tracks[0].getCompositionTimeEntries() != null && !tracks[0].getCompositionTimeEntries().isEmpty()) {
            List<int[]> lists = new LinkedList<int[]>();
            for (Track track : tracks) {
                lists.add(CompositionTimeToSample.blowupCompositionTimes(track.getCompositionTimeEntries()));
            }
            LinkedList<CompositionTimeToSample.Entry> compositionTimeEntries = new LinkedList<CompositionTimeToSample.Entry>();
            for (int[] list : lists) {
                for (int compositionTime : list) {
                    if (compositionTimeEntries.isEmpty() || compositionTimeEntries.getLast().getOffset() != compositionTime) {
                        CompositionTimeToSample.Entry e = new CompositionTimeToSample.Entry(1, compositionTime);
                        compositionTimeEntries.add(e);
                    } else {
                        CompositionTimeToSample.Entry e = compositionTimeEntries.getLast();
                        e.setCount(e.getCount() + 1);
                    }
                }
            }
            return compositionTimeEntries;
        } else {
            return null;
        }
    }

    public long[] getSyncSamples() {
        if (tracks[0].getSyncSamples() != null && tracks[0].getSyncSamples().length > 0) {
            int numSyncSamples = 0;
            for (Track track : tracks) {
                numSyncSamples += track.getSyncSamples().length;
            }
            long[] returnSyncSamples = new long[numSyncSamples];

            int pos = 0;
            long samplesBefore = 0;
            for (Track track : tracks) {
                for (long l : track.getSyncSamples()) {
                    returnSyncSamples[pos++] = samplesBefore + l;
                }
                samplesBefore += track.getSamples().size();
            }
            return returnSyncSamples;
        } else {
            return null;
        }
    }

    public List<SampleDependencyTypeBox.Entry> getSampleDependencies() {
        if (tracks[0].getSampleDependencies() != null && !tracks[0].getSampleDependencies().isEmpty()) {
            List<SampleDependencyTypeBox.Entry> list = new LinkedList<SampleDependencyTypeBox.Entry>();
            for (Track track : tracks) {
                list.addAll(track.getSampleDependencies());
            }
            return list;
        } else {
            return null;
        }
    }

    public TrackMetaData getTrackMetaData() {
        return tracks[0].getTrackMetaData();
    }

    public String getHandler() {
        return tracks[0].getHandler();
    }

    public Box getMediaHeaderBox() {
        return tracks[0].getMediaHeaderBox();
    }

    public SubSampleInformationBox getSubsampleInformationBox() {
        return tracks[0].getSubsampleInformationBox();
    }

}
