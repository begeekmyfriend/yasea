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
package com.googlecode.mp4parser.authoring.adaptivestreaming;

import com.coremedia.iso.Hex;
import com.coremedia.iso.boxes.SampleDescriptionBox;
import com.coremedia.iso.boxes.SoundMediaHeaderBox;
import com.coremedia.iso.boxes.VideoMediaHeaderBox;
import com.coremedia.iso.boxes.h264.AvcConfigurationBox;
import com.coremedia.iso.boxes.sampleentry.AudioSampleEntry;
import com.coremedia.iso.boxes.sampleentry.VisualSampleEntry;
import com.googlecode.mp4parser.Version;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.builder.FragmentIntersectionFinder;
import com.googlecode.mp4parser.boxes.DTSSpecificBox;
import com.googlecode.mp4parser.boxes.EC3SpecificBox;
import com.googlecode.mp4parser.boxes.mp4.ESDescriptorBox;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.AudioSpecificConfig;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

public class FlatManifestWriterImpl extends AbstractManifestWriter {
    private static final Logger LOG = Logger.getLogger(FlatManifestWriterImpl.class.getName());

    protected FlatManifestWriterImpl(FragmentIntersectionFinder intersectionFinder) {
        super(intersectionFinder);
    }

    /**
     * Overwrite this method in subclasses to add your specialities.
     *
     * @param manifest the original manifest
     * @return your customized version of the manifest
     */
    protected Document customizeManifest(Document manifest) {
        return manifest;
    }

