package net.ossrs.yasea;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import com.github.faucamp.simplertmp.DefaultRtmpPublisher;
import com.github.faucamp.simplertmp.RtmpHandler;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by winlin on 5/2/15.
 * Updated by leoma on 4/1/16.
 * to POST the h.264/avc annexb frame over RTMP.
 * @see android.media.MediaMuxer https://developer.android.com/reference/android/media/MediaMuxer.html
 */
public class SrsFlvMuxer {

    private static final int VIDEO_ALLOC_SIZE = 128 * 1024;
    private static final int AUDIO_ALLOC_SIZE = 4 * 1024;

    private volatile boolean started = false;
    private DefaultRtmpPublisher publisher;

    private Thread worker;
    private final Object txFrameLock = new Object();

    private SrsFlv flv = new SrsFlv();
    private boolean needToFindKeyFrame = true;
    private SrsFlvFrame mVideoSequenceHeader;
    private SrsFlvFrame mAudioSequenceHeader;
    private SrsAllocator mVideoAllocator = new SrsAllocator(VIDEO_ALLOC_SIZE);
    private SrsAllocator mAudioAllocator = new SrsAllocator(AUDIO_ALLOC_SIZE);
    private ConcurrentLinkedQueue<SrsFlvFrame> mFlvTagCache = new ConcurrentLinkedQueue<>();

    private static final int VIDEO_TRACK = 100;
    private static final int AUDIO_TRACK = 101;
    private static final String TAG = "SrsFlvMuxer";

    /**
     * constructor.
     * @param handler the rtmp event handler.
     */
    public SrsFlvMuxer(RtmpHandler handler) {
        publisher = new DefaultRtmpPublisher(handler);
    }

    /**
     * get cached video frame number in publisher
     */
    public AtomicInteger getVideoFrameCacheNumber() {
        return publisher == null ? null : publisher.getVideoFrameCacheNumber();
    }

    /**
     * set video resolution for publisher
     * @param width width
     * @param height height
     */
    public void setVideoResolution(int width, int height) {
        if (publisher != null) {
            publisher.setVideoResolution(width, height);
        }
    }

    /**
     * Adds a track with the specified format.
     * @param format The media format for the track.
     * @return The track index for this newly added track.
     */
    public int addTrack(MediaFormat format) {
        if (format.getString(MediaFormat.KEY_MIME).contentEquals(SrsEncoder.VCODEC)) {
            flv.setVideoTrack(format);
            return VIDEO_TRACK;
        } else {
            flv.setAudioTrack(format);
            return AUDIO_TRACK;
        }
    }

    private void disconnect() {
        try {
            publisher.close();
        } catch (IllegalStateException e) {
            // Ignore illegal state.
        }
        mVideoSequenceHeader = null;
        mAudioSequenceHeader = null;
        Log.i(TAG, "worker: disconnect ok.");
    }

    private boolean connect(String url) {
        boolean connected = false;
        Log.i(TAG, String.format("worker: connecting to RTMP server by url=%s\n", url));
        if (publisher.connect(url)) {
            connected = publisher.publish("live");
        }
        mVideoSequenceHeader = null;
        mAudioSequenceHeader = null;
        return connected;
    }

    private void sendFlvTag(SrsFlvFrame frame) {
        if (frame == null) {
            return;
        }

        if (frame.isVideo()) {
            if (frame.isKeyFrame()) {
                Log.i(TAG, String.format("worker: send frame type=%d, dts=%d, size=%dB",
                    frame.type, frame.dts, frame.flvTag.array().length));
            }
            publisher.publishVideoData(frame.flvTag.array(), frame.flvTag.size(), frame.dts);
            mVideoAllocator.release(frame.flvTag);
        } else if (frame.isAudio()) {
            publisher.publishAudioData(frame.flvTag.array(), frame.flvTag.size(), frame.dts);
            mAudioAllocator.release(frame.flvTag);
        }
    }

