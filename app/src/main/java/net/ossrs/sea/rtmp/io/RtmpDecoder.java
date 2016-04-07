package net.ossrs.sea.rtmp.io;

import java.io.IOException;
import java.io.InputStream;
import android.util.Log;
import net.ossrs.sea.rtmp.packets.Abort;
import net.ossrs.sea.rtmp.packets.Audio;
import net.ossrs.sea.rtmp.packets.Command;
import net.ossrs.sea.rtmp.packets.Data;
import net.ossrs.sea.rtmp.packets.RtmpHeader;
import net.ossrs.sea.rtmp.packets.RtmpPacket;
import net.ossrs.sea.rtmp.packets.SetChunkSize;
import net.ossrs.sea.rtmp.packets.SetPeerBandwidth;
import net.ossrs.sea.rtmp.packets.UserControl;
import net.ossrs.sea.rtmp.packets.Video;
import net.ossrs.sea.rtmp.packets.WindowAckSize;

/**
 *
 * @author francois
 */
public class RtmpDecoder {

    private static final String TAG = "RtmpDecoder";

    private RtmpSessionInfo rtmpSessionInfo;

    public RtmpDecoder(RtmpSessionInfo rtmpSessionInfo) {
        this.rtmpSessionInfo = rtmpSessionInfo;
    }

    public RtmpPacket readPacket(InputStream in) throws IOException {

        RtmpHeader header = RtmpHeader.readHeader(in, rtmpSessionInfo);
        RtmpPacket rtmpPacket;
        Log.d(TAG, "readPacket(): header.messageType: " + header.getMessageType());

        ChunkStreamInfo chunkStreamInfo = rtmpSessionInfo.getChunkStreamInfo(header.getChunkStreamId());

        chunkStreamInfo.setPrevHeaderRx(header);

        if (header.getPacketLength() > rtmpSessionInfo.getRxChunkSize()) {
            //Log.d(TAG, "readPacket(): packet size (" + header.getPacketLength() + ") is bigger than chunk size (" + rtmpSessionInfo.getChunkSize() + "); storing chunk data");
            // This packet consists of more than one chunk; store the chunks in the chunk stream until everything is read
            if (!chunkStreamInfo.storePacketChunk(in, rtmpSessionInfo.getRxChunkSize())) {
                Log.d(TAG, " readPacket(): returning null because of incomplete packet");
                return null; // packet is not yet complete
            } else {
                Log.d(TAG, " readPacket(): stored chunks complete packet; reading packet");
                in = chunkStreamInfo.getStoredPacketInputStream();
            }
        } else {
            //Log.d(TAG, "readPacket(): packet size (" + header.getPacketLength() + ") is LESS than chunk size (" + rtmpSessionInfo.getChunkSize() + "); reading packet fully");
        }

        switch (header.getMessageType()) {

            case SET_CHUNK_SIZE: {
                SetChunkSize setChunkSize = new SetChunkSize(header);
                setChunkSize.readBody(in);
                Log.d(TAG, "readPacket(): Setting chunk size to: " + setChunkSize.getChunkSize());
                rtmpSessionInfo.setRxChunkSize(setChunkSize.getChunkSize());
                return null;
            }
            case ABORT:
                rtmpPacket = new Abort(header);
                break;
            case USER_CONTROL_MESSAGE:
                rtmpPacket = new UserControl(header);
                break;
            case WINDOW_ACKNOWLEDGEMENT_SIZE:
                rtmpPacket = new WindowAckSize(header);
                break;
            case SET_PEER_BANDWIDTH:
                rtmpPacket = new SetPeerBandwidth(header);
                break;
            case AUDIO:
                rtmpPacket = new Audio(header);
                break;
            case VIDEO:
                rtmpPacket = new Video(header);
				break;
            case COMMAND_AMF0:
                rtmpPacket = new Command(header);
                break;
            case DATA_AMF0:
                rtmpPacket = new Data(header);
                break;
            default:
                throw new IOException("No packet body implementation for message type: " + header.getMessageType());
        }                
        rtmpPacket.readBody(in);                        
        return rtmpPacket;
    }
}
