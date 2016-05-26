package net.ossrs.yasea;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import com.coremedia.iso.BoxParser;
import com.coremedia.iso.IsoFile;
import com.coremedia.iso.IsoTypeWriter;
import com.coremedia.iso.boxes.AbstractMediaHeaderBox;
import com.coremedia.iso.boxes.Box;
import com.coremedia.iso.boxes.ContainerBox;
import com.coremedia.iso.boxes.DataEntryUrlBox;
import com.coremedia.iso.boxes.DataInformationBox;
import com.coremedia.iso.boxes.DataReferenceBox;
import com.coremedia.iso.boxes.FileTypeBox;
import com.coremedia.iso.boxes.HandlerBox;
import com.coremedia.iso.boxes.MediaBox;
import com.coremedia.iso.boxes.MediaHeaderBox;
import com.coremedia.iso.boxes.MediaInformationBox;
import com.coremedia.iso.boxes.MovieBox;
import com.coremedia.iso.boxes.MovieHeaderBox;
import com.coremedia.iso.boxes.SampleDescriptionBox;
import com.coremedia.iso.boxes.SampleSizeBox;
import com.coremedia.iso.boxes.SampleTableBox;
import com.coremedia.iso.boxes.SampleToChunkBox;
import com.coremedia.iso.boxes.SoundMediaHeaderBox;
import com.coremedia.iso.boxes.StaticChunkOffsetBox;
import com.coremedia.iso.boxes.SyncSampleBox;
import com.coremedia.iso.boxes.TimeToSampleBox;
import com.coremedia.iso.boxes.TrackBox;
import com.coremedia.iso.boxes.TrackHeaderBox;
import com.coremedia.iso.boxes.VideoMediaHeaderBox;
import com.coremedia.iso.boxes.h264.AvcConfigurationBox;
import com.coremedia.iso.boxes.sampleentry.AudioSampleEntry;
import com.coremedia.iso.boxes.sampleentry.VisualSampleEntry;
import com.googlecode.mp4parser.boxes.mp4.ESDescriptorBox;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.AudioSpecificConfig;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.DecoderConfigDescriptor;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.ESDescriptor;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.SLConfigDescriptor;
import com.googlecode.mp4parser.util.Matrix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by LeoMa on 2016/5/21.
 */
public class SrsMp4Muxer {

    private static final String TAG = "SrsMp4Muxer";
    private static final int VIDEO_TRACK = 100;
    private static final int AUDIO_TRACK = 101;

    private MediaFormat videoFormat = null;
    private MediaFormat audioFormat = null;

    private SrsUtils utils = new SrsUtils();
    private SrsRawH264Stream avc = new SrsRawH264Stream();
    private byte[] aac_specific_config = null;
    private int achannel;
    private int asample_rate;
    private Mp4Movie mp4Movie = new Mp4Movie();

    private boolean h264_sps_changed = false;
    private boolean h264_pps_changed = false;
    private byte[] h264_sps = new byte[0];
    private byte[] h264_pps = new byte[0];
    private ArrayList<byte[]> spsList = new ArrayList<>();
    private ArrayList<byte[]> ppsList = new ArrayList<>();

    private EventHandler mHandler;

    private File mRecFile;

    private Thread worker;
    private final Object writeLock = new Object();
    private final Object moovLock = new Object();
    private ConcurrentLinkedQueue<SrsEsFrame> frameCache = new ConcurrentLinkedQueue<>();

    private static Map<Integer, Integer> samplingFrequencyIndexMap = new HashMap<>();

    static {
        samplingFrequencyIndexMap.put(96000, 0x0);
        samplingFrequencyIndexMap.put(88200, 0x1);
        samplingFrequencyIndexMap.put(64000, 0x2);
        samplingFrequencyIndexMap.put(48000, 0x3);
        samplingFrequencyIndexMap.put(44100, 0x4);
        samplingFrequencyIndexMap.put(32000, 0x5);
        samplingFrequencyIndexMap.put(24000, 0x6);
        samplingFrequencyIndexMap.put(22050, 0x7);
        samplingFrequencyIndexMap.put(16000, 0x8);
        samplingFrequencyIndexMap.put(12000, 0x9);
        samplingFrequencyIndexMap.put(11025, 0xa);
        samplingFrequencyIndexMap.put(8000, 0xb);
    }

    public SrsMp4Muxer(EventHandler handler) {
        mHandler = handler;
    }

    /**
     * MP4 recording event handler.
     */
    interface EventHandler {

        void onVideoTrackBuilt(String msg);

        void onAudioTrackBuilt(String msg);

        void onVideoRecording(String msg);

        void onAudioRecording(String msg);

        void onRecordStarted(String msg);

        void onRecordFinished(String msg);
    }
    
