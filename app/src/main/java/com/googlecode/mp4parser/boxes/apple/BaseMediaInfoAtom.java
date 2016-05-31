package com.googlecode.mp4parser.boxes.apple;

import com.coremedia.iso.IsoTypeReader;
import com.coremedia.iso.IsoTypeWriter;
import com.googlecode.mp4parser.AbstractFullBox;

import java.nio.ByteBuffer;

public class BaseMediaInfoAtom extends AbstractFullBox {
    public static final String TYPE = "gmin";

    short graphicsMode = 64;
    int opColorR = 32768;
    int opColorG = 32768;
    int opColorB = 32768;
    short balance;
    short reserved;

    public BaseMediaInfoAtom() {
        super(TYPE);
    }

    @Override
    protected long getContentSize() {
        return 16;
    }

    @Override
    protected void getContent(ByteBuffer byteBuffer) {
        writeVersionAndFlags(byteBuffer);
        byteBuffer.putShort(graphicsMode);
        IsoTypeWriter.writeUInt16(byteBuffer, opColorR);
        IsoTypeWriter.writeUInt16(byteBuffer,opColorG );
        IsoTypeWriter.writeUInt16(byteBuffer,opColorB );
        byteBuffer.putShort(balance);
        byteBuffer.putShort(reserved);
    }

    @Override
    protected void _parseDetails(ByteBuffer content) {
        parseVersionAndFlags(content);
        graphicsMode = content.getShort();
        opColorR = IsoTypeReader.readUInt16(content);
        opColorG = IsoTypeReader.readUInt16(content);
        opColorB = IsoTypeReader.readUInt16(content);
        balance = content.getShort();
        reserved = content.getShort();

    }

    public short getGraphicsMode() {
        return graphicsMode;
    }

    public void setGraphicsMode(short graphicsMode) {
        this.graphicsMode = graphicsMode;
    }

    public int getOpColorR() {
        return opColorR;
    }

    public void setOpColorR(int opColorR) {
        this.opColorR = opColorR;
    }

    public int getOpColorG() {
        return opColorG;
    }

    public void setOpColorG(int opColorG) {
        this.opColorG = opColorG;
    }

    public int getOpColorB() {
        return opColorB;
    }

    public void setOpColorB(int opColorB) {
        this.opColorB = opColorB;
    }

    public short getBalance() {
        return balance;
    }

    public void setBalance(short balance) {
        this.balance = balance;
    }

    public short getReserved() {
        return reserved;
    }

    public void setReserved(short reserved) {
        this.reserved = reserved;
    }

    @Override
    public String toString() {
        return "BaseMediaInfoAtom{" +
                "graphicsMode=" + graphicsMode +
                ", opColorR=" + opColorR +
                ", opColorG=" + opColorG +
                ", opColorB=" + opColorB +
                ", balance=" + balance +
                ", reserved=" + reserved +
                '}';
    }
}