    public String getManifest(Movie movie) throws IOException {

        LinkedList<VideoQuality> videoQualities = new LinkedList<VideoQuality>();
        long videoTimescale = -1;

        LinkedList<AudioQuality> audioQualities = new LinkedList<AudioQuality>();
        long audioTimescale = -1;

        for (Track track : movie.getTracks()) {
            if (track.getMediaHeaderBox() instanceof VideoMediaHeaderBox) {
                videoFragmentsDurations = checkFragmentsAlign(videoFragmentsDurations, calculateFragmentDurations(track, movie));
                SampleDescriptionBox stsd = track.getSampleDescriptionBox();
                videoQualities.add(getVideoQuality(track, (VisualSampleEntry) stsd.getSampleEntry()));
                if (videoTimescale == -1) {
                    videoTimescale = track.getTrackMetaData().getTimescale();
                } else {
                    assert videoTimescale == track.getTrackMetaData().getTimescale();
                }
            }
            if (track.getMediaHeaderBox() instanceof SoundMediaHeaderBox) {
                audioFragmentsDurations = checkFragmentsAlign(audioFragmentsDurations, calculateFragmentDurations(track, movie));
                SampleDescriptionBox stsd = track.getSampleDescriptionBox();
                audioQualities.add(getAudioQuality(track, (AudioSampleEntry) stsd.getSampleEntry()));
                if (audioTimescale == -1) {
                    audioTimescale = track.getTrackMetaData().getTimescale();
                } else {
                    assert audioTimescale == track.getTrackMetaData().getTimescale();
                }

            }
        }
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder;
        try {
            documentBuilder = documentBuilderFactory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IOException(e);
        }
        Document document = documentBuilder.newDocument();


        Element smoothStreamingMedia = document.createElement("SmoothStreamingMedia");
        document.appendChild(smoothStreamingMedia);
        smoothStreamingMedia.setAttribute("MajorVersion", "2");
        smoothStreamingMedia.setAttribute("MinorVersion", "1");
// silverlight ignores the timescale attr        smoothStreamingMedia.addAttribute(new Attribute("TimeScale", Long.toString(movieTimeScale)));
        smoothStreamingMedia.setAttribute("Duration", "0");

        smoothStreamingMedia.appendChild(document.createComment(Version.VERSION));
        Element videoStreamIndex = document.createElement("StreamIndex");
        videoStreamIndex.setAttribute("Type", "video");
        videoStreamIndex.setAttribute("TimeScale", Long.toString(videoTimescale)); // silverlight ignores the timescale attr
        videoStreamIndex.setAttribute("Chunks", Integer.toString(videoFragmentsDurations.length));
        videoStreamIndex.setAttribute("Url", "video/{bitrate}/{start time}");
        videoStreamIndex.setAttribute("QualityLevels", Integer.toString(videoQualities.size()));
        smoothStreamingMedia.appendChild(videoStreamIndex);

        for (int i = 0; i < videoQualities.size(); i++) {
            VideoQuality vq = videoQualities.get(i);
            Element qualityLevel = document.createElement("QualityLevel");
            qualityLevel.setAttribute("Index", Integer.toString(i));
            qualityLevel.setAttribute("Bitrate", Long.toString(vq.bitrate));
            qualityLevel.setAttribute("FourCC", vq.fourCC);
            qualityLevel.setAttribute("MaxWidth", Long.toString(vq.width));
            qualityLevel.setAttribute("MaxHeight", Long.toString(vq.height));
            qualityLevel.setAttribute("CodecPrivateData", vq.codecPrivateData);
            qualityLevel.setAttribute("NALUnitLengthField", Integer.toString(vq.nalLength));
            videoStreamIndex.appendChild(qualityLevel);
        }

        for (int i = 0; i < videoFragmentsDurations.length; i++) {
            Element c = document.createElement("c");
            c.setAttribute("n", Integer.toString(i));
            c.setAttribute("d", Long.toString(videoFragmentsDurations[i]));
            videoStreamIndex.appendChild(c);
        }

        if (audioFragmentsDurations != null) {
            Element audioStreamIndex = document.createElement("StreamIndex");
            audioStreamIndex.setAttribute("Type", "audio");
            audioStreamIndex.setAttribute("TimeScale", Long.toString(audioTimescale)); // silverlight ignores the timescale attr
            audioStreamIndex.setAttribute("Chunks", Integer.toString(audioFragmentsDurations.length));
            audioStreamIndex.setAttribute("Url", "audio/{bitrate}/{start time}");
            audioStreamIndex.setAttribute("QualityLevels", Integer.toString(audioQualities.size()));
            smoothStreamingMedia.appendChild(audioStreamIndex);

            for (int i = 0; i < audioQualities.size(); i++) {
                AudioQuality aq = audioQualities.get(i);
                Element qualityLevel = document.createElement("QualityLevel");
                qualityLevel.setAttribute("Index", Integer.toString(i));
                qualityLevel.setAttribute("FourCC", aq.fourCC);
                qualityLevel.setAttribute("Bitrate", Long.toString(aq.bitrate));
                qualityLevel.setAttribute("AudioTag", Integer.toString(aq.audioTag));
                qualityLevel.setAttribute("SamplingRate", Long.toString(aq.samplingRate));
                qualityLevel.setAttribute("Channels", Integer.toString(aq.channels));
                qualityLevel.setAttribute("BitsPerSample", Integer.toString(aq.bitPerSample));
                qualityLevel.setAttribute("PacketSize", Integer.toString(aq.packetSize));
                qualityLevel.setAttribute("CodecPrivateData", aq.codecPrivateData);
                audioStreamIndex.appendChild(qualityLevel);
            }
            for (int i = 0; i < audioFragmentsDurations.length; i++) {
                Element c = document.createElement("c");
                c.setAttribute("n", Integer.toString(i));
                c.setAttribute("d", Long.toString(audioFragmentsDurations[i]));
                audioStreamIndex.appendChild(c);
            }
        }

        document.setXmlStandalone(true);
        Source source = new DOMSource(document);
        StringWriter stringWriter = new StringWriter();
        Result result = new StreamResult(stringWriter);
        TransformerFactory factory = TransformerFactory.newInstance();
        Transformer transformer;
        try {
            transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.transform(source, result);
        } catch (TransformerConfigurationException e) {
            throw new IOException(e);
        } catch (TransformerException e) {
            throw new IOException(e);
        }
        return stringWriter.getBuffer().toString();


    }

    private AudioQuality getAudioQuality(Track track, AudioSampleEntry ase) {
        if (getFormat(ase).equals("mp4a")) {
            return getAacAudioQuality(track, ase);
        } else if (getFormat(ase).equals("ec-3")) {
            return getEc3AudioQuality(track, ase);
        } else if (getFormat(ase).startsWith("dts")) {
            return getDtsAudioQuality(track, ase);
        } else {
            throw new InternalError("I don't know what to do with audio of type " + getFormat(ase));
        }

    }