    /**
     * start recording.
     */
    public void start(File outputFile) throws IOException {
        mRecFile = outputFile;
        createMovie(mRecFile);
        mHandler.onRecordStarted(mRecFile.getPath());

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                // Wait for moov box.
                synchronized (moovLock) {
                    try {
                        moovLock.wait();
                    } catch (InterruptedException ie) {
                        // do nothing
                    }
                }

                while (!Thread.interrupted()) {
                    // Keep at least one audio and video frame in cache to ensure monotonically increasing.
                    while (!frameCache.isEmpty()) {
                        SrsEsFrame frame = frameCache.poll();
                        try {
                            if (frame.is_video()) {
                                writeSampleData(VIDEO_TRACK, frame.bb, frame.bi, false);
                                mHandler.onVideoRecording("Video");
                            } else if (frame.is_audio()) {
                                writeSampleData(AUDIO_TRACK, frame.bb, frame.bi, true);
                                mHandler.onAudioRecording("Audio");
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    // Waiting for next frame
                    synchronized (writeLock) {
                        try {
                            // isEmpty() may take some time, so we set timeout to detect next frame
                            writeLock.wait(500);
                        } catch (InterruptedException ie) {
                            worker.interrupt();
                        }
                    }
                }
            }
        });
        worker.start();
    }

    /**
     * finish recording.
     */
    public void stop() {
        if (worker != null) {

            worker.interrupt();
            try {
                worker.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
                worker.interrupt();
            }
            worker = null;

            try {
                finishMovie();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mHandler.onRecordFinished(mRecFile.getPath());
        }

        Log.i(TAG, String.format("SrsMp4Muxer closed"));
    }

    /**
     * E.4.1 FLV Tag, page 75
     */
    class SrsCodecFlvTag
    {
        // set to the zero to reserved, for array map.
        public final static int Reserved = 0;

        // 8 = audio
        public final static int Audio = 8;
        // 9 = video
        public final static int Video = 9;
        // 18 = script data
        public final static int Script = 18;
    }

    /**
     * the FLV/RTMP supported audio sample rate.
     * Sampling rate. The following values are defined:
     * 0 = 5.5 kHz = 5512 Hz
     * 1 = 11 kHz = 11025 Hz
     * 2 = 22 kHz = 22050 Hz
     * 3 = 44 kHz = 44100 Hz
     */
    class SrsCodecAudioSampleRate
    {
        // set to the max value to reserved, for array map.
        public final static int Reserved                 = 4;

        public final static int R5512                     = 0;
        public final static int R11025                    = 1;
        public final static int R22050                    = 2;
        public final static int R44100                    = 3;
    }

    /**
     * Table 7-1 – NAL unit type codes, syntax element categories, and NAL unit type classes
     * H.264-AVC-ISO_IEC_14496-10-2012.pdf, page 83.
     */
    class SrsAvcNaluType
    {
        // Unspecified
        public final static int Reserved = 0;

        // Coded slice of a non-IDR picture slice_layer_without_partitioning_rbsp( )
        public final static int NonIDR = 1;
        // Coded slice data partition A slice_data_partition_a_layer_rbsp( )
        public final static int DataPartitionA = 2;
        // Coded slice data partition B slice_data_partition_b_layer_rbsp( )
        public final static int DataPartitionB = 3;
        // Coded slice data partition C slice_data_partition_c_layer_rbsp( )
        public final static int DataPartitionC = 4;
        // Coded slice of an IDR picture slice_layer_without_partitioning_rbsp( )
        public final static int IDR = 5;
        // Supplemental enhancement information (SEI) sei_rbsp( )
        public final static int SEI = 6;
        // Sequence parameter set seq_parameter_set_rbsp( )
        public final static int SPS = 7;
        // Picture parameter set pic_parameter_set_rbsp( )
        public final static int PPS = 8;
        // Access unit delimiter access_unit_delimiter_rbsp( )
        public final static int AccessUnitDelimiter = 9;
        // End of sequence end_of_seq_rbsp( )
        public final static int EOSequence = 10;
        // End of stream end_of_stream_rbsp( )
        public final static int EOStream = 11;
        // Filler data filler_data_rbsp( )
        public final static int FilterData = 12;
        // Sequence parameter set extension seq_parameter_set_extension_rbsp( )
        public final static int SPSExt = 13;
        // Prefix NAL unit prefix_nal_unit_rbsp( )
        public final static int PrefixNALU = 14;
        // Subset sequence parameter set subset_seq_parameter_set_rbsp( )
        public final static int SubsetSPS = 15;
        // Coded slice of an auxiliary coded picture without partitioning slice_layer_without_partitioning_rbsp( )
        public final static int LayerWithoutPartition = 19;
        // Coded slice extension slice_layer_extension_rbsp( )
        public final static int CodedSliceExt = 20;
    }

    /**
     * Adds a track with the specified format.
     *
     * @param format The media format for the track.
     * @return The track index for this newly added track.
     */
    public int addTrack(MediaFormat format) {
        if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC) {
            videoFormat = format;
            return VIDEO_TRACK;
        } else {
            achannel = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            asample_rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            audioFormat = format;
            return AUDIO_TRACK;
        }
    }

