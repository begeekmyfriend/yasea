/*****************************************************************************
 * cabac-a.S: aarch64 cabac
 *****************************************************************************
 * Copyright (C) 2014-2017 x264 project
 *
 * Authors: Janne Grunau <janne-x264@jannau.net>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02111, USA.
 *
 * This program is also available under a commercial proprietary license.
 * For more information, contact us at licensing@x264.com.
 *****************************************************************************/

#include "asm.S"
#include "asm-offsets.h"

// w11 holds x264_cabac_t.i_low
// w12 holds x264_cabac_t.i_range

function x264_cabac_encode_decision_asm, export=1
    movrel      x8,  X(x264_cabac_range_lps)
    movrel      x9,  X(x264_cabac_transition)
    add         w10, w1, #CABAC_STATE
    ldrb        w3,  [x0,  x10]         // i_state
    ldr         w12, [x0,  #CABAC_I_RANGE]
    and         x4,  x3,  #~1
    asr         w5,  w12, #6
    add         x8,  x8,  x4, lsl #1
    sub         w5,  w5,  #4
    eor         w6,  w2,  w3            // b ^ i_state
    ldrb        w4,  [x8,  x5]          // i_range_lps
    ldr         w11, [x0, #CABAC_I_LOW]
    sub         w12, w12, w4
    tbz         w6,  #0,  1f            // (b ^ i_state) & 1
    add         w11, w11, w12
    mov         w12,  w4
1:
    orr         w4,  w2,  w3, lsl #1
    ldrb        w9,  [x9,  x4]
    strb        w9,  [x0,  x10]    // i_state

cabac_encode_renorm:
    clz         w5,  w12
    ldr         w2,  [x0, #CABAC_I_QUEUE]
    sub         w5,  w5,  #23
    lsl         w12, w12, w5
    lsl         w11, w11, w5
2:
    adds        w2,  w2,  w5
    str         w12, [x0, #CABAC_I_RANGE]
    b.lt        0f
cabac_putbyte:
    mov         w13, #0x400
    add         w12, w2,  #10
    lsl         w13, w13, w2
    asr         w4,  w11, w12           // out
    sub         w2,  w2,  #8
    sub         w13, w13, #1
    subs        w5,  w4,  #0xff
    and         w11, w11, w13
    ldr         w6,  [x0, #CABAC_I_BYTES_OUTSTANDING]
    str         w2,  [x0, #CABAC_I_QUEUE]
    b.ne        1f

    add         w6,  w6,  #1
    str         w11, [x0, #CABAC_I_LOW]
    str         w6,  [x0, #CABAC_I_BYTES_OUTSTANDING]
    ret

1:
    ldr         x7,  [x0, #CABAC_P]
    asr         w5,  w4,  #8            // carry
    ldrb        w8,  [x7, #-1]
    add         w8,  w8,  w5
    sub         w5,  w5,  #1
    strb        w8,  [x7, #-1]
    cbz         w6,  3f
2:
    subs        w6,  w6,  #1
    strb        w5,  [x7],  #1
    b.gt        2b
3:
    strb        w4,  [x7],  #1
    str         wzr, [x0, #CABAC_I_BYTES_OUTSTANDING]
    str         x7,  [x0, #CABAC_P]
0:
    str         w11, [x0, #CABAC_I_LOW]
    str         w2,  [x0, #CABAC_I_QUEUE]
    ret
endfunc

function x264_cabac_encode_bypass_asm, export=1
    ldr         w12, [x0, #CABAC_I_RANGE]
    ldr         w11, [x0, #CABAC_I_LOW]
    ldr         w2,  [x0, #CABAC_I_QUEUE]
    and         w1,  w1,  w12
    add         w11, w1,  w11, lsl #1
    adds        w2,  w2,  #1
    b.ge        cabac_putbyte
    str         w11, [x0, #CABAC_I_LOW]
    str         w2,  [x0, #CABAC_I_QUEUE]
    ret
endfunc

function x264_cabac_encode_terminal_asm, export=1
    ldr         w12, [x0, #CABAC_I_RANGE]
    ldr         w11, [x0, #CABAC_I_LOW]
    sub         w12, w12, #2
    b           cabac_encode_renorm
endfunc