    private AudioQuality getAacAudioQuality(Track track, AudioSampleEntry ase) {
        AudioQuality l = new AudioQuality();
        final ESDescriptorBox esDescriptorBox = ase.getBoxes(ESDescriptorBox.class).get(0);
        final AudioSpecificConfig audioSpecificConfig = esDescriptorBox.getEsDescriptor().getDecoderConfigDescriptor().getAudioSpecificInfo();
        if (audioSpecificConfig.getSbrPresentFlag() == 1) {
            l.fourCC = "AACH";
        } else if (audioSpecificConfig.getPsPresentFlag() == 1) {
            l.fourCC = "AACP"; //I'm not sure if that's what MS considers as AAC+ - because actually AAC+ and AAC-HE should be the same...
        } else {
            l.fourCC = "AACL";
        }
        l.bitrate = getBitrate(track);
        l.audioTag = 255;
        l.samplingRate = ase.getSampleRate();
        l.channels = ase.getChannelCount();
        l.bitPerSample = ase.getSampleSize();
        l.packetSize = 4;
        l.codecPrivateData = getAudioCodecPrivateData(audioSpecificConfig);
        //Index="0" Bitrate="103000" AudioTag="255" SamplingRate="44100" Channels="2" BitsPerSample="16" packetSize="4" CodecPrivateData=""
        return l;
    }