    /**
     * send the annexb frame to SRS over RTMP.
     *
     * @param trackIndex The track index for this sample.
     * @param byteBuf    The encoded sample.
     * @param bufferInfo The buffer information related to this sample.
     */
    public void writeSampleData(int trackIndex, ByteBuffer byteBuf, MediaCodec.BufferInfo bufferInfo) throws IllegalArgumentException {
        if (VIDEO_TRACK == trackIndex) {
            writeVideoSample(byteBuf, bufferInfo);
        } else {
            writeAudioSample(byteBuf, bufferInfo);
        }
    }

    public void writeVideoSample(final ByteBuffer bb, MediaCodec.BufferInfo bi) throws IllegalArgumentException {
        int nal_unit_type = bb.get(4) & 0x1f;
        if (nal_unit_type == SrsAvcNaluType.IDR || nal_unit_type == SrsAvcNaluType.NonIDR) {
            writeFrameByte(SrsCodecFlvTag.Video, bb, bi);
        } else {
            while (bb.position() < bi.size) {
                SrsEsFrameBytes frame = avc.annexb_demux(bb, bi);
                // for sps
                if (avc.is_sps(frame)) {
                    byte[] sps = new byte[frame.size];
                    frame.data.get(sps);
                    if (utils.srs_bytes_equals(h264_sps, sps)) {
                        continue;
                    }
                    h264_sps_changed = true;
                    h264_sps = sps;
                    spsList.add(sps);
                    continue;
                }

                // for pps
                if (avc.is_pps(frame)) {
                    byte[] pps = new byte[frame.size];
                    frame.data.get(pps);
                    if (utils.srs_bytes_equals(h264_pps, pps)) {
                        continue;
                    }
                    h264_pps_changed = true;
                    h264_pps = pps;
                    ppsList.add(pps);
                    continue;
                }
            }

            if (h264_sps_changed && h264_pps_changed) {
                h264_sps_changed = false;
                h264_pps_changed = false;
                mp4Movie.addTrack(videoFormat, false);
            }
        }
    }

    public void writeAudioSample(final ByteBuffer bb, MediaCodec.BufferInfo bi) {
        byte[] frame = new byte[bi.size + 2];
        byte aac_packet_type = 1; // 1 = AAC raw
        if (aac_specific_config == null) {
            frame = new byte[4];

            // @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf
            // AudioSpecificConfig (), page 33
            // 1.6.2.1 AudioSpecificConfig
            // audioObjectType; 5 bslbf
            byte ch = (byte) (bb.get(0) & 0xf8);
            // 3bits left.

            // samplingFrequencyIndex; 4 bslbf
            byte samplingFrequencyIndex = 0x04;
            if (asample_rate == SrsCodecAudioSampleRate.R22050) {
                samplingFrequencyIndex = 0x07;
            } else if (asample_rate == SrsCodecAudioSampleRate.R11025) {
                samplingFrequencyIndex = 0x0a;
            }
            ch |= (samplingFrequencyIndex >> 1) & 0x07;
            frame[2] = ch;

            ch = (byte) ((samplingFrequencyIndex << 7) & 0x80);
            // 7bits left.

            // channelConfiguration; 4 bslbf
            byte channelConfiguration = 1;
            if (achannel == 2) {
                channelConfiguration = 2;
            }
            ch |= (channelConfiguration << 3) & 0x78;
            // 3bits left.

            // GASpecificConfig(), page 451
            // 4.4.1 Decoder configuration (GASpecificConfig)
            // frameLengthFlag; 1 bslbf
            // dependsOnCoreCoder; 1 bslbf
            // extensionFlag; 1 bslbf
            frame[3] = ch;

            aac_specific_config = frame;
            aac_packet_type = 0; // 0 = AAC sequence header
            mp4Movie.addTrack(audioFormat, true);
        } else {
            writeFrameByte(SrsCodecFlvTag.Audio, bb, bi);
            //bb.get(frame, 2, frame.length - 2);
        }

//        byte sound_format = 10; // AAC
//        byte sound_type = 0; // 0 = Mono sound
//        if (achannel == 2) {
//            sound_type = 1; // 1 = Stereo sound
//        }
//        byte sound_size = 1; // 1 = 16-bit samples
//        byte sound_rate = 3; // 44100, 22050, 11025
//        if (asample_rate == 22050) {
//            sound_rate = 2;
//        } else if (asample_rate == 11025) {
//            sound_rate = 1;
//        }

        // for audio frame, there is 1 or 2 bytes header:
        //      1bytes, SoundFormat|SoundRate|SoundSize|SoundType
        //      1bytes, AACPacketType for SoundFormat == 10, 0 is sequence header.
//        byte audio_header = (byte) (sound_type & 0x01);
//        audio_header |= (sound_size << 1) & 0x02;
//        audio_header |= (sound_rate << 2) & 0x0c;
//        audio_header |= (sound_format << 4) & 0xf0;
//
//        frame[0] = audio_header;
//        frame[1] = aac_packet_type;

        //SrsEsFrameBytes tag = new SrsEsFrameBytes();
        //tag.frame = ByteBuffer.wrap(frame);
        //tag.size = frame.length;

        //writeFrameByte(SrsCodecFlvTag.Audio, dts, 0, aac_packet_type, tag);
    }

