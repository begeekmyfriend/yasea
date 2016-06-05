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
import com.googlecode.mp4parser.util.Math;
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

    private File mRecFile;
    private EventHandler mHandler;

    private MediaFormat videoFormat = null;
    private MediaFormat audioFormat = null;

    private SrsRawH264Stream avc = new SrsRawH264Stream();
    private Mp4Movie mp4Movie = new Mp4Movie();

    private boolean aacSpecConfig = false;
    private ByteBuffer h264_sps = null;
    private ByteBuffer h264_pps = null;
    private ArrayList<byte[]> spsList = new ArrayList<>();
    private ArrayList<byte[]> ppsList = new ArrayList<>();

    private Thread worker;
    private volatile boolean bRecording = false;
    private volatile boolean bPaused = false;
    private volatile boolean needToFindKeyFrame = true;
    private final Object writeLock = new Object();
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

        void onRecordPause(String msg);

        void onRecordResume(String msg);

        void onRecordStarted(String msg);

        void onRecordFinished(String msg);
    }
    
    /**
     * start recording.
     */
    public void record(File outputFile) throws IOException {
        mRecFile = outputFile;
        createMovie(mRecFile);
        mHandler.onRecordStarted(mRecFile.getPath());

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                bRecording = true;
                while (bRecording) {
                    // Keep at least one audio and video frame in cache to ensure monotonically increasing.
                    while (!frameCache.isEmpty()) {
                        SrsEsFrame frame = frameCache.poll();
                        try {
                            writeSampleData(frame.bb, frame.bi, frame.is_audio());
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
     * pause recording.
     */
    public void pause() {
        if (bRecording) {
            bPaused = true;
            mHandler.onRecordPause("Recording pause");
        }
    }

    /**
     * resume recording.
     */
    public void resume() {
        if (bRecording) {
            bPaused = false;
            needToFindKeyFrame = true;
            mHandler.onRecordResume("Recording resume");
        }
    }

    /**
     * finish recording.
     */
    public void stop() {
        bRecording = false;
        bPaused = false;
        needToFindKeyFrame = false;
        aacSpecConfig = false;

		if (worker != null) {
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
     * Adds a track with the specified format.
     *
     * @param format The media format for the track.
     * @return The track index for this newly added track.
     */
    public int addTrack(MediaFormat format) {
        if (format.getString(MediaFormat.KEY_MIME).contentEquals(SrsEncoder.VCODEC)) {
            videoFormat = format;
            return VIDEO_TRACK;
        } else {
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

    public void writeVideoSample(final ByteBuffer bb, MediaCodec.BufferInfo bi) throws IllegalArgumentException {
        int nal_unit_type = bb.get(4) & 0x1f;
        if (nal_unit_type == SrsAvcNaluType.IDR || nal_unit_type == SrsAvcNaluType.NonIDR) {
            writeFrameByte(VIDEO_TRACK, bb, bi, nal_unit_type == SrsAvcNaluType.IDR);
        } else {
            while (bb.position() < bi.size) {
                SrsEsFrameBytes frame = avc.annexb_demux(bb, bi);

                if (avc.is_sps(frame)) {
                    if (!frame.data.equals(h264_sps)) {
                        byte[] sps = new byte[frame.size];
                        frame.data.get(sps);
                        h264_sps = ByteBuffer.wrap(sps);
                        spsList.clear();
                        spsList.add(sps);
                    }
                    continue;
                }

                if (avc.is_pps(frame)) {
                    if (!frame.data.equals(h264_pps)) {
                        byte[] pps = new byte[frame.size];
                        frame.data.get(pps);
                        h264_pps = ByteBuffer.wrap(pps);
                        ppsList.clear();
                        ppsList.add(pps);
                    }
                    continue;
                }
            }

            if (!spsList.isEmpty() && !ppsList.isEmpty()) {
                mp4Movie.addTrack(videoFormat, false);
            }
        }
    }

    public void writeAudioSample(final ByteBuffer bb, MediaCodec.BufferInfo bi) {
        if (!aacSpecConfig) {
            aacSpecConfig = true;
            mp4Movie.addTrack(audioFormat, true);
        } else {
            writeFrameByte(AUDIO_TRACK, bb, bi, false);
        }
    }

    private void writeFrameByte(int track, ByteBuffer bb, MediaCodec.BufferInfo bi, boolean isKeyFrame) {
        SrsEsFrame frame = new SrsEsFrame();
        frame.bb = bb;
        frame.bi = bi;
        frame.isKeyFrame = isKeyFrame;
        frame.track = track;

        if (bRecording && !bPaused) {
            if (needToFindKeyFrame) {
                if (frame.isKeyFrame) {
                    needToFindKeyFrame = false;
                    frameCache.add(frame);
                    synchronized (writeLock) {
                        writeLock.notifyAll();
                    }
                }
            } else {
                frameCache.add(frame);
                synchronized (writeLock) {
                    writeLock.notifyAll();
                }
            }
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
        public int track;
        public boolean isKeyFrame;

        public boolean is_video() {
            return track == VIDEO_TRACK;
        }

        public boolean is_audio() {
            return track == AUDIO_TRACK;
        }
    }

    /**
     * the raw h.264 stream, in annexb.
     */
    class SrsRawH264Stream {
        public boolean is_sps(SrsEsFrameBytes frame) {
            if (frame.size < 1) {
                return false;
            }

            return (frame.data.get(0) & 0x1f) == SrsAvcNaluType.SPS;
        }

        public boolean is_pps(SrsEsFrameBytes frame) {
            if (frame.size < 1) {
                return false;
            }
            return (frame.data.get(0) & 0x1f) == SrsAvcNaluType.PPS;
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

        public SrsEsFrameBytes annexb_demux(ByteBuffer bb, MediaCodec.BufferInfo bi) throws IllegalArgumentException {
            SrsEsFrameBytes tbb = new SrsEsFrameBytes();

            while (bb.position() < bi.size) {
                // each frame must prefixed by annexb format.
                // about annexb, @see H.264-AVC-ISO_IEC_14496-10.pdf, page 211.
                SrsAnnexbSearch tbbsc = srs_avc_startswith_annexb(bb, bi);
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
                    SrsAnnexbSearch bsc = srs_avc_startswith_annexb(bb, bi);
                    if (bsc.match) {
                        break;
                    }
                    bb.get();
                }

                tbb.size = bb.position() - pos;
                if (bb.position() < bi.size) {
                    Log.i(TAG, String.format("annexb multiple match ok, pts=%d", bi.presentationTimeUs / 1000));
                }
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
                if (format.getString(MediaFormat.KEY_MIME).contentEquals(SrsEncoder.VCODEC)) {
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
                    avcConfigurationBox.setAvcProfileIndication((int) h264_sps.get(1));
                    avcConfigurationBox.setProfileCompatibility(0);
                    avcConfigurationBox.setAvcLevelIndication((int) h264_sps.get(3));
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
            } else {
                tracks.put(VIDEO_TRACK, new Track(tracks.size(), format, false));
            }
        }
    }

    public class InterleaveChunkMdat implements Box {
        private boolean first = true;
        private ContainerBox parent;
        private ByteBuffer header;
        private long contentSize = 1024 * 1024 * 1024;

        public ContainerBox getParent() {
            return parent;
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
            return header.limit() + contentSize;
        }

        public int getHeaderSize() {
            return header.limit();
        }

        private boolean isSmallBox(long contentSize) {
            return (contentSize + header.limit()) < 4294967296L;
        }

        public void getBox(WritableByteChannel writableByteChannel) throws IOException {
            header = ByteBuffer.allocate(16);
            long size = getSize();
            if (isSmallBox(size)) {
                IsoTypeWriter.writeUInt32(header, size);
            } else {
                IsoTypeWriter.writeUInt32(header, 1);
            }
            header.put(IsoFile.fourCCtoBytes("mdat"));
            if (isSmallBox(size)) {
                header.put(new byte[8]);
            } else {
                IsoTypeWriter.writeUInt64(header, size);
            }
            header.rewind();
            writableByteChannel.write(header);
        }

        @Override
        public void parse(ReadableByteChannel readableByteChannel, ByteBuffer header, long contentSize, BoxParser boxParser) throws IOException {
        }
    }

    private InterleaveChunkMdat mdat = null;
    private FileOutputStream fos = null;
    private FileChannel fc = null;
    private volatile long recFileSize = 0;
    private volatile long mdatOffset = 0;
    private volatile long flushBytes = 0;
    private HashMap<Track, long[]> track2SampleSizes = new HashMap<>();

    private void createMovie(File outputFile) throws IOException {
        fos = new FileOutputStream(outputFile);
        fc = fos.getChannel();
        mdat = new InterleaveChunkMdat();
        mdatOffset = 0;

        FileTypeBox fileTypeBox = createFileTypeBox();
        fileTypeBox.getBox(fc);
        recFileSize += fileTypeBox.getSize();
    }

    private void writeSampleData(ByteBuffer byteBuf, MediaCodec.BufferInfo bufferInfo, boolean isAudio) throws IOException {
        int trackIndex = isAudio ? AUDIO_TRACK : VIDEO_TRACK;
        if (!mp4Movie.getTracks().containsKey(trackIndex)) {
            return;
        }

        if (mdat.first) {
            mdat.setContentSize(0);
            mdat.getBox(fc);
            mdatOffset = recFileSize;
            recFileSize += mdat.getHeaderSize();
            mdat.first = false;
        }

        mp4Movie.addSample(trackIndex, recFileSize, bufferInfo);
        byteBuf.position(bufferInfo.offset + (isAudio ? 0 : 4));
        byteBuf.limit(bufferInfo.offset + bufferInfo.size);
        if (!isAudio) {
            ByteBuffer size = ByteBuffer.allocateDirect(4);
            size.position(0);
            size.putInt(bufferInfo.size - 4);
            size.position(0);
            recFileSize += fc.write(size);
        }
        int writeBytes = fc.write(byteBuf);

        recFileSize += writeBytes;
        flushBytes += writeBytes;
        if (flushBytes > 64 * 1024) {
            fos.flush();
            flushBytes = 0;
        }
    }

    private void finishMovie() throws IOException {
        if (flushBytes > 0) {
            fos.flush();
            flushBytes = 0;
        }
        if (mdat.getSize() != 0) {
            // flush cached mdat box
            long oldPosition = fc.position();
            fc.position(mdatOffset);
            mdat.setContentSize(recFileSize - mdat.getHeaderSize() - mdatOffset);
            mdat.getBox(fc);
            fc.position(oldPosition);
            mdat.setContentSize(0);
            fos.flush();
        }

        for (Track track : mp4Movie.getTracks().values()) {
            List<Sample> samples = track.getSamples();
            long[] sizes = new long[samples.size()];
            for (int i = 0; i < sizes.length; i++) {
                sizes[i] = samples.get(i).getSize();
            }
            track2SampleSizes.put(track, sizes);
        }

        Box moov = createMovieBox(mp4Movie);
        moov.getBox(fc);
        fos.flush();

        fc.close();
        fos.close();
        mp4Movie.getTracks().clear();
        track2SampleSizes.clear();
        recFileSize = 0;
        flushBytes = 0;
    }

    private FileTypeBox createFileTypeBox() {
        LinkedList<String> minorBrands = new LinkedList<>();
        minorBrands.add("isom");
        minorBrands.add("3gp4");
        return new FileTypeBox("isom", 0, minorBrands);
    }

    private long getTimescale(Mp4Movie mp4Movie) {
        long timescale = 0;
        if (!mp4Movie.getTracks().isEmpty()) {
            timescale = mp4Movie.getTracks().values().iterator().next().getTimeScale();
        }
        for (Track track : mp4Movie.getTracks().values()) {
            timescale = Math.gcd(track.getTimeScale(), timescale);
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
