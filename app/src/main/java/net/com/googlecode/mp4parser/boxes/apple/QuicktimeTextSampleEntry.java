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
package com.googlecode.mp4parser.boxes.apple;

import com.coremedia.iso.IsoTypeReader;
import com.coremedia.iso.IsoTypeWriter;
import com.coremedia.iso.boxes.sampleentry.SampleEntry;

import java.nio.ByteBuffer;

/**
 * Entry type for timed text samples defined in the timed text specification (ISO/IEC 14496-17).
 */
public class QuicktimeTextSampleEntry extends SampleEntry {

    public static final String TYPE = "text";

    int displayFlags;
    int textJustification;

    int backgroundR;
    int backgroundG;
    int backgroundB;

    long defaultTextBox;
    long reserved1;

    short fontNumber;
    short fontFace;
    byte reserved2;
    short reserved3;

    int foregroundR = 65535;
    int foregroundG = 65535;
    int foregroundB = 65535;

    String fontName = "";

    public QuicktimeTextSampleEntry() {
        super(TYPE);
    }

    @Override
    public void _parseDetails(ByteBuffer content) {
        _parseReservedAndDataReferenceIndex(content);

        displayFlags = content.getInt();
        textJustification = content.getInt();
        backgroundR = IsoTypeReader.readUInt16(content);
        backgroundG = IsoTypeReader.readUInt16(content);
        backgroundB = IsoTypeReader.readUInt16(content);
        defaultTextBox = IsoTypeReader.readUInt64(content);
        reserved1 = IsoTypeReader.readUInt64(content);
        fontNumber = content.getShort();
        fontFace = content.getShort();
        reserved2 = content.get();
        reserved3 = content.getShort();
        foregroundR = IsoTypeReader.readUInt16(content);
        foregroundG = IsoTypeReader.readUInt16(content);
        foregroundB = IsoTypeReader.readUInt16(content);

        if (content.remaining() > 0) {
            int length = IsoTypeReader.readUInt8(content);
            byte[] myFontName = new byte[length];
            content.get(myFontName);
            fontName = new String(myFontName);
        } else {
            fontName = null;
        }
    }


    protected long getContentSize() {
        return 52 + (fontName != null ? fontName.length() : 0);
    }


    public int getDisplayFlags() {
        return displayFlags;
    }

    public void setDisplayFlags(int displayFlags) {
        this.displayFlags = displayFlags;
    }

    public int getTextJustification() {
        return textJustification;
    }

    public void setTextJustification(int textJustification) {
        this.textJustification = textJustification;
    }

    public int getBackgroundR() {
        return backgroundR;
    }

    public void setBackgroundR(int backgroundR) {
        this.backgroundR = backgroundR;
    }

    public int getBackgroundG() {
        return backgroundG;
    }

    public void setBackgroundG(int backgroundG) {
        this.backgroundG = backgroundG;
    }

    public int getBackgroundB() {
        return backgroundB;
    }

    public void setBackgroundB(int backgroundB) {
        this.backgroundB = backgroundB;
    }

    public long getDefaultTextBox() {
        return defaultTextBox;
    }

    public void setDefaultTextBox(long defaultTextBox) {
        this.defaultTextBox = defaultTextBox;
    }

    public long getReserved1() {
        return reserved1;
    }

    public void setReserved1(long reserved1) {
        this.reserved1 = reserved1;
    }

    public short getFontNumber() {
        return fontNumber;
    }

    public void setFontNumber(short fontNumber) {
        this.fontNumber = fontNumber;
    }

    public short getFontFace() {
        return fontFace;
    }

    public void setFontFace(short fontFace) {
        this.fontFace = fontFace;
    }

    public byte getReserved2() {
        return reserved2;
    }

    public void setReserved2(byte reserved2) {
        this.reserved2 = reserved2;
    }

    public short getReserved3() {
        return reserved3;
    }

    public void setReserved3(short reserved3) {
        this.reserved3 = reserved3;
    }

    public int getForegroundR() {
        return foregroundR;
    }

    public void setForegroundR(int foregroundR) {
        this.foregroundR = foregroundR;
    }

    public int getForegroundG() {
        return foregroundG;
    }

    public void setForegroundG(int foregroundG) {
        this.foregroundG = foregroundG;
    }

    public int getForegroundB() {
        return foregroundB;
    }

    public void setForegroundB(int foregroundB) {
        this.foregroundB = foregroundB;
    }

    public String getFontName() {
        return fontName;
    }

    public void setFontName(String fontName) {
        this.fontName = fontName;
    }

    @Override
    protected void getContent(ByteBuffer byteBuffer) {
        _writeReservedAndDataReferenceIndex(byteBuffer);
        byteBuffer.putInt(displayFlags);
        byteBuffer.putInt(textJustification);
        IsoTypeWriter.writeUInt16(byteBuffer, backgroundR);
        IsoTypeWriter.writeUInt16(byteBuffer, backgroundG);
        IsoTypeWriter.writeUInt16(byteBuffer, backgroundB);
        IsoTypeWriter.writeUInt64(byteBuffer, defaultTextBox);
        IsoTypeWriter.writeUInt64(byteBuffer, reserved1);
        byteBuffer.putShort(fontNumber);
        byteBuffer.putShort(fontFace);
        byteBuffer.put(reserved2);
        byteBuffer.putShort(reserved3);

        IsoTypeWriter.writeUInt16(byteBuffer, foregroundR);
        IsoTypeWriter.writeUInt16(byteBuffer, foregroundG);
        IsoTypeWriter.writeUInt16(byteBuffer, foregroundB);
        if (fontName != null) {
            IsoTypeWriter.writeUInt8(byteBuffer, fontName.length());
            byteBuffer.put(fontName.getBytes());
        }

    }


}