    /**
     * start to the remote server for remux.
     */
    public void start(final String rtmpUrl) {
        started = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                if (!connect(rtmpUrl)) {
                    return;
                }

                while (!Thread.interrupted()) {
                    while (!mFlvTagCache.isEmpty()) {
                        SrsFlvFrame frame = mFlvTagCache.poll();
                        if (frame.isSequenceHeader()) {
                            if (frame.isVideo()) {
                                mVideoSequenceHeader = frame;
                                sendFlvTag(mVideoSequenceHeader);
                            } else if (frame.isAudio()) {
                                mAudioSequenceHeader = frame;
                                sendFlvTag(mAudioSequenceHeader);
                            }
                        } else {
                            if (frame.isVideo() && mVideoSequenceHeader != null) {
                                sendFlvTag(frame);
                            } else if (frame.isAudio() && mAudioSequenceHeader != null) {
                                sendFlvTag(frame);
                            }
                        }
                    }
                    // Waiting for next frame
                    synchronized (txFrameLock) {
                        try {
                            // isEmpty() may take some time, so we set timeout to detect next frame
                            txFrameLock.wait(500);
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
     * stop the muxer, disconnect RTMP connection.
     */
    public void stop() {
        started = false;
        mFlvTagCache.clear();
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
                worker.interrupt();
            }
            worker = null;
        }
        flv.reset();
        needToFindKeyFrame = true;
        Log.i(TAG, "SrsFlvMuxer closed");
        // We should not block the main thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                disconnect();
            }
        }).start();
    }

    /**
     * send the annexb frame over RTMP.
     * @param trackIndex The track index for this sample.
     * @param byteBuf The encoded sample.
     * @param bufferInfo The buffer information related to this sample.
     */
    public void writeSampleData(int trackIndex, ByteBuffer byteBuf, MediaCodec.BufferInfo bufferInfo) {
        if (bufferInfo.offset > 0) {
            Log.w(TAG, String.format("encoded frame %dB, offset=%d pts=%dms",
                    bufferInfo.size, bufferInfo.offset, bufferInfo.presentationTimeUs / 1000
            ));
        }

        if (VIDEO_TRACK == trackIndex) {
            flv.writeVideoSample(byteBuf, bufferInfo);
        } else {
            flv.writeAudioSample(byteBuf, bufferInfo);
        }
    }

    // E.4.3.1 VIDEODATA
    // Frame Type UB [4]
    // Type of video frame. The following values are defined:
    //     1 = key frame (for AVC, a seekable frame)
    //     2 = inter frame (for AVC, a non-seekable frame)
    //     3 = disposable inter frame (H.263 only)
    //     4 = generated key frame (reserved for server use only)
    //     5 = video info/command frame
    private class SrsCodecVideoAVCFrame
    {
        // set to the zero to reserved, for array map.
        public final static int Reserved = 0;
        public final static int Reserved1 = 6;

        public final static int KeyFrame                     = 1;
        public final static int InterFrame                 = 2;
        public final static int DisposableInterFrame         = 3;
        public final static int GeneratedKeyFrame            = 4;
        public final static int VideoInfoFrame                = 5;
    }

    // AVCPacketType IF CodecID == 7 UI8
    // The following values are defined:
    //     0 = AVC sequence header
    //     1 = AVC NALU
    //     2 = AVC end of sequence (lower level NALU sequence ender is
    //         not required or supported)
    private class SrsCodecVideoAVCType
    {
        // set to the max value to reserved, for array map.
        public final static int Reserved                    = 3;

        public final static int SequenceHeader                 = 0;
        public final static int NALU                         = 1;
        public final static int SequenceHeaderEOF             = 2;
    }

    /**
     * E.4.1 FLV Tag, page 75
     */
    private class SrsCodecFlvTag
    {
        // set to the zero to reserved, for array map.
        public final static int Reserved = 0;

        // 8 = audio
        public final static int Audio = 8;
        // 9 = video
        public final static int Video = 9;
        // 18 = script data
        public final static int Script = 18;
    };

    // E.4.3.1 VIDEODATA
    // CodecID UB [4]
    // Codec Identifier. The following values are defined:
    //     2 = Sorenson H.263
    //     3 = Screen video
    //     4 = On2 VP6
    //     5 = On2 VP6 with alpha channel
    //     6 = Screen video version 2
    //     7 = AVC
    private class SrsCodecVideo
    {
        // set to the zero to reserved, for array map.
        public final static int Reserved                = 0;
        public final static int Reserved1                = 1;
        public final static int Reserved2                = 9;

        // for user to disable video, for example, use pure audio hls.
        public final static int Disabled                = 8;

        public final static int SorensonH263             = 2;
        public final static int ScreenVideo             = 3;
        public final static int On2VP6                 = 4;
        public final static int On2VP6WithAlphaChannel = 5;
        public final static int ScreenVideoVersion2     = 6;
        public final static int AVC                     = 7;
    }

    /**
     * the aac object type, for RTMP sequence header
     * for AudioSpecificConfig, @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf, page 33
     * for audioObjectType, @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf, page 23
     */
    private class SrsAacObjectType
    {
        public final static int Reserved = 0;

        // Table 1.1 – Audio Object Type definition
        // @see @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf, page 23
        public final static int AacMain = 1;
        public final static int AacLC = 2;
        public final static int AacSSR = 3;

        // AAC HE = LC+SBR
        public final static int AacHE = 5;
        // AAC HEv2 = LC+SBR+PS
        public final static int AacHEV2 = 29;
    }

    /**
     * the aac profile, for ADTS(HLS/TS)
     * @see https://github.com/simple-rtmp-server/srs/issues/310
     */
    private class SrsAacProfile
    {
        public final static int Reserved = 3;

        // @see 7.1 Profiles, aac-iso-13818-7.pdf, page 40
        public final static int Main = 0;
        public final static int LC = 1;
        public final static int SSR = 2;
    }

    /**
     * the FLV/RTMP supported audio sample rate.
     * Sampling rate. The following values are defined:
     * 0 = 5.5 kHz = 5512 Hz
     * 1 = 11 kHz = 11025 Hz
     * 2 = 22 kHz = 22050 Hz
     * 3 = 44 kHz = 44100 Hz
     */
    private class SrsCodecAudioSampleRate
    {
        public final static int R5512                     = 5512;
        public final static int R11025                    = 11025;
        public final static int R22050                    = 22050;
        public final static int R44100                    = 44100;
        public final static int R32000                    = 32000;
        public final static int R16000                    = 16000;
    }

    /**
     * Table 7-1 – NAL unit type codes, syntax element categories, and NAL unit type classes
     * H.264-AVC-ISO_IEC_14496-10-2012.pdf, page 83.
     */
    private class SrsAvcNaluType
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
     * the search result for annexb.
     */
    private class SrsAnnexbSearch {
        public int nb_start_code = 0;
        public boolean match = false;
    }

    /**
     * the demuxed tag frame.
     */
    private class SrsFlvFrameBytes {
        public ByteBuffer data;
        public int size;
    }

    /**
     * the muxed flv frame.
     */
    private class SrsFlvFrame {
        // the tag bytes.
        public SrsAllocator.Allocation flvTag;
        // the codec type for audio/aac and video/avc for instance.
        public int avc_aac_type;
        // the frame type, keyframe or not.
        public int frame_type;
        // the tag type, audio, video or data.
        public int type;
        // the dts in ms, tbn is 1000.
        public int dts;

        public boolean isKeyFrame() {
            return isVideo() && frame_type == SrsCodecVideoAVCFrame.KeyFrame;
        }

        public boolean isSequenceHeader() {
            return avc_aac_type == 0;
        }

        public boolean isVideo() {
            return type == SrsCodecFlvTag.Video;
        }

        public boolean isAudio() {
            return type == SrsCodecFlvTag.Audio;
        }
    }

    /**
     * the raw h.264 stream, in annexb.
     */
    private class SrsRawH264Stream {
        private final static String TAG = "SrsFlvMuxer";

        private SrsAnnexbSearch annexb = new SrsAnnexbSearch();
        private SrsFlvFrameBytes seq_hdr = new SrsFlvFrameBytes();
        private SrsFlvFrameBytes sps_hdr = new SrsFlvFrameBytes();
        private SrsFlvFrameBytes sps_bb = new SrsFlvFrameBytes();
        private SrsFlvFrameBytes pps_hdr = new SrsFlvFrameBytes();
        private SrsFlvFrameBytes pps_bb = new SrsFlvFrameBytes();

        public boolean isSps(SrsFlvFrameBytes frame) {
            return frame.size >= 1 && (frame.data.get(0) & 0x1f) == SrsAvcNaluType.SPS;
        }

        public boolean isPps(SrsFlvFrameBytes frame) {
            return frame.size >= 1 && (frame.data.get(0) & 0x1f) == SrsAvcNaluType.PPS;
        }

        public SrsFlvFrameBytes muxNaluHeader(SrsFlvFrameBytes frame) {
            SrsFlvFrameBytes nalu_hdr = new SrsFlvFrameBytes();
            nalu_hdr.data = ByteBuffer.allocate(4);
            nalu_hdr.size = 4;
            // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
            // lengthSizeMinusOne, or NAL_unit_length, always use 4bytes size
            int NAL_unit_length = frame.size;

            // mux the avc NALU in "ISO Base Media File Format"
            // from H.264-AVC-ISO_IEC_14496-15.pdf, page 20
            // NALUnitLength
            nalu_hdr.data.putInt(NAL_unit_length);

            // reset the buffer.
            nalu_hdr.data.rewind();
            return nalu_hdr;
        }

        public void muxSequenceHeader(ByteBuffer sps, ByteBuffer pps, int dts, int pts,
                                        ArrayList<SrsFlvFrameBytes> frames) {
            // 5bytes sps/pps header:
            //      configurationVersion, AVCProfileIndication, profile_compatibility,
            //      AVCLevelIndication, lengthSizeMinusOne
            // 3bytes size of sps:
            //      numOfSequenceParameterSets, sequenceParameterSetLength(2B)
            // Nbytes of sps.
            //      sequenceParameterSetNALUnit
            // 3bytes size of pps:
            //      numOfPictureParameterSets, pictureParameterSetLength
            // Nbytes of pps:
            //      pictureParameterSetNALUnit

            // decode the SPS:
            // @see: 7.3.2.1.1, H.264-AVC-ISO_IEC_14496-10-2012.pdf, page 62
            if (seq_hdr.data == null) {
                seq_hdr.data = ByteBuffer.allocate(5);
                seq_hdr.size = 5;
            }
            seq_hdr.data.rewind();
            // @see: Annex A Profiles and levels, H.264-AVC-ISO_IEC_14496-10.pdf, page 205
            //      Baseline profile profile_idc is 66(0x42).
            //      Main profile profile_idc is 77(0x4d).
            //      Extended profile profile_idc is 88(0x58).
            byte profile_idc = sps.get(1);
            //u_int8_t constraint_set = frame[2];
            byte level_idc = sps.get(3);

            // generate the sps/pps header
            // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
            // configurationVersion
            seq_hdr.data.put((byte) 0x01);
            // AVCProfileIndication
            seq_hdr.data.put(profile_idc);
            // profile_compatibility
            seq_hdr.data.put((byte) 0x00);
            // AVCLevelIndication
            seq_hdr.data.put(level_idc);
            // lengthSizeMinusOne, or NAL_unit_length, always use 4bytes size,
            // so we always set it to 0x03.
            seq_hdr.data.put((byte) 0x03);

            // reset the buffer.
            seq_hdr.data.rewind();
            frames.add(seq_hdr);

            // sps
            if (sps_hdr.data == null) {
                sps_hdr.data = ByteBuffer.allocate(3);
                sps_hdr.size = 3;
            }
            sps_hdr.data.rewind();
            // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
            // numOfSequenceParameterSets, always 1
            sps_hdr.data.put((byte) 0x01);
            // sequenceParameterSetLength
            sps_hdr.data.putShort((short) sps.array().length);

            sps_hdr.data.rewind();
            frames.add(sps_hdr);

            // sequenceParameterSetNALUnit
            sps_bb.size = sps.array().length;
            sps_bb.data = sps.duplicate();
            frames.add(sps_bb);

            // pps
            if (pps_hdr.data == null) {
                pps_hdr.data = ByteBuffer.allocate(3);
                pps_hdr.size = 3;
            }
            pps_hdr.data.rewind();
            // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
            // numOfPictureParameterSets, always 1
            pps_hdr.data.put((byte) 0x01);
            // pictureParameterSetLength
            pps_hdr.data.putShort((short) pps.array().length);

            pps_hdr.data.rewind();
            frames.add(pps_hdr);

            // pictureParameterSetNALUnit
            pps_bb.size = pps.array().length;
            pps_bb.data = pps.duplicate();
            frames.add(pps_bb);
        }

        public SrsAllocator.Allocation muxFlvTag(ArrayList<SrsFlvFrameBytes> frames, int frame_type,
                                                 int avc_packet_type, int dts, int pts) {
            // for h264 in RTMP video payload, there is 5bytes header:
            //      1bytes, FrameType | CodecID
            //      1bytes, AVCPacketType
            //      3bytes, CompositionTime, the cts.
            // @see: E.4.3 Video Tags, video_file_format_spec_v10_1.pdf, page 78
            int size = 5;
            for (int i = 0; i < frames.size(); i++) {
                size += frames.get(i).size;
            }
            SrsAllocator.Allocation allocation = mVideoAllocator.allocate(size);

            // @see: E.4.3 Video Tags, video_file_format_spec_v10_1.pdf, page 78
            // Frame Type, Type of video frame.
            // CodecID, Codec Identifier.
            // set the rtmp header
            allocation.put((byte) ((frame_type << 4) | SrsCodecVideo.AVC));

            // AVCPacketType
            allocation.put((byte)avc_packet_type);

            // CompositionTime
            // pts = dts + cts, or
            // cts = pts - dts.
            // where cts is the header in rtmp video packet payload header.
            int cts = pts - dts;
            allocation.put((byte)(cts >> 16));
            allocation.put((byte)(cts >> 8));
            allocation.put((byte)cts);

            // h.264 raw data.
            for (int i = 0; i < frames.size(); i++) {
                SrsFlvFrameBytes frame = frames.get(i);
                frame.data.get(allocation.array(), allocation.size(), frame.size);
                allocation.appendOffset(frame.size);
            }

            return allocation;
        }

        private SrsAnnexbSearch searchStartcode(ByteBuffer bb, MediaCodec.BufferInfo bi) {
            annexb.match = false;
            annexb.nb_start_code = 0;
            if (bi.size - 4 > 0) {
                if (bb.get(0) == 0x00 && bb.get(1) == 0x00 && bb.get(2) == 0x00 && bb.get(3) == 0x01) {
                    // match N[00] 00 00 00 01, where N>=0
                    annexb.match = true;
                    annexb.nb_start_code = 4;
                }else if (bb.get(0) == 0x00 && bb.get(1) == 0x00 && bb.get(2) == 0x01){
                    // match N[00] 00 00 01, where N>=0
                    annexb.match = true;
                    annexb.nb_start_code = 3;
                }
            }
            return annexb;
        }

        private SrsAnnexbSearch searchAnnexb(ByteBuffer bb, MediaCodec.BufferInfo bi) {
            annexb.match = false;
            annexb.nb_start_code = 0;
            for (int i = bb.position(); i < bi.size - 4; i++) {
                // not match.
                if (bb.get(i) != 0x00 || bb.get(i + 1) != 0x00) {
                    continue;
                }
                // match N[00] 00 00 01, where N>=0
                if (bb.get(i + 2) == 0x01) {
                    annexb.match = true;
                    annexb.nb_start_code = i + 3 - bb.position();
                    break;
                }
                // match N[00] 00 00 00 01, where N>=0
                if (bb.get(i + 2) == 0x00 && bb.get(i + 3) == 0x01) {
                    annexb.match = true;
                    annexb.nb_start_code = i + 4 - bb.position();
                    break;
                }
            }
            return annexb;
        }

        public SrsFlvFrameBytes demuxAnnexb(ByteBuffer bb, MediaCodec.BufferInfo bi, boolean isOnlyChkHeader) {
            SrsFlvFrameBytes tbb = new SrsFlvFrameBytes();
            if (bb.position() < bi.size - 4) {
                // each frame must prefixed by annexb format.
                // about annexb, @see H.264-AVC-ISO_IEC_14496-10.pdf, page 211.
                SrsAnnexbSearch tbbsc = isOnlyChkHeader ? searchStartcode(bb, bi) : searchAnnexb(bb, bi);
                // tbbsc.nb_start_code always 4 , after 00 00 00 01
                if (!tbbsc.match || tbbsc.nb_start_code < 3) {
                    Log.e(TAG, "annexb not match.");
                } else {
                    // the start codes.
                    for (int i = 0; i < tbbsc.nb_start_code; i++) {
                        bb.get();
                    }

                    // find out the frame size.
                    tbb.data = bb.slice();
                    tbb.size = bi.size - bb.position();
                }
            }
            return tbb;
        }
    }

    private class SrsRawAacStreamCodec {
        public byte protection_absent;
        // SrsAacObjectType
        public int aac_object;
        public byte sampling_frequency_index;
        public byte channel_configuration;
        public short frame_length;

        public byte sound_format;
        public byte sound_rate;
        public byte sound_size;
        public byte sound_type;
        // 0 for sh; 1 for raw data.
        public byte aac_packet_type;

        public byte[] frame;
    }

    /**
     * remux the annexb to flv tags.
     */
    private class SrsFlv {
        private MediaFormat videoTrack;
        private MediaFormat audioTrack;
        private int achannel;
        private int asample_rate;
        private SrsRawH264Stream avc = new SrsRawH264Stream();
        private ArrayList<SrsFlvFrameBytes> ipbs = new ArrayList<>();
        private SrsAllocator.Allocation audio_tag;
        private SrsAllocator.Allocation video_tag;
        private ByteBuffer h264_sps;
        private boolean h264_sps_changed;
        private ByteBuffer h264_pps;
        private boolean h264_pps_changed;
        private boolean h264_sps_pps_sent;
        private boolean aac_specific_config_got;

        public SrsFlv() {
            reset();
        }

        public void reset() {
            h264_sps_changed = false;
            h264_pps_changed = false;
            h264_sps_pps_sent = false;
            aac_specific_config_got = false;
            if (null != h264_sps){
                Arrays.fill(h264_sps.array(),(byte) 0x00);
                h264_sps.clear();
            }
            if (null!=h264_pps) {
                Arrays.fill(h264_pps.array(),(byte) 0x00);
                h264_pps.clear();
            }

        }

        public void setVideoTrack(MediaFormat format) {
            videoTrack = format;
        }

        public void setAudioTrack(MediaFormat format) {
            audioTrack = format;
            achannel = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            asample_rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        }

        public void writeAudioSample(final ByteBuffer bb, MediaCodec.BufferInfo bi) {
            int pts = (int)(bi.presentationTimeUs / 1000);
            int dts = pts;

            audio_tag = mAudioAllocator.allocate(bi.size + 2);
            byte aac_packet_type = 1; // 1 = AAC raw
            if (!aac_specific_config_got) {
                // @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf
                // AudioSpecificConfig (), page 33
                // 1.6.2.1 AudioSpecificConfig
                // audioObjectType; 5 bslbf
                byte ch = (byte)(bb.get(0) & 0xf8);
                // 3bits left.

                // samplingFrequencyIndex; 4 bslbf
                byte samplingFrequencyIndex = 0x04;
                if (asample_rate == SrsCodecAudioSampleRate.R22050) {
                    samplingFrequencyIndex = 0x07;
                } else if (asample_rate == SrsCodecAudioSampleRate.R11025) {
                    samplingFrequencyIndex = 0x0a;
                } else if (asample_rate == SrsCodecAudioSampleRate.R32000) {
                    samplingFrequencyIndex = 0x05;
                } else if (asample_rate == SrsCodecAudioSampleRate.R16000) {
                    samplingFrequencyIndex = 0x08;
                }
                ch |= (samplingFrequencyIndex >> 1) & 0x07;
                audio_tag.put(ch, 2);

                ch = (byte)((samplingFrequencyIndex << 7) & 0x80);
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
                audio_tag.put(ch, 3);

                aac_specific_config_got = true;
                aac_packet_type = 0; // 0 = AAC sequence header

                writeAdtsHeader(audio_tag.array(), 4);
                audio_tag.appendOffset(7);
            } else {
                bb.get(audio_tag.array(), 2, bi.size);
                audio_tag.appendOffset(bi.size + 2);
            }

            byte sound_format = 10; // AAC
            byte sound_type = 0; // 0 = Mono sound
            if (achannel == 2) {
                sound_type = 1; // 1 = Stereo sound
            }
            byte sound_size = 1; // 1 = 16-bit samples
            byte sound_rate = 3; // 44100, 22050, 11025, 5512
            if (asample_rate == 22050) {
                sound_rate = 2;
            } else if (asample_rate == 11025) {
                sound_rate = 1;
            } else if (asample_rate == 5512) {
                sound_rate = 0;
            }

            // for audio frame, there is 1 or 2 bytes header:
            //      1bytes, SoundFormat|SoundRate|SoundSize|SoundType
            //      1bytes, AACPacketType for SoundFormat == 10, 0 is sequence header.
            byte audio_header = (byte) (sound_type & 0x01);
            audio_header |= (sound_size << 1) & 0x02;
            audio_header |= (sound_rate << 2) & 0x0c;
            audio_header |= (sound_format << 4) & 0xf0;

            audio_tag.put(audio_header, 0);
            audio_tag.put(aac_packet_type, 1);

            writeRtmpPacket(SrsCodecFlvTag.Audio, dts, 0, aac_packet_type, audio_tag);
        }

        private void writeAdtsHeader(byte[] frame, int offset) {
            // adts sync word 0xfff (12-bit)
            frame[offset] = (byte) 0xff;
            frame[offset + 1] = (byte) 0xf0;
            // versioin 0 for MPEG-4, 1 for MPEG-2 (1-bit)
            frame[offset + 1] |= 0 << 3;
            // layer 0 (2-bit)
            frame[offset + 1] |= 0 << 1;
            // protection absent: 1 (1-bit)
            frame[offset + 1] |= 1;
            // profile: audio_object_type - 1 (2-bit)
            frame[offset + 2] = (SrsAacObjectType.AacLC - 1) << 6;
            // sampling frequency index: 4 (4-bit)
            frame[offset + 2] |= (4 & 0xf) << 2;
            // channel configuration (3-bit)
            frame[offset + 2] |= (2 & (byte) 0x4) >> 2;
            frame[offset + 3] = (byte) ((2 & (byte) 0x03) << 6);
            // original: 0 (1-bit)
            frame[offset + 3] |= 0 << 5;
            // home: 0 (1-bit)
            frame[offset + 3] |= 0 << 4;
            // copyright id bit: 0 (1-bit)
            frame[offset + 3] |= 0 << 3;
            // copyright id start: 0 (1-bit)
            frame[offset + 3] |= 0 << 2;
            // frame size (13-bit)
            frame[offset + 3] |= ((frame.length - 2) & 0x1800) >> 11;
            frame[offset + 4] = (byte) (((frame.length - 2) & 0x7f8) >> 3);
            frame[offset + 5] = (byte) (((frame.length - 2) & 0x7) << 5);
            // buffer fullness (0x7ff for variable bitrate)
            frame[offset + 5] |= (byte) 0x1f;
            frame[offset + 6] = (byte) 0xfc;
            // number of data block (nb - 1)
            frame[offset + 6] |= 0x0;
        }

        public void writeVideoSample(final ByteBuffer bb, MediaCodec.BufferInfo bi) {
            if (bi.size < 4) return;

            int pts = (int) (bi.presentationTimeUs / 1000);
            int dts = pts;
            int type = SrsCodecVideoAVCFrame.InterFrame;
            SrsFlvFrameBytes frame = avc.demuxAnnexb(bb, bi, true);
            int nal_unit_type = frame.data.get(0) & 0x1f;
            if (nal_unit_type == SrsAvcNaluType.IDR) {
                type = SrsCodecVideoAVCFrame.KeyFrame;
            } else if (nal_unit_type == SrsAvcNaluType.SPS || nal_unit_type == SrsAvcNaluType.PPS) {
                SrsFlvFrameBytes frame_pps = avc.demuxAnnexb(bb, bi, false);
                frame.size = frame.size - frame_pps.size - 4;  // 4 ---> 00 00 00 01 pps
                if (!frame.data.equals(h264_sps)) {
                    byte[] sps = new byte[frame.size];
                    frame.data.get(sps);
                    h264_sps_changed = true;
                    h264_sps = ByteBuffer.wrap(sps);
//                    writeH264SpsPps(dts, pts);
                }

                SrsFlvFrameBytes frame_sei = avc.demuxAnnexb(bb, bi, false);
                if (frame_sei.size > 0){
                    if(SrsAvcNaluType.SEI == (int)(frame_sei.data.get(0) & 0x1f))
                        frame_pps.size = frame_pps.size - frame_sei.size - 3;// 3 ---> 00 00 01 SEI
                }

                if (!frame_pps.data.equals(h264_pps)) {
                    byte[] pps = new byte[frame_pps.size];
                    frame_pps.data.get(pps);
                    h264_pps_changed = true;
                    h264_pps = ByteBuffer.wrap(pps);
                    writeH264SpsPps(dts, pts);
                }
                return;
            } else if (nal_unit_type != SrsAvcNaluType.NonIDR) {
                return;
            }

            ipbs.add(avc.muxNaluHeader(frame));
            ipbs.add(frame);

            //writeH264SpsPps(dts, pts);
            writeH264IpbFrame(ipbs, type, dts, pts);
            ipbs.clear();
        }

        private void writeH264SpsPps(int dts, int pts) {
            // when sps or pps changed, update the sequence header,
            // for the pps maybe not changed while sps changed.
            // so, we must check when each video ts message frame parsed.
            if (h264_sps_pps_sent && !h264_sps_changed && !h264_pps_changed) {
                return;
            }

            // when not got sps/pps, wait.
            if (h264_pps == null || h264_sps == null) {
                return;
            }

            // h264 raw to h264 packet.
            ArrayList<SrsFlvFrameBytes> frames = new ArrayList<>();
            avc.muxSequenceHeader(h264_sps, h264_pps, dts, pts, frames);

            // h264 packet to flv packet.
            int frame_type = SrsCodecVideoAVCFrame.KeyFrame;
            int avc_packet_type = SrsCodecVideoAVCType.SequenceHeader;
            video_tag = avc.muxFlvTag(frames, frame_type, avc_packet_type, dts, pts);

            // the timestamp in rtmp message header is dts.
            writeRtmpPacket(SrsCodecFlvTag.Video, dts, frame_type, avc_packet_type, video_tag);

            // reset sps and pps.
            h264_sps_changed = false;
            h264_pps_changed = false;
            h264_sps_pps_sent = true;
            Log.i(TAG, String.format("flv: h264 sps/pps sent, sps=%dB, pps=%dB",
                h264_sps.array().length, h264_pps.array().length));
        }

        private void writeH264IpbFrame(ArrayList<SrsFlvFrameBytes> frames, int type, int dts, int pts) {
            // when sps or pps not sent, ignore the packet.
            // @see https://github.com/simple-rtmp-server/srs/issues/203
            if (!h264_sps_pps_sent) {
                return;
            }

            video_tag = avc.muxFlvTag(frames, type, SrsCodecVideoAVCType.NALU, dts, pts);

            // the timestamp in rtmp message header is dts.
            writeRtmpPacket(SrsCodecFlvTag.Video, dts, type, SrsCodecVideoAVCType.NALU, video_tag);
        }

        private void writeRtmpPacket(int type, int dts, int frame_type, int avc_aac_type, SrsAllocator.Allocation tag) {
            SrsFlvFrame frame = new SrsFlvFrame();
            frame.flvTag = tag;
            frame.type = type;
            frame.dts = dts;
            frame.frame_type = frame_type;
            frame.avc_aac_type = avc_aac_type;

            if (frame.isVideo()) {
                if (needToFindKeyFrame) {
                    if (frame.isKeyFrame()) {
                        needToFindKeyFrame = false;
                        flvTagCacheAdd(frame);
                    }
                } else {
                    flvTagCacheAdd(frame);
                }
            } else if (frame.isAudio()) {
                flvTagCacheAdd(frame);
            }
        }

        private void flvTagCacheAdd(SrsFlvFrame frame) {
            if (started) {
                mFlvTagCache.add(frame);
                if (frame.isVideo()) {
                    getVideoFrameCacheNumber().incrementAndGet();
                }
            }
            synchronized (txFrameLock) {
                txFrameLock.notifyAll();
            }
        }
    }
}
