/*
 * Copyright 2011 castLabs, Berlin
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

package com.googlecode.mp4parser.boxes.basemediaformat;

import com.googlecode.mp4parser.AbstractBox;
import com.coremedia.iso.boxes.h264.AvcConfigurationBox;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static com.googlecode.mp4parser.util.CastUtils.l2i;

/**
 * The AVC NAL Unit Storage Box SHALL contain an AVCDecoderConfigurationRecord,
 * as defined in section 5.2.4.1 of the ISO 14496-12.
 */
public class AvcNalUnitStorageBox extends AbstractBox {
    AvcConfigurationBox.AVCDecoderConfigurationRecord avcDecoderConfigurationRecord;

    public AvcNalUnitStorageBox() {
        super("avcn");
    }

    public AvcNalUnitStorageBox(AvcConfigurationBox avcConfigurationBox) {
        super("avcn");
        this.avcDecoderConfigurationRecord = avcConfigurationBox.getavcDecoderConfigurationRecord();
    }

    public AvcConfigurationBox.AVCDecoderConfigurationRecord getAvcDecoderConfigurationRecord() {
        return avcDecoderConfigurationRecord;
    }

    // just to display sps in isoviewer no practical use
    public int getLengthSizeMinusOne() {
        return avcDecoderConfigurationRecord.lengthSizeMinusOne;
    }

    public String[] getSPS() {
        return avcDecoderConfigurationRecord.getSPS();
    }

    public String[] getPPS() {
        return avcDecoderConfigurationRecord.getPPS();
    }

    public List<String> getSequenceParameterSetsAsStrings() {
        return avcDecoderConfigurationRecord.getSequenceParameterSetsAsStrings();
    }

    public List<String> getSequenceParameterSetExtsAsStrings() {
        return avcDecoderConfigurationRecord.getSequenceParameterSetExtsAsStrings();
    }

    public List<String> getPictureParameterSetsAsStrings() {
        return avcDecoderConfigurationRecord.getPictureParameterSetsAsStrings();
    }

    @Override
    protected long getContentSize() {
        return avcDecoderConfigurationRecord.getContentSize();
    }

    @Override
    public void _parseDetails(ByteBuffer content) {
        this.avcDecoderConfigurationRecord = new AvcConfigurationBox.AVCDecoderConfigurationRecord(content);
    }

    @Override
    protected void getContent(ByteBuffer byteBuffer) {
        this.avcDecoderConfigurationRecord.getContent(byteBuffer);
    }

    @Override
    public String toString() {
        return "AvcNalUnitStorageBox{" +
                "SPS=" + avcDecoderConfigurationRecord.getSequenceParameterSetsAsStrings() +
                ",PPS=" + avcDecoderConfigurationRecord.getPictureParameterSetsAsStrings() +
                ",lengthSize=" + (avcDecoderConfigurationRecord.lengthSizeMinusOne + 1) +
                '}';
    }
}