    private AudioQuality getEc3AudioQuality(Track track, AudioSampleEntry ase) {
        final EC3SpecificBox ec3SpecificBox = ase.getBoxes(EC3SpecificBox.class).get(0);
        if (ec3SpecificBox == null) {
            throw new RuntimeException("EC-3 track misses EC3SpecificBox!");
        }

        short nfchans = 0; //full bandwidth channels
        short lfechans = 0;
        byte dWChannelMaskFirstByte = 0;
        byte dWChannelMaskSecondByte = 0;
        for (EC3SpecificBox.Entry entry : ec3SpecificBox.getEntries()) {
            /*
            Table 4.3: Audio coding mode
            acmod Audio coding mode Nfchans Channel array ordering
            000 1 + 1 2 Ch1, Ch2
            001 1/0 1 C
            010 2/0 2 L, R
            011 3/0 3 L, C, R
            100 2/1 3 L, R, S
            101 3/1 4 L, C, R, S
            110 2/2 4 L, R, SL, SR
            111 3/2 5 L, C, R, SL, SR

            Table F.2: Chan_loc field bit assignments
            Bit Location
            0 Lc/Rc pair
            1 Lrs/Rrs pair
            2 Cs
            3 Ts
            4 Lsd/Rsd pair
            5 Lw/Rw pair
            6 Lvh/Rvh pair
            7 Cvh
            8 LFE2
            */
            switch (entry.acmod) {
                case 0: //1+1; Ch1, Ch2
                    nfchans += 2;
                    throw new RuntimeException("Smooth Streaming doesn't support DDP 1+1 mode");
                case 1: //1/0; C
                    nfchans += 1;
                    if (entry.num_dep_sub > 0) {
                        DependentSubstreamMask dependentSubstreamMask = new DependentSubstreamMask(dWChannelMaskFirstByte, dWChannelMaskSecondByte, entry).process();
                        dWChannelMaskFirstByte |= dependentSubstreamMask.getdWChannelMaskFirstByte();
                        dWChannelMaskSecondByte |= dependentSubstreamMask.getdWChannelMaskSecondByte();
                    } else {
                        dWChannelMaskFirstByte |= 0x20;
                    }
                    break;
                case 2: //2/0; L, R
                    nfchans += 2;
                    if (entry.num_dep_sub > 0) {
                        DependentSubstreamMask dependentSubstreamMask = new DependentSubstreamMask(dWChannelMaskFirstByte, dWChannelMaskSecondByte, entry).process();
                        dWChannelMaskFirstByte |= dependentSubstreamMask.getdWChannelMaskFirstByte();
                        dWChannelMaskSecondByte |= dependentSubstreamMask.getdWChannelMaskSecondByte();
                    } else {
                        dWChannelMaskFirstByte |= 0xC0;
                    }
                    break;
                case 3: //3/0; L, C, R
                    nfchans += 3;
                    if (entry.num_dep_sub > 0) {
                        DependentSubstreamMask dependentSubstreamMask = new DependentSubstreamMask(dWChannelMaskFirstByte, dWChannelMaskSecondByte, entry).process();
                        dWChannelMaskFirstByte |= dependentSubstreamMask.getdWChannelMaskFirstByte();
                        dWChannelMaskSecondByte |= dependentSubstreamMask.getdWChannelMaskSecondByte();
                    } else {
                        dWChannelMaskFirstByte |= 0xE0;
                    }
                    break;
                case 4: //2/1; L, R, S
                    nfchans += 3;
                    if (entry.num_dep_sub > 0) {
                        DependentSubstreamMask dependentSubstreamMask = new DependentSubstreamMask(dWChannelMaskFirstByte, dWChannelMaskSecondByte, entry).process();
                        dWChannelMaskFirstByte |= dependentSubstreamMask.getdWChannelMaskFirstByte();
                        dWChannelMaskSecondByte |= dependentSubstreamMask.getdWChannelMaskSecondByte();
                    } else {
                        dWChannelMaskFirstByte |= 0xC0;
                        dWChannelMaskSecondByte |= 0x80;
                    }
                    break;
                case 5: //3/1; L, C, R, S
                    nfchans += 4;
                    if (entry.num_dep_sub > 0) {
                        DependentSubstreamMask dependentSubstreamMask = new DependentSubstreamMask(dWChannelMaskFirstByte, dWChannelMaskSecondByte, entry).process();
                        dWChannelMaskFirstByte |= dependentSubstreamMask.getdWChannelMaskFirstByte();
                        dWChannelMaskSecondByte |= dependentSubstreamMask.getdWChannelMaskSecondByte();
                    } else {
                        dWChannelMaskFirstByte |= 0xE0;
                        dWChannelMaskSecondByte |= 0x80;
                    }
                    break;
                case 6: //2/2; L, R, SL, SR
                    nfchans += 4;
                    if (entry.num_dep_sub > 0) {
                        DependentSubstreamMask dependentSubstreamMask = new DependentSubstreamMask(dWChannelMaskFirstByte, dWChannelMaskSecondByte, entry).process();
                        dWChannelMaskFirstByte |= dependentSubstreamMask.getdWChannelMaskFirstByte();
                        dWChannelMaskSecondByte |= dependentSubstreamMask.getdWChannelMaskSecondByte();
                    } else {
                        dWChannelMaskFirstByte |= 0xCC;
                    }
                    break;
                case 7: //3/2; L, C, R, SL, SR
                    nfchans += 5;
                    if (entry.num_dep_sub > 0) {
                        DependentSubstreamMask dependentSubstreamMask = new DependentSubstreamMask(dWChannelMaskFirstByte, dWChannelMaskSecondByte, entry).process();
                        dWChannelMaskFirstByte |= dependentSubstreamMask.getdWChannelMaskFirstByte();
                        dWChannelMaskSecondByte |= dependentSubstreamMask.getdWChannelMaskSecondByte();
                    } else {
                        dWChannelMaskFirstByte |= 0xEC;
                    }
                    break;
            }
            if (entry.lfeon == 1) {
                lfechans ++;
                dWChannelMaskFirstByte |= 0x10;
            }
        }

        final ByteBuffer waveformatex = ByteBuffer.allocate(22);
        waveformatex.put(new byte[]{0x00, 0x06}); //1536 wSamplesPerBlock - little endian
        waveformatex.put(dWChannelMaskFirstByte);
        waveformatex.put(dWChannelMaskSecondByte);
        waveformatex.put(new byte[]{0x00, 0x00}); //pad dwChannelMask to 32bit
        waveformatex.put(new byte[]{(byte)0xAF, (byte)0x87, (byte)0xFB, (byte)0xA7, 0x02, 0x2D, (byte)0xFB, 0x42, (byte)0xA4, (byte)0xD4, 0x05, (byte)0xCD, (byte)0x93, (byte)0x84, 0x3B, (byte)0xDD}); //SubFormat - Dolby Digital Plus GUID

        final ByteBuffer dec3Content = ByteBuffer.allocate((int) ec3SpecificBox.getContentSize());
        ec3SpecificBox.getContent(dec3Content);

        AudioQuality l = new AudioQuality();
        l.fourCC = "EC-3";
        l.bitrate = getBitrate(track);
        l.audioTag = 65534;
        l.samplingRate = ase.getSampleRate();
        l.channels = nfchans + lfechans;
        l.bitPerSample = 16;
        l.packetSize = track.getSamples().get(0).limit(); //assuming all are same size
        l.codecPrivateData = Hex.encodeHex(waveformatex.array()) + Hex.encodeHex(dec3Content.array()); //append EC3SpecificBox (big endian) at the end of waveformatex
        return l;
    }

