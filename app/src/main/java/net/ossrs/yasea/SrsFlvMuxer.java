package net.ossrs.yasea;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.ossrs.yasea.rtmp.DefaultRtmpPublisher;

/**
 * Created by winlin on 5/2/15.
 * Updated by leoma on 4/1/16.
 * to POST the h.264/avc annexb frame to SRS over RTMP.
 * @remark we must start a worker thread to send data to server.
 * @see android.media.MediaMuxer https://developer.android.com/reference/android/media/MediaMuxer.html
 *
 * Usage:
 *      muxer = new SrsRtmp("rtmp://ossrs.net/live/yasea");
 *      muxer.start();
 *
 *      MediaFormat aformat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, asample_rate, achannel);
 *      // setup the aformat for audio.
 *      atrack = muxer.addTrack(aformat);
 *
 *      MediaFormat vformat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vsize.width, vsize.height);
 *      // setup the vformat for video.
 *      vtrack = muxer.addTrack(vformat);
 *
 *      // encode the video frame from camera by h.264 codec to es and bi,
 *      // where es is the h.264 ES(element stream).
 *      ByteBuffer es, MediaCodec.BufferInfo bi;
 *      muxer.writeSampleData(vtrack, es, bi);
 *
 *      // encode the audio frame from microphone by aac codec to es and bi,
 *      // where es is the aac ES(element stream).
 *      ByteBuffer es, MediaCodec.BufferInfo bi;
 *      muxer.writeSampleData(atrack, es, bi);
 *
 *      muxer.stop();
 *      muxer.release();
 */
public class SrsFlvMuxer {
    private volatile boolean connected = false;
    private DefaultRtmpPublisher publisher;

    private Thread worker;
    private final Object txFrameLock = new Object();

    private SrsFlv flv = new SrsFlv();
    private boolean needToFindKeyFrame = true;
    private boolean sequenceHeaderOk = false;
    private SrsFlvFrame videoSequenceHeader;
    private SrsFlvFrame audioSequenceHeader;
    private ConcurrentLinkedQueue<SrsFlvFrame> frameCache = new ConcurrentLinkedQueue<SrsFlvFrame>();

    private static final int VIDEO_TRACK = 100;
    private static final int AUDIO_TRACK = 101;
    private static final String TAG = "SrsFlvMuxer";

