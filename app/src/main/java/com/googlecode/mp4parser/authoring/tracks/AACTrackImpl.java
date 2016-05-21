/*
 * Copyright 2012 castLabs GmbH, Berlin
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
import com.googlecode.mp4parser.authoring.TrackMetaData;
import com.googlecode.mp4parser.boxes.AC3SpecificBox;
import com.googlecode.mp4parser.boxes.mp4.ESDescriptorBox;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.*;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;

/**
 */
public class AACTrackImpl extends AbstractTrack {
    public static Map<Integer, Integer> samplingFrequencyIndexMap = new HashMap<Integer, Integer>();

    static {
        samplingFrequencyIndexMap.put(96000, 0);
        samplingFrequencyIndexMap.put(88200, 1);
        samplingFrequencyIndexMap.put(64000, 2);
        samplingFrequencyIndexMap.put(48000, 3);
        samplingFrequencyIndexMap.put(44100, 4);
        samplingFrequencyIndexMap.put(32000, 5);
        samplingFrequencyIndexMap.put(24000, 6);
        samplingFrequencyIndexMap.put(22050, 7);
        samplingFrequencyIndexMap.put(16000, 8);
        samplingFrequencyIndexMap.put(12000, 9);
        samplingFrequencyIndexMap.put(11025, 10);
        samplingFrequencyIndexMap.put(8000, 11);
        samplingFrequencyIndexMap.put(0x0, 96000);
        samplingFrequencyIndexMap.put(0x1, 88200);
        samplingFrequencyIndexMap.put(0x2, 64000);
        samplingFrequencyIndexMap.put(0x3, 48000);
        samplingFrequencyIndexMap.put(0x4, 44100);
        samplingFrequencyIndexMap.put(0x5, 32000);
        samplingFrequencyIndexMap.put(0x6, 24000);
        samplingFrequencyIndexMap.put(0x7, 22050);
        samplingFrequencyIndexMap.put(0x8, 16000);
        samplingFrequencyIndexMap.put(0x9, 12000);
        samplingFrequencyIndexMap.put(0xa, 11025);
        samplingFrequencyIndexMap.put(0xb, 8000);
    }

    TrackMetaData trackMetaData = new TrackMetaData();
    SampleDescriptionBox sampleDescriptionBox;

    int samplerate;
    int bitrate;
    int channelCount;
    int channelconfig;

    int bufferSizeDB;
    long maxBitRate;
    long avgBitRate;

    private BufferedInputStream inputStream;
    private List<ByteBuffer> samples;
    boolean readSamples = false;
    List<TimeToSampleBox.Entry> stts;
    private String lang = "und";


    public AACTrackImpl(InputStream inputStream, String lang) throws IOException {
        this.lang = lang;
        parse(inputStream);
     }

    public AACTrackImpl(InputStream inputStream) throws IOException {
        parse(inputStream);
     }

    private void parse(InputStream inputStream) throws IOException {
        this.inputStream = new BufferedInputStream(inputStream);
        stts = new LinkedList<TimeToSampleBox.Entry>();

        if (!readVariables()) {
            throw new IOException();
        }

        samples = new LinkedList<ByteBuffer>();
        if (!readSamples()) {
            throw new IOException();
        }

        double packetsPerSecond = (double)samplerate / 1024.0;
        double duration = samples.size() / packetsPerSecond;

        long dataSize = 0;
        LinkedList<Integer> queue = new LinkedList<Integer>();
        for (int i = 0; i < samples.size(); i++) {
            int size = samples.get(i).capacity();
            dataSize += size;
            queue.add(size);
            while (queue.size() > packetsPerSecond) {
                queue.pop();
            }
            if (queue.size() == (int) packetsPerSecond) {
                int currSize = 0;
                for (int j = 0 ; j < queue.size(); j++) {
                    currSize += queue.get(j);
                }
                double currBitrate = 8.0 * currSize / queue.size() * packetsPerSecond;
                if (currBitrate > maxBitRate) {
                    maxBitRate = (int)currBitrate;
                }
            }
        }

        avgBitRate = (int) (8 * dataSize / duration);

        bufferSizeDB = 1536; /* TODO: Calcultate this somehow! */

        sampleDescriptionBox = new SampleDescriptionBox();
        AudioSampleEntry audioSampleEntry = new AudioSampleEntry("mp4a");
        audioSampleEntry.setChannelCount(2);
        audioSampleEntry.setSampleRate(samplerate);
        audioSampleEntry.setDataReferenceIndex(1);
        audioSampleEntry.setSampleSize(16);


        ESDescriptorBox esds = new ESDescriptorBox();
        ESDescriptor descriptor = new ESDescriptor();
        descriptor.setEsId(0);

        SLConfigDescriptor slConfigDescriptor = new SLConfigDescriptor();
        slConfigDescriptor.setPredefined(2);
        descriptor.setSlConfigDescriptor(slConfigDescriptor);

        DecoderConfigDescriptor decoderConfigDescriptor = new DecoderConfigDescriptor();
        decoderConfigDescriptor.setObjectTypeIndication(0x40);
        decoderConfigDescriptor.setStreamType(5);
        decoderConfigDescriptor.setBufferSizeDB(bufferSizeDB);
        decoderConfigDescriptor.setMaxBitRate(maxBitRate);
        decoderConfigDescriptor.setAvgBitRate(avgBitRate);

        AudioSpecificConfig audioSpecificConfig = new AudioSpecificConfig();
        audioSpecificConfig.setAudioObjectType(2); // AAC LC
        audioSpecificConfig.setSamplingFrequencyIndex(samplingFrequencyIndexMap.get(samplerate));
        audioSpecificConfig.setChannelConfiguration(channelconfig);
        decoderConfigDescriptor.setAudioSpecificInfo(audioSpecificConfig);

        descriptor.setDecoderConfigDescriptor(decoderConfigDescriptor);

        ByteBuffer data = descriptor.serialize();
        esds.setData(data);
        audioSampleEntry.addBox(esds);
        sampleDescriptionBox.addBox(audioSampleEntry);

        trackMetaData.setCreationTime(new Date());
        trackMetaData.setModificationTime(new Date());
        trackMetaData.setLanguage(lang);
        trackMetaData.setTimescale(samplerate); // Audio tracks always use samplerate as timescale
    }