    private AudioQuality getDtsAudioQuality(Track track, AudioSampleEntry ase) {
        final DTSSpecificBox dtsSpecificBox = ase.getBoxes(DTSSpecificBox.class).get(0);
        if (dtsSpecificBox == null) {
            throw new RuntimeException("DTS track misses DTSSpecificBox!");
        }

        final ByteBuffer waveformatex = ByteBuffer.allocate(22);
        final int frameDuration = dtsSpecificBox.getFrameDuration();
        short samplesPerBlock = 0;
        switch (frameDuration) {
            case 0:
                samplesPerBlock = 512;
                break;
            case 1:
                samplesPerBlock = 1024;
                break;
            case 2:
                samplesPerBlock = 2048;
                break;
            case 3:
                samplesPerBlock = 4096;
                break;
        }
        waveformatex.put((byte) (samplesPerBlock & 0xff));
        waveformatex.put((byte) (samplesPerBlock >>> 8));
        final int dwChannelMask = getNumChannelsAndMask(dtsSpecificBox)[1];
        waveformatex.put((byte) (dwChannelMask & 0xff));
        waveformatex.put((byte) (dwChannelMask >>> 8));
        waveformatex.put((byte) (dwChannelMask >>> 16));
        waveformatex.put((byte) (dwChannelMask >>> 24));
        waveformatex.put(new byte[]{(byte)0xAE, (byte)0xE4, (byte)0xBF, (byte)0x5E, (byte)0x61, (byte)0x5E, (byte)0x41, (byte)0x87, (byte)0x92, (byte)0xFC, (byte)0xA4, (byte)0x81, (byte)0x26, (byte)0x99, (byte)0x02, (byte)0x11}); //DTS-HD GUID

        final ByteBuffer dtsCodecPrivateData = ByteBuffer.allocate(8);
        dtsCodecPrivateData.put((byte) dtsSpecificBox.getStreamConstruction());

        final int channelLayout = dtsSpecificBox.getChannelLayout();
        dtsCodecPrivateData.put((byte) (channelLayout & 0xff));
        dtsCodecPrivateData.put((byte) (channelLayout >>> 8));
        dtsCodecPrivateData.put((byte) (channelLayout >>> 16));
        dtsCodecPrivateData.put((byte) (channelLayout >>> 24));

        byte dtsFlags = (byte) (dtsSpecificBox.getMultiAssetFlag() << 1);
        dtsFlags |= dtsSpecificBox.getLBRDurationMod();
        dtsCodecPrivateData.put(dtsFlags);
        dtsCodecPrivateData.put(new byte[]{0x00, 0x00}); //reserved

        AudioQuality l = new AudioQuality();
        l.fourCC = getFormat(ase);
        l.bitrate = dtsSpecificBox.getAvgBitRate();
        l.audioTag = 65534;
        l.samplingRate = dtsSpecificBox.getDTSSamplingFrequency();
        l.channels = getNumChannelsAndMask(dtsSpecificBox)[0];
        l.bitPerSample = 16;
        l.packetSize = track.getSamples().get(0).limit(); //assuming all are same size
        l.codecPrivateData = Hex.encodeHex(waveformatex.array()) + Hex.encodeHex(dtsCodecPrivateData.array());
        return l;

    }