    /**
     * constructor.
     * @param handler the rtmp event handler.
     */
    public SrsFlvMuxer(DefaultRtmpPublisher.EventHandler handler) {
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
            publisher.closeStream();
        } catch (IllegalStateException e) {
            // Ignore illegal state.
        }
        publisher.shutdown();
        connected = false;
        sequenceHeaderOk = false;
    }

    private boolean connect(String url) {
        try {
            if (!connected) {
                Log.i(TAG, String.format("worker: connecting to RTMP server by url=%s\n", url));
                if (publisher.connect(url)) {
                    connected = publisher.publish("live");
                }
                sequenceHeaderOk = false;
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), ioe);
        }
        return connected;
    }

    private void sendFlvTag(SrsFlvFrame frame) throws IllegalStateException, IOException {
        if (!connected || frame == null) {
            return;
        }

        if (frame.is_video()) {
            publisher.publishVideoData(frame.flvTag.array(), frame.dts);
        } else if (frame.is_audio()) {
            publisher.publishAudioData(frame.flvTag.array(), frame.dts);
        }

        if (frame.is_keyframe()) {
            Log.i(TAG, String.format("worker: send frame type=%d, dts=%d, size=%dB",
                    frame.type, frame.dts, frame.flvTag.array().length));
        }
    }

    /**
     * start to the remote SRS for remux.
     */
    public void start(final String rtmpUrl) throws IOException {

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                if (!connect(rtmpUrl)) {
                    return;
                }

                while (!Thread.interrupted()) {
                    // Keep at least one audio and video frame in cache to ensure monotonically increasing.
                    while (!frameCache.isEmpty()) {
                        SrsFlvFrame frame = frameCache.poll();
                        try {
                            // when sequence header required,
                            // adjust the dts by the current frame and sent it.
                            if (!sequenceHeaderOk) {
                                if (videoSequenceHeader != null) {
                                    videoSequenceHeader.dts = frame.dts;
                                }
                                if (audioSequenceHeader != null) {
                                    audioSequenceHeader.dts = frame.dts;
                                }

                                sendFlvTag(audioSequenceHeader);
                                sendFlvTag(videoSequenceHeader);
                                sequenceHeaderOk = true;
                            }

                            // try to send, ignore when not connected.
                            if (sequenceHeaderOk) {
                                sendFlvTag(frame);
                            }

                            // cache the sequence header.
                            if (frame.type == SrsCodecFlvTag.Video && frame.avc_aac_type == SrsCodecVideoAVCType.SequenceHeader) {
                                videoSequenceHeader = frame;
                            } else if (frame.type == SrsCodecFlvTag.Audio && frame.avc_aac_type == 0) {
                                audioSequenceHeader = frame;
                            }
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(worker, ioe);
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
     * stop the muxer, disconnect RTMP connection from SRS.
     */
    public void stop() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                disconnect();
            }
        }).start();

        if (worker != null) {
            worker.interrupt();
            try {
                worker.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
                worker.interrupt();
            }
            frameCache.clear();
            worker = null;
            Log.i(TAG, "worker: disconnect SRS ok.");
        }

        needToFindKeyFrame = true;
        Log.i(TAG, "SrsFlvMuxer closed");
    }

    /**
     * send the annexb frame to SRS over RTMP.
     * @param trackIndex The track index for this sample.
     * @param byteBuf The encoded sample.
     * @param bufferInfo The buffer information related to this sample.
     */
    public void writeSampleData(int trackIndex, ByteBuffer byteBuf, MediaCodec.BufferInfo bufferInfo) throws IllegalArgumentException {
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

    /**
     * print the size of bytes in bb
     * @param bb the bytes to print.
     * @param size the total size of bytes to print.
     */
    public static void srs_print_bytes(String tag, ByteBuffer bb, int size) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int bytes_in_line = 16;
        int max = bb.remaining();
        for (i = 0; i < size && i < max; i++) {
            sb.append(String.format("0x%s ", Integer.toHexString(bb.get(i) & 0xFF)));
            if (((i + 1) % bytes_in_line) == 0) {
                Log.i(tag, String.format("%03d-%03d: %s", i / bytes_in_line * bytes_in_line, i, sb.toString()));
                sb = new StringBuilder();
            }
        }
        if (sb.length() > 0) {
            Log.i(tag, String.format("%03d-%03d: %s", size / bytes_in_line * bytes_in_line, i - 1, sb.toString()));
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
    class SrsCodecVideoAVCFrame
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
    class SrsCodecVideoAVCType
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
    class SrsCodecVideo
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
    class SrsAacObjectType
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
    class SrsAacProfile
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
     * utils functions from srs.
     */
    public class SrsUtils {

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

        public boolean srs_aac_startswith_adts(ByteBuffer bb, MediaCodec.BufferInfo bi)
        {
            int pos = bb.position();
            if (bi.size - pos < 2) {
                return false;
            }

            // matched 12bits 0xFFF,
            // @remark, we must cast the 0xff to char to compare.
            if (bb.get(pos) != (byte)0xff || (byte)(bb.get(pos + 1) & 0xf0) != (byte)0xf0) {
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
    class SrsFlvFrameBytes {
        public ByteBuffer data;
        public int size;
    }

    /**
     * the muxed flv frame.
     */
    class SrsFlvFrame {
        // the tag bytes.
        public ByteBuffer flvTag;
        // the codec type for audio/aac and video/avc for instance.
        public int avc_aac_type;
        // the frame type, keyframe or not.
        public int frame_type;
        // the tag type, audio, video or data.
        public int type;
        // the dts in ms, tbn is 1000.
        public int dts;

        public boolean is_keyframe() {
            return type == SrsCodecFlvTag.Video && frame_type == SrsCodecVideoAVCFrame.KeyFrame;
        }

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
        private final static String TAG = "SrsFlvMuxer";

        public SrsRawH264Stream() {
            utils = new SrsUtils();
        }

        public boolean is_sps(SrsFlvFrameBytes frame) {
            if (frame.size < 1) {
                return false;
            }
            return (frame.data.get(0) & 0x1f) == SrsAvcNaluType.SPS;
        }

        public boolean is_pps(SrsFlvFrameBytes frame) {
            if (frame.size < 1) {
                return false;
            }
            return (frame.data.get(0) & 0x1f) == SrsAvcNaluType.PPS;
        }

        public SrsFlvFrameBytes mux_ibp_frame(SrsFlvFrameBytes frame) {
            SrsFlvFrameBytes nalu_header = new SrsFlvFrameBytes();
            nalu_header.size = 4;
            nalu_header.data = ByteBuffer.allocate(nalu_header.size);

            // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
            // lengthSizeMinusOne, or NAL_unit_length, always use 4bytes size
            int NAL_unit_length = frame.size;

            // mux the avc NALU in "ISO Base Media File Format"
            // from H.264-AVC-ISO_IEC_14496-15.pdf, page 20
            // NALUnitLength
            nalu_header.data.putInt(NAL_unit_length);

            // reset the buffer.
            nalu_header.data.rewind();
            return nalu_header;
        }

        public void mux_sequence_header(ByteBuffer sps, ByteBuffer pps, int dts, int pts, ArrayList<SrsFlvFrameBytes> frames) {
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
            if (true) {
                SrsFlvFrameBytes hdr = new SrsFlvFrameBytes();
                hdr.size = 5;
                hdr.data = ByteBuffer.allocate(hdr.size);

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
                hdr.data.put((byte)0x01);
                // AVCProfileIndication
                hdr.data.put(profile_idc);
                // profile_compatibility
                hdr.data.put((byte)0x00);
                // AVCLevelIndication
                hdr.data.put(level_idc);
                // lengthSizeMinusOne, or NAL_unit_length, always use 4bytes size,
                // so we always set it to 0x03.
                hdr.data.put((byte)0x03);

                // reset the buffer.
                hdr.data.rewind();
                frames.add(hdr);
            }

            // sps
            if (true) {
                SrsFlvFrameBytes sps_hdr = new SrsFlvFrameBytes();
                sps_hdr.size = 3;
                sps_hdr.data = ByteBuffer.allocate(sps_hdr.size);

                // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
                // numOfSequenceParameterSets, always 1
                sps_hdr.data.put((byte) 0x01);
                // sequenceParameterSetLength
                sps_hdr.data.putShort((short) sps.array().length);

                sps_hdr.data.rewind();
                frames.add(sps_hdr);

                // sequenceParameterSetNALUnit
                SrsFlvFrameBytes sps_bb = new SrsFlvFrameBytes();
                sps_bb.size = sps.array().length;
                sps_bb.data = sps.duplicate();
                frames.add(sps_bb);
            }

            // pps
            if (true) {
                SrsFlvFrameBytes pps_hdr = new SrsFlvFrameBytes();
                pps_hdr.size = 3;
                pps_hdr.data = ByteBuffer.allocate(pps_hdr.size);

                // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
                // numOfPictureParameterSets, always 1
                pps_hdr.data.put((byte) 0x01);
                // pictureParameterSetLength
                pps_hdr.data.putShort((short) pps.array().length);

                pps_hdr.data.rewind();
                frames.add(pps_hdr);

                // pictureParameterSetNALUnit
                SrsFlvFrameBytes pps_bb = new SrsFlvFrameBytes();
                pps_bb.size = pps.array().length;
                pps_bb.data = pps.duplicate();
                frames.add(pps_bb);
            }
        }

        public SrsFlvFrameBytes mux_avc2flv(ArrayList<SrsFlvFrameBytes> frames, int frame_type, int avc_packet_type, int dts, int pts) {
            SrsFlvFrameBytes flv_tag = new SrsFlvFrameBytes();

            // for h264 in RTMP video payload, there is 5bytes header:
            //      1bytes, FrameType | CodecID
            //      1bytes, AVCPacketType
            //      3bytes, CompositionTime, the cts.
            // @see: E.4.3 Video Tags, video_file_format_spec_v10_1.pdf, page 78
            flv_tag.size = 5;
            for (int i = 0; i < frames.size(); i++) {
                SrsFlvFrameBytes frame = frames.get(i);
                flv_tag.size += frame.size;
            }

            flv_tag.data = ByteBuffer.allocate(flv_tag.size);

            // @see: E.4.3 Video Tags, video_file_format_spec_v10_1.pdf, page 78
            // Frame Type, Type of video frame.
            // CodecID, Codec Identifier.
            // set the rtmp header
            flv_tag.data.put((byte)((frame_type << 4) | SrsCodecVideo.AVC));

            // AVCPacketType
            flv_tag.data.put((byte)avc_packet_type);

            // CompositionTime
            // pts = dts + cts, or
            // cts = pts - dts.
            // where cts is the header in rtmp video packet payload header.
            int cts = pts - dts;
            flv_tag.data.put((byte)(cts >> 16));
            flv_tag.data.put((byte)(cts >> 8));
            flv_tag.data.put((byte)cts);

            // h.264 raw data.
            for (int i = 0; i < frames.size(); i++) {
                SrsFlvFrameBytes frame = frames.get(i);
                byte[] frame_bytes = new byte[frame.size];
                frame.data.get(frame_bytes);
                flv_tag.data.put(frame_bytes);
            }

            // reset the buffer.
            flv_tag.data.rewind();
            return flv_tag;
        }

        public SrsFlvFrameBytes annexb_demux(ByteBuffer bb, MediaCodec.BufferInfo bi) throws IllegalArgumentException {
            SrsFlvFrameBytes tbb = new SrsFlvFrameBytes();

            while (bb.position() < bi.size) {
                // each frame must prefixed by annexb format.
                // about annexb, @see H.264-AVC-ISO_IEC_14496-10.pdf, page 211.
                SrsAnnexbSearch tbbsc = utils.srs_avc_startswith_annexb(bb, bi);
                if (!tbbsc.match || tbbsc.nb_start_code < 3) {
                    Log.e(TAG, "annexb not match.");
                    SrsFlvMuxer.srs_print_bytes(TAG, bb, 16);
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
                break;
            }

            return tbb;
        }
    }

    class SrsRawAacStreamCodec {
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
    class SrsFlv {
        private MediaFormat videoTrack;
        private MediaFormat audioTrack;
        private int achannel;
        private int asample_rate;

        private SrsRawH264Stream avc;
        private ByteBuffer h264_sps;
        private boolean h264_sps_changed;
        private ByteBuffer h264_pps;
        private boolean h264_pps_changed;
        private boolean h264_sps_pps_sent;
        private byte[] aac_specific_config;

        public SrsFlv() {
            avc = new SrsRawH264Stream();
            h264_sps_changed = false;
            h264_pps_changed = false;
            h264_sps_pps_sent = false;

            aac_specific_config = null;
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

            byte[] frame = new byte[bi.size + 2];
            byte aac_packet_type = 1; // 1 = AAC raw
            if (aac_specific_config == null) {
                frame = new byte[4];

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
                }
                ch |= (samplingFrequencyIndex >> 1) & 0x07;
                frame[2] = ch;

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
                frame[3] = ch;

                aac_specific_config = frame;
                aac_packet_type = 0; // 0 = AAC sequence header
            } else {
                bb.get(frame, 2, frame.length - 2);
            }

            byte sound_format = 10; // AAC
            byte sound_type = 0; // 0 = Mono sound
            if (achannel == 2) {
                sound_type = 1; // 1 = Stereo sound
            }
            byte sound_size = 1; // 1 = 16-bit samples
            byte sound_rate = 3; // 44100, 22050, 11025
            if (asample_rate == 22050) {
                sound_rate = 2;
            } else if (asample_rate == 11025) {
                sound_rate = 1;
            }

            // for audio frame, there is 1 or 2 bytes header:
            //      1bytes, SoundFormat|SoundRate|SoundSize|SoundType
            //      1bytes, AACPacketType for SoundFormat == 10, 0 is sequence header.
            byte audio_header = (byte)(sound_type & 0x01);
            audio_header |= (sound_size << 1) & 0x02;
            audio_header |= (sound_rate << 2) & 0x0c;
            audio_header |= (sound_format << 4) & 0xf0;

            frame[0] = audio_header;
            frame[1] = aac_packet_type;

            SrsFlvFrameBytes tag = new SrsFlvFrameBytes();
            tag.data = ByteBuffer.wrap(frame);
            tag.size = frame.length;

            rtmp_write_packet(SrsCodecFlvTag.Audio, dts, 0, aac_packet_type, tag);
        }

        public void writeVideoSample(final ByteBuffer bb, MediaCodec.BufferInfo bi) throws IllegalArgumentException {
            int pts = (int)(bi.presentationTimeUs / 1000);
            int dts = (int)pts;

            ArrayList<SrsFlvFrameBytes> ibps = new ArrayList<SrsFlvFrameBytes>();
            int frame_type = SrsCodecVideoAVCFrame.InterFrame;

            // send each frame.
            while (bb.position() < bi.size) {
                SrsFlvFrameBytes frame = avc.annexb_demux(bb, bi);

                // 5bits, 7.3.1 NAL unit syntax,
                // H.264-AVC-ISO_IEC_14496-10.pdf, page 44.
                //  7: SPS, 8: PPS, 5: I Frame, 1: P Frame
                int nal_unit_type = (int)(frame.data.get(0) & 0x1f);
                if (nal_unit_type == SrsAvcNaluType.SPS || nal_unit_type == SrsAvcNaluType.PPS) {
                    Log.i(TAG, String.format("annexb demux %dB, pts=%d, frame=%dB, nalu=%d", bi.size, pts, frame.size, nal_unit_type));
                }

                // for IDR frame, the frame is keyframe.
                if (nal_unit_type == SrsAvcNaluType.IDR) {
                    frame_type = SrsCodecVideoAVCFrame.KeyFrame;
                }

                // ignore the nalu type aud(9)
                if (nal_unit_type == SrsAvcNaluType.AccessUnitDelimiter) {
                    continue;
                }

                // for sps
                if (avc.is_sps(frame)) {
                    if (!frame.data.equals(h264_sps)) {
                        byte[] sps = new byte[frame.size];
                        frame.data.get(sps);
                        h264_sps_changed = true;
                        h264_sps = ByteBuffer.wrap(sps);
                    }
                    continue;
                }

                // for pps
                if (avc.is_pps(frame)) {
                    if (!frame.data.equals(h264_pps)) {
                        byte[] pps = new byte[frame.size];
                        frame.data.get(pps);
                        h264_pps_changed = true;
                        h264_pps = ByteBuffer.wrap(pps);
                    }
                    continue;
                }

                // ibp frame.
                SrsFlvFrameBytes nalu_header = avc.mux_ibp_frame(frame);
                ibps.add(nalu_header);
                ibps.add(frame);
            }

            write_h264_sps_pps(dts, pts);

            write_h264_ipb_frame(ibps, frame_type, dts, pts);
        }

        private void write_h264_sps_pps(int dts, int pts) {
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
            ArrayList<SrsFlvFrameBytes> frames = new ArrayList<SrsFlvFrameBytes>();
            avc.mux_sequence_header(h264_sps, h264_pps, dts, pts, frames);

            // h264 packet to flv packet.
            int frame_type = SrsCodecVideoAVCFrame.KeyFrame;
            int avc_packet_type = SrsCodecVideoAVCType.SequenceHeader;
            SrsFlvFrameBytes flv_tag = avc.mux_avc2flv(frames, frame_type, avc_packet_type, dts, pts);

            // the timestamp in rtmp message header is dts.
            rtmp_write_packet(SrsCodecFlvTag.Video, dts, frame_type, avc_packet_type, flv_tag);

            // reset sps and pps.
            h264_sps_changed = false;
            h264_pps_changed = false;
            h264_sps_pps_sent = true;
            Log.i(TAG, String.format("flv: h264 sps/pps sent, sps=%dB, pps=%dB", h264_sps.array().length, h264_pps.array().length));
        }

        private void write_h264_ipb_frame(ArrayList<SrsFlvFrameBytes> ibps, int frame_type, int dts, int pts) {
            // when sps or pps not sent, ignore the packet.
            // @see https://github.com/simple-rtmp-server/srs/issues/203
            if (!h264_sps_pps_sent) {
                return;
            }

            int avc_packet_type = SrsCodecVideoAVCType.NALU;
            SrsFlvFrameBytes flv_tag = avc.mux_avc2flv(ibps, frame_type, avc_packet_type, dts, pts);

            // the timestamp in rtmp message header is dts.
            rtmp_write_packet(SrsCodecFlvTag.Video, dts, frame_type, avc_packet_type, flv_tag);
        }

        private void rtmp_write_packet(int type, int dts, int frame_type, int avc_aac_type, SrsFlvFrameBytes tag) {
            SrsFlvFrame frame = new SrsFlvFrame();
            frame.flvTag = ByteBuffer.allocate(tag.size);
            frame.flvTag.put(tag.data.array());
            frame.type = type;
            frame.dts = dts;
            frame.frame_type = frame_type;
            frame.avc_aac_type = avc_aac_type;

            if (needToFindKeyFrame) {
                if (frame.is_keyframe()) {
                    needToFindKeyFrame = false;
                    flvFrameCacheAdd(frame);
                }
            } else {
                flvFrameCacheAdd(frame);
            }
        }

        private void flvFrameCacheAdd(SrsFlvFrame frame) {
            frameCache.add(frame);
            if (frame.is_video()) {
                getVideoFrameCacheNumber().incrementAndGet();
            }
            synchronized (txFrameLock) {
                txFrameLock.notifyAll();
            }
        }
    }
}
