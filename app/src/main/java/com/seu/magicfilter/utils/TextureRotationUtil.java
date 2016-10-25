/*
 * Copyright (C) 2012 CyberAgent
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.seu.magicfilter.utils;

public class TextureRotationUtil {

    public static final float TEXTURE_NO_ROTATION[] = {
        0.0f, 1.0f, 0.0f, 1.0f,
        1.0f, 1.0f, 0.0f, 1.0f,
        0.0f, 0.0f, 0.0f, 1.0f,
        1.0f, 0.0f, 0.0f, 1.0f,
    };

    public static final float TEXTURE_ROTATED_90[] = {
        1.0f, 1.0f, 0.0f, 1.0f,
        1.0f, 0.0f, 0.0f, 1.0f,
        0.0f, 1.0f, 0.0f, 1.0f,
        0.0f, 0.0f, 0.0f, 1.0f,
    };
    public static final float TEXTURE_ROTATED_180[] = {
        1.0f, 0.0f, 0.0f, 1.0f,
        0.0f, 0.0f, 0.0f, 1.0f,
        1.0f, 1.0f, 0.0f, 1.0f,
        0.0f, 1.0f, 0.0f, 1.0f,
    };
    public static final float TEXTURE_ROTATED_270[] = {
        0.0f, 0.0f, 0.0f, 1.0f,
        0.0f, 1.0f, 0.0f, 1.0f,
        1.0f, 0.0f, 0.0f, 1.0f,
        1.0f, 1.0f, 0.0f, 1.0f,
    };
    
    public static final float CUBE[] = {
        -1.0f, -1.0f, 0.0f, 1.0f,
        1.0f,  -1.0f, 0.0f, 1.0f,
        -1.0f, 1.0f,  0.0f, 1.0f,
        1.0f,  1.0f,  0.0f, 1.0f,
    };
    
    private TextureRotationUtil() {}

    public static float[] getRotation(final Rotation rotation, final boolean flipHorizontal,
                                                         final boolean flipVertical) {
        float[] rotatedTex;
        switch (rotation) {
            case ROTATION_90:
                rotatedTex = TEXTURE_ROTATED_90;
                break;
            case ROTATION_180:
                rotatedTex = TEXTURE_ROTATED_180;
                break;
            case ROTATION_270:
                rotatedTex = TEXTURE_ROTATED_270;
                break;
            case NORMAL:
            default:
                rotatedTex = TEXTURE_NO_ROTATION;
                break;
        }
        if (flipHorizontal) {
            rotatedTex = new float[]{
                    flip(rotatedTex[0]), rotatedTex[1], 0.0f, 1.0f,
                    flip(rotatedTex[4]), rotatedTex[5], 0.0f, 1.0f,
                    flip(rotatedTex[8]), rotatedTex[9], 0.0f, 1.0f,
                    flip(rotatedTex[12]), rotatedTex[13], 0.0f, 1.0f,
            };
        }
        if (flipVertical) {
            rotatedTex = new float[]{
                    rotatedTex[0], flip(rotatedTex[1]), 0.0f, 1.0f,
                    rotatedTex[4], flip(rotatedTex[5]), 0.0f, 1.0f,
                    rotatedTex[8], flip(rotatedTex[9]), 0.0f, 1.0f,
                    rotatedTex[12], flip(rotatedTex[13]), 0.0f, 1.0f,
            };
        }
        return rotatedTex;
    }


    private static float flip(final float i) {
        return i == 0.0f ? 1.0f : 0.0f;
    }
}
