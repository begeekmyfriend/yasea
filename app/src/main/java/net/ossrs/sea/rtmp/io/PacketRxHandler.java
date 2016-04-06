package net.ossrs.sea.rtmp.io;

import net.ossrs.sea.rtmp.packets.RtmpPacket;

/**
 * Handler interface for received RTMP packets
 * @author francois
 */
public interface PacketRxHandler {
    
    public void handleRxPacket(RtmpPacket rtmpPacket);
    
    public void notifyWindowAckRequired(final int numBytesReadThusFar);    
}