    public SampleDescriptionBox getSampleDescriptionBox() {
        return sampleDescriptionBox;
    }

    public List<TimeToSampleBox.Entry> getDecodingTimeEntries() {
        return stts;
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

    public TrackMetaData getTrackMetaData() {
        return trackMetaData;
    }

    public String getHandler() {
        return "soun";
    }

    public List<ByteBuffer> getSamples() {
        return samples;
    }

    public Box getMediaHeaderBox() {
        return new SoundMediaHeaderBox();
    }

    public SubSampleInformationBox getSubsampleInformationBox() {
        return null;
    }

    private boolean readVariables() throws IOException {
        byte[] data = new byte[100];
        inputStream.mark(100);
        if (100 != inputStream.read(data, 0, 100)) {
            return false;
        }
        inputStream.reset(); // Rewind
        ByteBuffer bb = ByteBuffer.wrap(data);
        BitReaderBuffer brb = new BitReaderBuffer(bb);
        int syncword = brb.readBits(12);
        if (syncword != 0xfff) {
            return false;
        }
        int id = brb.readBits(1);
        int layer = brb.readBits(2);
        int protectionAbsent = brb.readBits(1);
        int profile = brb.readBits(2);
        samplerate = samplingFrequencyIndexMap.get(brb.readBits(4));
        brb.readBits(1);
        channelconfig = brb.readBits(3);
        int original = brb.readBits(1);
        int home = brb.readBits(1);
        int emphasis = brb.readBits(2);

        return true;
    }

    private boolean readSamples() throws IOException {
        if (readSamples) {
            return true;
        }

        readSamples = true;
        byte[] header = new byte[15];
        boolean ret = false;
        inputStream.mark(15);
        while (-1 != inputStream.read(header)) {
            ret = true;
            ByteBuffer bb = ByteBuffer.wrap(header);
            inputStream.reset();
            BitReaderBuffer brb = new BitReaderBuffer(bb);
            int syncword = brb.readBits(12);
            if (syncword != 0xfff) {
                return false;
            }
            brb.readBits(3);
            int protectionAbsent = brb.readBits(1);
            brb.readBits(14);
            int frameSize = brb.readBits(13);
            int bufferFullness = brb.readBits(11);
            int noBlocks = brb.readBits(2);
            int used = (int) Math.ceil(brb.getPosition() / 8.0);
            if (protectionAbsent == 0) {
                used += 2;
            }
            inputStream.skip(used);
            frameSize -= used;
//            System.out.println("Size: " + frameSize + " fullness: " + bufferFullness + " no blocks: " + noBlocks);
            byte[] data = new byte[frameSize];
            inputStream.read(data);
            samples.add(ByteBuffer.wrap(data));
            stts.add(new TimeToSampleBox.Entry(1, 1024));
            inputStream.mark(15);
        }
        return ret;
    }

    @Override
    public String toString() {
        return "AACTrackImpl{" +
                "samplerate=" + samplerate +
                ", bitrate=" + bitrate +
                ", channelCount=" + channelCount +
                ", channelconfig=" + channelconfig +
                '}';
    }
}