    private void writeFrameByte(int type, ByteBuffer bb, MediaCodec.BufferInfo bi) {
        SrsEsFrame frame = new SrsEsFrame();
        frame.bb = bb;
        frame.bi = bi;
        frame.type = type;

        frameCache.add(frame);
        synchronized (writeLock) {
            writeLock.notifyAll();
        }
    }

    /**
     * utils functions from srs.
     */
    public class SrsUtils {
        public boolean srs_bytes_equals(byte[] a, byte[] b) {
            if ((a == null || b == null) && (a != null || b != null)) {
                return false;
            }

            if (a.length != b.length) {
                return false;
            }

            for (int i = 0; i < a.length && i < b.length; i++) {
                if (a[i] != b[i]) {
                    return false;
                }
            }

            return true;
        }

        public SrsAnnexbSearch srs_avc_startswith_annexb(ByteBuffer bb, MediaCodec.BufferInfo bi) {
            SrsAnnexbSearch as = new SrsAnnexbSearch();
            as.match = false;

            int pos = bb.position();
            while (pos < bi.size - 3) {
                // not match.
                if (bb.get(pos) != 0x00 || bb.get(pos + 1) != 0x00) {
                    break;
                }

                // match N[00] 00 00 01, where N>=0
                if (bb.get(pos + 2) == 0x01) {
                    as.match = true;
                    as.nb_start_code = pos + 3 - bb.position();
                    break;
                }

                pos++;
            }

            return as;
        }

        public boolean srs_aac_startswith_adts(ByteBuffer bb, MediaCodec.BufferInfo bi) {
            int pos = bb.position();
            if (bi.size - pos < 2) {
                return false;
            }

            // matched 12bits 0xFFF,
            // @remark, we must cast the 0xff to char to compare.
            if (bb.get(pos) != (byte) 0xff || (byte) (bb.get(pos + 1) & 0xf0) != (byte) 0xf0) {
                return false;
            }

            return true;
        }
    }

    /**
     * the search result for annexb.
     */
    class SrsAnnexbSearch {
        public int nb_start_code = 0;
        public boolean match = false;
    }

    /**
     * the demuxed tag frame.
     */
    class SrsEsFrameBytes {
        public ByteBuffer data;
        public int size;
    }

    /**
     * the AV frame.
     */
    class SrsEsFrame {
        public ByteBuffer bb;
        public MediaCodec.BufferInfo bi;
        public int type;

        public boolean is_video() {
            return type == SrsCodecFlvTag.Video;
        }

        public boolean is_audio() {
            return type == SrsCodecFlvTag.Audio;
        }
    }

    /**
     * the raw h.264 stream, in annexb.
     */
    class SrsRawH264Stream {
        private SrsUtils utils;

        public SrsRawH264Stream() {
            utils = new SrsUtils();
        }

        public boolean is_sps(SrsEsFrameBytes frame) {
            if (frame.size < 1) {
                return false;
            }

            // 5bits, 7.3.1 NAL unit syntax,
            // H.264-AVC-ISO_IEC_14496-10.pdf, page 44.
            //  7: SPS, 8: PPS, 5: I Frame, 1: P Frame
            int nal_unit_type = (int) (frame.data.get(0) & 0x1f);

            return nal_unit_type == SrsAvcNaluType.SPS;
        }

        public boolean is_pps(SrsEsFrameBytes frame) {
            if (frame.size < 1) {
                return false;
            }

            // 5bits, 7.3.1 NAL unit syntax,
            // H.264-AVC-ISO_IEC_14496-10.pdf, page 44.
            //  7: SPS, 8: PPS, 5: I Frame, 1: P Frame
            int nal_unit_type = (int) (frame.data.get(0) & 0x1f);

            return nal_unit_type == SrsAvcNaluType.PPS;
        }

        public SrsEsFrameBytes annexb_demux(ByteBuffer bb, MediaCodec.BufferInfo bi) throws IllegalArgumentException {
            SrsEsFrameBytes tbb = new SrsEsFrameBytes();

            while (bb.position() < bi.size) {
                // each frame must prefixed by annexb format.
                // about annexb, @see H.264-AVC-ISO_IEC_14496-10.pdf, page 211.
                SrsAnnexbSearch tbbsc = utils.srs_avc_startswith_annexb(bb, bi);
                if (!tbbsc.match || tbbsc.nb_start_code < 3) {
                    Log.e(TAG, "annexb not match.");
                    throw new IllegalArgumentException(String.format("annexb not match for %dB, pos=%d", bi.size, bb.position()));
                }

                // the start codes.
                ByteBuffer tbbs = bb.slice();
                for (int i = 0; i < tbbsc.nb_start_code; i++) {
                    bb.get();
                }

                // find out the frame size.
                tbb.data = bb.slice();
                int pos = bb.position();
                while (bb.position() < bi.size) {
                    SrsAnnexbSearch bsc = utils.srs_avc_startswith_annexb(bb, bi);
                    if (bsc.match) {
                        break;
                    }
                    bb.get();
                }

                tbb.size = bb.position() - pos;
                if (bb.position() < bi.size) {
                    Log.i(TAG, String.format("annexb multiple match ok, pts=%d", bi.presentationTimeUs / 1000));
                }
                //Log.i(TAG, String.format("annexb match %d bytes", tbb.size));
                break;
            }

            return tbb;
        }
    }

