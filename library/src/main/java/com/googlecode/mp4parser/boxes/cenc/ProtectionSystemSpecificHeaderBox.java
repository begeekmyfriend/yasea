package com.googlecode.mp4parser.boxes.cenc;

import com.coremedia.iso.BoxParser;
import com.coremedia.iso.IsoFile;
import com.coremedia.iso.IsoTypeReader;
import com.coremedia.iso.IsoTypeWriter;
import com.coremedia.iso.boxes.Box;
import com.googlecode.mp4parser.AbstractFullBox;
import com.googlecode.mp4parser.util.UUIDConverter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;



/**
 * This box contains information needed by a Content Protection System to play back the content. The
 * data format is specified by the system identified by the ‘pssh’ parameter SystemID, and is considered
 * opaque for the purposes of this specification.
 * <p/>
 * The data encapsulated in the Data field may be read by the identified Content Protection System to
 * enable decryption key acquisition and decryption of media data. For license/rights-based systems, the
 * header information may include data such as the URL of license server(s) or rights issuer(s) used,
 * embedded licenses/rights, and/or other protection system specific metadata.
 * <p/>
 * A single file may be constructed to be playable by multiple key and digital rights management (DRM)
 * systems, by including one Protection System-Specific Header box for each system supported. Readers
 * that process such presentations must match the SystemID field in this box to the SystemID(s) of the
 * DRM System(s) they support, and select or create the matching Protection System-Specific Header
 * box(es) for storage and retrieval of Protection-Specific information interpreted or created by that DRM
 * system.
 */
public class ProtectionSystemSpecificHeaderBox extends AbstractFullBox {
    public static final String TYPE = "pssh";

    public static byte[] OMA2_SYSTEM_ID = UUIDConverter.convert(UUID.fromString("A2B55680-6F43-11E0-9A3F-0002A5D5C51B"));
    public static byte[] PLAYREADY_SYSTEM_ID = UUIDConverter.convert(UUID.fromString("9A04F079-9840-4286-AB92-E65BE0885F95"));

    byte[] content;
    byte[] systemId;


    public byte[] getSystemId() {
        return systemId;
    }

    public void setSystemId(byte[] systemId) {
        assert systemId.length == 16;
        this.systemId = systemId;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }

    public ProtectionSystemSpecificHeaderBox() {
        super(TYPE);
    }

    @Override
    protected long getContentSize() {
        return 24 + content.length;
    }

    @Override
    protected void getContent(ByteBuffer byteBuffer) {
        writeVersionAndFlags(byteBuffer);
        assert systemId.length == 16;
        byteBuffer.put(systemId, 0, 16);
        IsoTypeWriter.writeUInt32(byteBuffer, content.length);
        byteBuffer.put(content);
    }

    @Override
    protected void _parseDetails(ByteBuffer content) {
        parseVersionAndFlags(content);
        systemId = new byte[16];
        content.get(systemId);
        long length = IsoTypeReader.readUInt32(content);
        this.content = new byte[content.remaining()];
        content.get(this.content);
        assert length == this.content.length;
    }
}