    /* dwChannelMask
    L SPEAKER_FRONT_LEFT 0x00000001
    R SPEAKER_FRONT_RIGHT 0x00000002
    C SPEAKER_FRONT_CENTER 0x00000004
    LFE1 SPEAKER_LOW_FREQUENCY 0x00000008
    Ls or Lsr* SPEAKER_BACK_LEFT 0x00000010
    Rs or Rsr* SPEAKER_BACK_RIGHT 0x00000020
    Lc SPEAKER_FRONT_LEFT_OF_CENTER 0x00000040
    Rc SPEAKER_FRONT_RIGHT_OF_CENTER 0x00000080
    Cs SPEAKER_BACK_CENTER 0x00000100
    Lss SPEAKER_SIDE_LEFT 0x00000200
    Rss SPEAKER_SIDE_RIGHT 0x00000400
    Oh SPEAKER_TOP_CENTER 0x00000800
    Lh SPEAKER_TOP_FRONT_LEFT 0x00001000
    Ch SPEAKER_TOP_FRONT_CENTER 0x00002000
    Rh SPEAKER_TOP_FRONT_RIGHT 0x00004000
    Lhr SPEAKER_TOP_BACK_LEFT 0x00008000
    Chf SPEAKER_TOP_BACK_CENTER 0x00010000
    Rhr SPEAKER_TOP_BACK_RIGHT 0x00020000
    SPEAKER_RESERVED 0x80000000

    * if Lss, Rss exist, then this position is equivalent to Lsr, Rsr respectively
     */
    private int[] getNumChannelsAndMask(DTSSpecificBox dtsSpecificBox) {
        final int channelLayout = dtsSpecificBox.getChannelLayout();
        int numChannels = 0;
        int dwChannelMask = 0;
        if ((channelLayout & 0x0001) == 0x0001) {
            //0001h Center in front of listener 1
            numChannels += 1;
            dwChannelMask |= 0x00000004; //SPEAKER_FRONT_CENTER
        }
        if ((channelLayout & 0x0002) == 0x0002) {
            //0002h Left/Right in front 2
            numChannels += 2;
            dwChannelMask |= 0x00000001; //SPEAKER_FRONT_LEFT
            dwChannelMask |= 0x00000002; //SPEAKER_FRONT_RIGHT
        }
        if ((channelLayout & 0x0004) == 0x0004) {
            //0004h Left/Right surround on side in rear 2
            numChannels += 2;
            //* if Lss, Rss exist, then this position is equivalent to Lsr, Rsr respectively
            dwChannelMask |= 0x00000010; //SPEAKER_BACK_LEFT
            dwChannelMask |= 0x00000020; //SPEAKER_BACK_RIGHT
        }
        if ((channelLayout & 0x0008) == 0x0008) {
            //0008h Low frequency effects subwoofer 1
            numChannels += 1;
            dwChannelMask |= 0x00000008; //SPEAKER_LOW_FREQUENCY
        }
        if ((channelLayout & 0x0010) == 0x0010) {
            //0010h Center surround in rear 1
            numChannels += 1;
            dwChannelMask |= 0x00000100; //SPEAKER_BACK_CENTER
        }
        if ((channelLayout & 0x0020) == 0x0020) {
            //0020h Left/Right height in front 2
            numChannels += 2;
            dwChannelMask |= 0x00001000; //SPEAKER_TOP_FRONT_LEFT
            dwChannelMask |= 0x00004000; //SPEAKER_TOP_FRONT_RIGHT
        }
        if ((channelLayout & 0x0040) == 0x0040) {
            //0040h Left/Right surround in rear 2
            numChannels += 2;
            dwChannelMask |= 0x00000010; //SPEAKER_BACK_LEFT
            dwChannelMask |= 0x00000020; //SPEAKER_BACK_RIGHT
        }
        if ((channelLayout & 0x0080) == 0x0080) {
            //0080h Center Height in front 1
            numChannels += 1;
            dwChannelMask |= 0x00002000; //SPEAKER_TOP_FRONT_CENTER
        }
        if ((channelLayout & 0x0100) == 0x0100) {
            //0100h Over the listener’s head 1
            numChannels += 1;
            dwChannelMask |= 0x00000800; //SPEAKER_TOP_CENTER
        }
        if ((channelLayout & 0x0200) == 0x0200) {
            //0200h Between left/right and center in front 2
            numChannels += 2;
            dwChannelMask |= 0x00000040; //SPEAKER_FRONT_LEFT_OF_CENTER
            dwChannelMask |= 0x00000080; //SPEAKER_FRONT_RIGHT_OF_CENTER
        }
        if ((channelLayout & 0x0400) == 0x0400) {
            //0400h Left/Right on side in front 2
            numChannels += 2;
            dwChannelMask |= 0x00000200; //SPEAKER_SIDE_LEFT
            dwChannelMask |= 0x00000400; //SPEAKER_SIDE_RIGHT
        }
        if ((channelLayout & 0x0800) == 0x0800) {
            //0800h Left/Right surround on side 2
            numChannels += 2;
            //* if Lss, Rss exist, then this position is equivalent to Lsr, Rsr respectively
            dwChannelMask |= 0x00000010; //SPEAKER_BACK_LEFT
            dwChannelMask |= 0x00000020; //SPEAKER_BACK_RIGHT
        }
        if ((channelLayout & 0x1000) == 0x1000) {
            //1000h Second low frequency effects subwoofer 1
            numChannels += 1;
            dwChannelMask |= 0x00000008; //SPEAKER_LOW_FREQUENCY
        }
        if ((channelLayout & 0x2000) == 0x2000) {
            //2000h Left/Right height on side 2
            numChannels += 2;
            dwChannelMask |= 0x00000010; //SPEAKER_BACK_LEFT
            dwChannelMask |= 0x00000020; //SPEAKER_BACK_RIGHT
        }
        if ((channelLayout & 0x4000) == 0x4000) {
            //4000h Center height in rear 1
            numChannels += 1;
            dwChannelMask |= 0x00010000; //SPEAKER_TOP_BACK_CENTER
        }
        if ((channelLayout & 0x8000) == 0x8000) {
            //8000h Left/Right height in rear 2
            numChannels += 2;
            dwChannelMask |= 0x00008000; //SPEAKER_TOP_BACK_LEFT
            dwChannelMask |= 0x00020000; //SPEAKER_TOP_BACK_RIGHT
        }
        if ((channelLayout & 0x10000) == 0x10000) {
            //10000h Center below in front
            numChannels += 1;
        }
        if ((channelLayout & 0x20000) == 0x20000) {
            //20000h Left/Right below in front
            numChannels += 2;
        }
        return new int[]{numChannels, dwChannelMask};
    }