    public class Sample {
        private long offset = 0;
        private long size = 0;

        public Sample(long offset, long size) {
            this.offset = offset;
            this.size = size;
        }

        public long getOffset() {
            return offset;
        }

        public long getSize() {
            return size;
        }
    }

    public class Track {
        private int trackId = 0;
        private ArrayList<Sample> samples = new ArrayList<>();
        private long duration = 0;
        private String handler;
        private AbstractMediaHeaderBox headerBox = null;
        private SampleDescriptionBox sampleDescriptionBox = null;
        private LinkedList<Integer> syncSamples = null;
        private int timeScale;
        private Date creationTime = new Date();
        private int height;
        private int width;
        private float volume = 0;
        private ArrayList<Long> sampleDurations = new ArrayList<>();
        private boolean isAudio = false;
        private long lastPresentationTimeUs = 0;
        private boolean first = true;

        public Track(int id, MediaFormat format, boolean audio) {
            trackId = id;
            isAudio = audio;
            if (!isAudio) {
                sampleDurations.add((long) 3015);
                duration = 3015;
                width = format.getInteger(MediaFormat.KEY_WIDTH);
                height = format.getInteger(MediaFormat.KEY_HEIGHT);
                timeScale = 90000;
                syncSamples = new LinkedList<>();
                handler = "vide";
                headerBox = new VideoMediaHeaderBox();
                sampleDescriptionBox = new SampleDescriptionBox();
                if (format.getString(MediaFormat.KEY_MIME).equals("video/avc")) {
                    VisualSampleEntry visualSampleEntry = new VisualSampleEntry("avc1");
                    visualSampleEntry.setDataReferenceIndex(1);
                    visualSampleEntry.setDepth(24);
                    visualSampleEntry.setFrameCount(1);
                    visualSampleEntry.setHorizresolution(72);
                    visualSampleEntry.setVertresolution(72);
                    visualSampleEntry.setWidth(width);
                    visualSampleEntry.setHeight(height);
                    visualSampleEntry.setCompressorname("AVC Coding");

                    AvcConfigurationBox avcConfigurationBox = new AvcConfigurationBox();
                    avcConfigurationBox.setConfigurationVersion(1);
                    avcConfigurationBox.setAvcProfileIndication((int) h264_sps[1]);
                    avcConfigurationBox.setProfileCompatibility(0);
                    avcConfigurationBox.setAvcLevelIndication((int) h264_sps[3]);
                    avcConfigurationBox.setLengthSizeMinusOne(3);
                    avcConfigurationBox.setSequenceParameterSets(spsList);
                    avcConfigurationBox.setPictureParameterSets(ppsList);
                    avcConfigurationBox.setBitDepthLumaMinus8(-1);
                    avcConfigurationBox.setBitDepthChromaMinus8(-1);
                    avcConfigurationBox.setChromaFormat(-1);
                    avcConfigurationBox.setHasExts(false);

                    visualSampleEntry.addBox(avcConfigurationBox);
                    sampleDescriptionBox.addBox(visualSampleEntry);
                }
            } else {
                sampleDurations.add((long) 1024);
                duration = 1024;
                volume = 1;
                timeScale = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                handler = "soun";
                headerBox = new SoundMediaHeaderBox();
                sampleDescriptionBox = new SampleDescriptionBox();
                AudioSampleEntry audioSampleEntry = new AudioSampleEntry("mp4a");
                audioSampleEntry.setChannelCount(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                audioSampleEntry.setSampleRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
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
                decoderConfigDescriptor.setBufferSizeDB(1536);
                decoderConfigDescriptor.setMaxBitRate(96000);
                decoderConfigDescriptor.setAvgBitRate(96000);

                AudioSpecificConfig audioSpecificConfig = new AudioSpecificConfig();
                audioSpecificConfig.setAudioObjectType(2);
                audioSpecificConfig.setSamplingFrequencyIndex(samplingFrequencyIndexMap.get((int) audioSampleEntry.getSampleRate()));
                audioSpecificConfig.setChannelConfiguration(audioSampleEntry.getChannelCount());
                decoderConfigDescriptor.setAudioSpecificInfo(audioSpecificConfig);

                descriptor.setDecoderConfigDescriptor(decoderConfigDescriptor);

                ByteBuffer data = descriptor.serialize();
                esds.setEsDescriptor(descriptor);
                esds.setData(data);
                audioSampleEntry.addBox(esds);
                sampleDescriptionBox.addBox(audioSampleEntry);
            }
        }

        public void addSample(long offset, MediaCodec.BufferInfo bufferInfo) {
            long delta = bufferInfo.presentationTimeUs - lastPresentationTimeUs;
            if (delta < 0) {
                return;
            }
            boolean isSyncFrame = !isAudio && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
            samples.add(new Sample(offset, bufferInfo.size));
            if (syncSamples != null && isSyncFrame) {
                syncSamples.add(samples.size());
            }

            delta = (delta * timeScale + 500000L) / 1000000L;
            lastPresentationTimeUs = bufferInfo.presentationTimeUs;
            if (!first) {
                sampleDurations.add(sampleDurations.size() - 1, delta);
                duration += delta;
            }
            first = false;
        }

        public ArrayList<Sample> getSamples() {
            return samples;
        }

        public long getDuration() {
            return duration;
        }

        public String getHandler() {
            return handler;
        }

        public AbstractMediaHeaderBox getMediaHeaderBox() {
            return headerBox;
        }

        public SampleDescriptionBox getSampleDescriptionBox() {
            return sampleDescriptionBox;
        }

        public long[] getSyncSamples() {
            if (syncSamples == null || syncSamples.isEmpty()) {
                return null;
            }
            long[] returns = new long[syncSamples.size()];
            for (int i = 0; i < syncSamples.size(); i++) {
                returns[i] = syncSamples.get(i);
            }
            return returns;
        }

        public int getTimeScale() {
            return timeScale;
        }

        public Date getCreationTime() {
            return creationTime;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public float getVolume() {
            return volume;
        }

        public ArrayList<Long> getSampleDurations() {
            return sampleDurations;
        }

        public boolean isAudio() {
            return isAudio;
        }

        public int getTrackId() {
            return trackId;
        }
    }

    public class Mp4Movie {
        private Matrix matrix = Matrix.ROTATE_0;
        private HashMap<Integer, Track> tracks = new HashMap<>();

        public Matrix getMatrix() {
            return matrix;
        }

        public void setRotation(int angle) {
            if (angle == 0) {
                matrix = Matrix.ROTATE_0;
            } else if (angle == 90) {
                matrix = Matrix.ROTATE_90;
            } else if (angle == 180) {
                matrix = Matrix.ROTATE_180;
            } else if (angle == 270) {
                matrix = Matrix.ROTATE_270;
            }
        }

        public HashMap<Integer, Track> getTracks() {
            return tracks;
        }

        public void addSample(int trackIndex, long offset, MediaCodec.BufferInfo bufferInfo) {
            Track track = tracks.get(trackIndex);
            track.addSample(offset, bufferInfo);
        }

        public void addTrack(MediaFormat format, boolean isAudio) {
            if (isAudio) {
                tracks.put(AUDIO_TRACK, new Track(tracks.size(), format, true));
                mHandler.onAudioTrackBuilt("AAC specific configuration got");
            } else {
                tracks.put(VIDEO_TRACK, new Track(tracks.size(), format, false));
                mHandler.onVideoTrackBuilt("H.264 SPS PPS got");

                for (Track track : getTracks().values()) {
                    List<Sample> samples = track.getSamples();
                    long[] sizes = new long[samples.size()];
                    for (int i = 0; i < sizes.length; i++) {
                        sizes[i] = samples.get(i).getSize();
                    }
                    track2SampleSizes.put(track, sizes);
                }

                Box moov = createMovieBox(this);
                try {
                    moov.getBox(fc);
                    fos.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                synchronized (moovLock) {
                    moovLock.notifyAll();
                }
            }
        }
    }

    private InterleaveChunkMdat mdat = null;
    private FileOutputStream fos = null;
    private FileChannel fc = null;
    private long dataOffset = 0;
    private long writedSinceLastMdat = 0;
    private boolean writeNewMdat = true;
    private HashMap<Track, long[]> track2SampleSizes = new HashMap<>();
    private ByteBuffer sizeBuffer = null;

    private void createMovie(File outputFile) throws IOException {
        fos = new FileOutputStream(outputFile);
        fc = fos.getChannel();

        FileTypeBox fileTypeBox = createFileTypeBox();
        fileTypeBox.getBox(fc);
        dataOffset += fileTypeBox.getSize();
        writedSinceLastMdat += dataOffset;

        mdat = new InterleaveChunkMdat();

        sizeBuffer = ByteBuffer.allocateDirect(4);
    }

    private void flushCurrentMdat() throws IOException {
        long oldPosition = fc.position();
        fc.position(mdat.getOffset());
        mdat.getBox(fc);
        fc.position(oldPosition);
        mdat.setDataOffset(0);
        mdat.setContentSize(0);
        fos.flush();
    }

    private boolean writeSampleData(int trackIndex, ByteBuffer byteBuf, MediaCodec.BufferInfo bufferInfo, boolean isAudio) throws IOException {
        if (writeNewMdat) {
            mdat.setContentSize(0);
            mdat.getBox(fc);
            mdat.setDataOffset(dataOffset);
            dataOffset += 16;
            writedSinceLastMdat += 16;
            writeNewMdat = false;
        }

        mdat.setContentSize(mdat.getContentSize() + bufferInfo.size);
        writedSinceLastMdat += bufferInfo.size;

        boolean flush = false;
        if (writedSinceLastMdat >= 32 * 1024) {
            flushCurrentMdat();
            writeNewMdat = true;
            flush = true;
            writedSinceLastMdat -= 32 * 1024;
        }

        mp4Movie.addSample(trackIndex, dataOffset, bufferInfo);
        byteBuf.position(bufferInfo.offset + (isAudio ? 0 : 4));
        byteBuf.limit(bufferInfo.offset + bufferInfo.size);

        if (!isAudio) {
            sizeBuffer.position(0);
            sizeBuffer.putInt(bufferInfo.size - 4);
            sizeBuffer.position(0);
            fc.write(sizeBuffer);
        }

        fc.write(byteBuf);
        dataOffset += bufferInfo.size;

        if (flush) {
            fos.flush();
        }
        return flush;
    }

    private void finishMovie() throws IOException {
        if (mdat.getContentSize() != 0) {
            flushCurrentMdat();
        }

        fc.close();
        fos.close();
    }

    private FileTypeBox createFileTypeBox() {
        LinkedList<String> minorBrands = new LinkedList<>();
        minorBrands.add("isom");
        minorBrands.add("3gp4");
        return new FileTypeBox("isom", 0, minorBrands);
    }

    private class InterleaveChunkMdat implements Box {
        private ContainerBox parent;
        private long contentSize = 1024 * 1024 * 1024;
        private long dataOffset = 0;

        public ContainerBox getParent() {
            return parent;
        }

        public long getOffset() {
            return dataOffset;
        }

        public void setDataOffset(long offset) {
            dataOffset = offset;
        }

        public void setParent(ContainerBox parent) {
            this.parent = parent;
        }

        public void setContentSize(long contentSize) {
            this.contentSize = contentSize;
        }

        public long getContentSize() {
            return contentSize;
        }

        public String getType() {
            return "mdat";
        }

        public long getSize() {
            return 16 + contentSize;
        }

        private boolean isSmallBox(long contentSize) {
            return (contentSize + 8) < 4294967296L;
        }

        public void getBox(WritableByteChannel writableByteChannel) throws IOException {
            ByteBuffer bb = ByteBuffer.allocate(16);
            long size = getSize();
            if (isSmallBox(size)) {
                IsoTypeWriter.writeUInt32(bb, size);
            } else {
                IsoTypeWriter.writeUInt32(bb, 1);
            }
            bb.put(IsoFile.fourCCtoBytes("mdat"));
            if (isSmallBox(size)) {
                bb.put(new byte[8]);
            } else {
                IsoTypeWriter.writeUInt64(bb, size);
            }
            bb.rewind();
            writableByteChannel.write(bb);
        }

        @Override
        public void parse(ReadableByteChannel readableByteChannel, ByteBuffer header, long contentSize, BoxParser boxParser) throws IOException {
        }
    }

    private static long gcd(long a, long b) {
        if (b == 0) {
            return a;
        }
        return gcd(b, a % b);
    }

    private long getTimescale(Mp4Movie mp4Movie) {
        long timescale = 0;
        if (!mp4Movie.getTracks().isEmpty()) {
            timescale = mp4Movie.getTracks().values().iterator().next().getTimeScale();
        }
        for (Track track : mp4Movie.getTracks().values()) {
            timescale = gcd(track.getTimeScale(), timescale);
        }
        return timescale;
    }

    private MovieBox createMovieBox(Mp4Movie movie) {
        MovieBox movieBox = new MovieBox();
        MovieHeaderBox mvhd = new MovieHeaderBox();

        mvhd.setCreationTime(new Date());
        mvhd.setModificationTime(new Date());
        mvhd.setMatrix(Matrix.ROTATE_0);
        long movieTimeScale = getTimescale(movie);
        long duration = 0;

        for (Track track : movie.getTracks().values()) {
            long tracksDuration = track.getDuration() * movieTimeScale / track.getTimeScale();
            if (tracksDuration > duration) {
                duration = tracksDuration;
            }
        }

        mvhd.setDuration(duration);
        mvhd.setTimescale(movieTimeScale);
        mvhd.setNextTrackId(movie.getTracks().size() + 1);

        movieBox.addBox(mvhd);
        for (Track track : movie.getTracks().values()) {
            movieBox.addBox(createTrackBox(track, movie));
        }
        return movieBox;
    }

    private TrackBox createTrackBox(Track track, Mp4Movie movie) {
        TrackBox trackBox = new TrackBox();
        TrackHeaderBox tkhd = new TrackHeaderBox();

        tkhd.setEnabled(true);
        tkhd.setInMovie(true);
        tkhd.setInPreview(true);
        if (track.isAudio()) {
            tkhd.setMatrix(Matrix.ROTATE_0);
        } else {
            tkhd.setMatrix(movie.getMatrix());
        }
        tkhd.setAlternateGroup(0);
        tkhd.setCreationTime(track.getCreationTime());
        tkhd.setModificationTime(track.getCreationTime());
        tkhd.setDuration(track.getDuration() * getTimescale(movie) / track.getTimeScale());
        tkhd.setHeight(track.getHeight());
        tkhd.setWidth(track.getWidth());
        tkhd.setLayer(0);
        tkhd.setModificationTime(new Date());
        tkhd.setTrackId(track.getTrackId() + 1);
        tkhd.setVolume(track.getVolume());

        trackBox.addBox(tkhd);

        MediaBox mdia = new MediaBox();
        trackBox.addBox(mdia);
        MediaHeaderBox mdhd = new MediaHeaderBox();
        mdhd.setCreationTime(track.getCreationTime());
        mdhd.setModificationTime(track.getCreationTime());
        mdhd.setDuration(track.getDuration());
        mdhd.setTimescale(track.getTimeScale());
        mdhd.setLanguage("eng");
        mdia.addBox(mdhd);
        HandlerBox hdlr = new HandlerBox();
        hdlr.setName(track.isAudio() ? "SoundHandle" : "VideoHandle");
        hdlr.setHandlerType(track.getHandler());

        mdia.addBox(hdlr);

        MediaInformationBox minf = new MediaInformationBox();
        minf.addBox(track.getMediaHeaderBox());

        DataInformationBox dinf = new DataInformationBox();
        DataReferenceBox dref = new DataReferenceBox();
        dinf.addBox(dref);
        DataEntryUrlBox url = new DataEntryUrlBox();
        url.setFlags(1);
        dref.addBox(url);
        minf.addBox(dinf);

        Box stbl = createStbl(track);
        minf.addBox(stbl);
        mdia.addBox(minf);

        return trackBox;
    }

    private Box createStbl(Track track) {
        SampleTableBox stbl = new SampleTableBox();
        createStsd(track, stbl);
        createStts(track, stbl);
        createStss(track, stbl);
        createStsc(track, stbl);
        createStsz(track, stbl);
        createStco(track, stbl);
        return stbl;
    }

    private void createStsd(Track track, SampleTableBox stbl) {
        stbl.addBox(track.getSampleDescriptionBox());
    }

    private void createStts(Track track, SampleTableBox stbl) {
        TimeToSampleBox.Entry lastEntry = null;
        List<TimeToSampleBox.Entry> entries = new ArrayList<>();

        for (long delta : track.getSampleDurations()) {
            if (lastEntry != null && lastEntry.getDelta() == delta) {
                lastEntry.setCount(lastEntry.getCount() + 1);
            } else {
                lastEntry = new TimeToSampleBox.Entry(1, delta);
                entries.add(lastEntry);
            }
        }
        TimeToSampleBox stts = new TimeToSampleBox();
        stts.setEntries(entries);
        stbl.addBox(stts);
    }

    private void createStss(Track track, SampleTableBox stbl) {
        long[] syncSamples = track.getSyncSamples();
        if (syncSamples != null && syncSamples.length > 0) {
            SyncSampleBox stss = new SyncSampleBox();
            stss.setSampleNumber(syncSamples);
            stbl.addBox(stss);
        }
    }

    private void createStsc(Track track, SampleTableBox stbl) {
        SampleToChunkBox stsc = new SampleToChunkBox();
        stsc.setEntries(new LinkedList<SampleToChunkBox.Entry>());

        long lastOffset;
        int lastChunkNumber = 1;
        int lastSampleCount = 0;

        int previousWritedChunkCount = -1;

        int samplesCount = track.getSamples().size();
        for (int a = 0; a < samplesCount; a++) {
            Sample sample = track.getSamples().get(a);
            long offset = sample.getOffset();
            long size = sample.getSize();

            lastOffset = offset + size;
            lastSampleCount++;

            boolean write = false;
            if (a != samplesCount - 1) {
                Sample nextSample = track.getSamples().get(a + 1);
                if (lastOffset != nextSample.getOffset()) {
                    write = true;
                }
            } else {
                write = true;
            }
            if (write) {
                if (previousWritedChunkCount != lastSampleCount) {
                    stsc.getEntries().add(new SampleToChunkBox.Entry(lastChunkNumber, lastSampleCount, 1));
                    previousWritedChunkCount = lastSampleCount;
                }
                lastSampleCount = 0;
                lastChunkNumber++;
            }
        }
        stbl.addBox(stsc);
    }

    private void createStsz(Track track, SampleTableBox stbl) {
        SampleSizeBox stsz = new SampleSizeBox();
        stsz.setSampleSizes(track2SampleSizes.get(track));
        stbl.addBox(stsz);
    }

    private void createStco(Track track, SampleTableBox stbl) {
        ArrayList<Long> chunksOffsets = new ArrayList<>();
        long lastOffset = -1;
        for (Sample sample : track.getSamples()) {
            long offset = sample.getOffset();
            if (lastOffset != -1 && lastOffset != offset) {
                lastOffset = -1;
            }
            if (lastOffset == -1) {
                chunksOffsets.add(offset);
            }
            lastOffset = offset + sample.getSize();
        }
        long[] chunkOffsetsLong = new long[chunksOffsets.size()];
        for (int a = 0; a < chunksOffsets.size(); a++) {
            chunkOffsetsLong[a] = chunksOffsets.get(a);
        }

        StaticChunkOffsetBox stco = new StaticChunkOffsetBox();
        stco.setChunkOffsets(chunkOffsetsLong);
        stbl.addBox(stco);
    }
}