    private String getAudioCodecPrivateData(AudioSpecificConfig audioSpecificConfig) {
        byte[] configByteArray = audioSpecificConfig.getConfigBytes();
        return Hex.encodeHex(configByteArray);
    }

    private VideoQuality getVideoQuality(Track track, VisualSampleEntry vse) {
        VideoQuality l;
        if ("avc1".equals(getFormat(vse))) {
            AvcConfigurationBox avcConfigurationBox = vse.getBoxes(AvcConfigurationBox.class).get(0);
            l = new VideoQuality();
            l.bitrate = getBitrate(track);
            l.codecPrivateData = Hex.encodeHex(getAvcCodecPrivateData(avcConfigurationBox));
            l.fourCC = "AVC1";
            l.width = vse.getWidth();
            l.height = vse.getHeight();
            l.nalLength = avcConfigurationBox.getLengthSizeMinusOne() + 1;
        } else {
            throw new InternalError("I don't know how to handle video of type " + getFormat(vse));
        }
        return l;
    }

    private byte[] getAvcCodecPrivateData(AvcConfigurationBox avcConfigurationBox) {
        List<byte[]> sps = avcConfigurationBox.getSequenceParameterSets();
        List<byte[]> pps = avcConfigurationBox.getPictureParameterSets();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write(new byte[]{0, 0, 0, 1});

            for (byte[] sp : sps) {
                baos.write(sp);
            }
            baos.write(new byte[]{0, 0, 0, 1});
            for (byte[] pp : pps) {
                baos.write(pp);
            }
        } catch (IOException ex) {
            throw new RuntimeException("ByteArrayOutputStream do not throw IOException ?!?!?");
        }
        return baos.toByteArray();
    }

    private class DependentSubstreamMask {
        private byte dWChannelMaskFirstByte;
        private byte dWChannelMaskSecondByte;
        private EC3SpecificBox.Entry entry;

        public DependentSubstreamMask(byte dWChannelMaskFirstByte, byte dWChannelMaskSecondByte, EC3SpecificBox.Entry entry) {
            this.dWChannelMaskFirstByte = dWChannelMaskFirstByte;
            this.dWChannelMaskSecondByte = dWChannelMaskSecondByte;
            this.entry = entry;
        }

        public byte getdWChannelMaskFirstByte() {
            return dWChannelMaskFirstByte;
        }

        public byte getdWChannelMaskSecondByte() {
            return dWChannelMaskSecondByte;
        }

        public DependentSubstreamMask process() {
            switch (entry.chan_loc) {
                case 0:
                    dWChannelMaskFirstByte |= 0x3;
                    break;
                case 1:
                    dWChannelMaskFirstByte |= 0xC;
                    break;
                case 2:
                    dWChannelMaskSecondByte |= 0x80;
                    break;
                case 3:
                    dWChannelMaskSecondByte |= 0x8;
                    break;
                case 6:
                    dWChannelMaskSecondByte |= 0x5;
                    break;
                case 7:
                    dWChannelMaskSecondByte |= 0x2;
                    break;
            }
            return this;
        }
    }
}
