;*****************************************************************************
;* mc-a2.asm: x86 motion compensation
;*****************************************************************************
;* Copyright (C) 2005-2016 x264 project
;*
;* Authors: Loren Merritt <lorenm@u.washington.edu>
;*          Fiona Glaser <fiona@x264.com>
;*          Holger Lubitz <holger@lubitz.org>
;*          Mathieu Monnier <manao@melix.net>
;*          Oskar Arvidsson <oskar@irock.se>
;*
;* This program is free software; you can redistribute it and/or modify
;* it under the terms of the GNU General Public License as published by
;* the Free Software Foundation; either version 2 of the License, or
;* (at your option) any later version.
;*
;* This program is distributed in the hope that it will be useful,
;* but WITHOUT ANY WARRANTY; without even the implied warranty of
;* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;* GNU General Public License for more details.
;*
;* You should have received a copy of the GNU General Public License
;* along with this program; if not, write to the Free Software
;* Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02111, USA.
;*
;* This program is also available under a commercial proprietary license.
;* For more information, contact us at licensing@x264.com.
;*****************************************************************************

%include "x86inc.asm"
%include "x86util.asm"

SECTION_RODATA 32

pw_1024: times 16 dw 1024
filt_mul20: times 32 db 20
filt_mul15: times 16 db 1, -5
filt_mul51: times 16 db -5, 1
hpel_shuf: times 2 db 0,8,1,9,2,10,3,11,4,12,5,13,6,14,7,15
deinterleave_shuf: times 2 db 0,2,4,6,8,10,12,14,1,3,5,7,9,11,13,15

%if HIGH_BIT_DEPTH
copy_swap_shuf: times 2 db 2,3,0,1,6,7,4,5,10,11,8,9,14,15,12,13
v210_mask: times 4 dq 0xc00ffc003ff003ff
v210_luma_shuf: times 2 db 1,2,4,5,6,7,9,10,12,13,14,15,12,13,14,15
v210_chroma_shuf: times 2 db 0,1,2,3,5,6,8,9,10,11,13,14,10,11,13,14
; vpermd indices {0,1,2,4,5,7,_,_} merged in the 3 lsb of each dword to save a register
v210_mult: dw 0x2000,0x7fff,0x0801,0x2000,0x7ffa,0x0800,0x7ffc,0x0800
           dw 0x1ffd,0x7fff,0x07ff,0x2000,0x7fff,0x0800,0x7fff,0x0800

deinterleave_shuf32a: SHUFFLE_MASK_W 0,2,4,6,8,10,12,14
deinterleave_shuf32b: SHUFFLE_MASK_W 1,3,5,7,9,11,13,15
%else
copy_swap_shuf: times 2 db 1,0,3,2,5,4,7,6,9,8,11,10,13,12,15,14
deinterleave_rgb_shuf: db 0,3,6,9,1,4,7,10,2,5,8,11,-1,-1,-1,-1
                       db 0,4,8,12,1,5,9,13,2,6,10,14,-1,-1,-1,-1

deinterleave_shuf32a: db 0,2,4,6,8,10,12,14,16,18,20,22,24,26,28,30
deinterleave_shuf32b: db 1,3,5,7,9,11,13,15,17,19,21,23,25,27,29,31
%endif ; !HIGH_BIT_DEPTH

mbtree_fix8_unpack_shuf: db -1,-1, 1, 0,-1,-1, 3, 2,-1,-1, 5, 4,-1,-1, 7, 6
                         db -1,-1, 9, 8,-1,-1,11,10,-1,-1,13,12,-1,-1,15,14
mbtree_fix8_pack_shuf:   db  1, 0, 3, 2, 5, 4, 7, 6, 9, 8,11,10,13,12,15,14

pf_256:    times 4 dd 256.0
pf_inv256: times 4 dd 0.00390625

pd_16: times 4 dd 16
pd_0f: times 4 dd 0xffff

pad10: times 8 dw    10*PIXEL_MAX
pad20: times 8 dw    20*PIXEL_MAX
pad30: times 8 dw    30*PIXEL_MAX
depad: times 4 dd 32*20*PIXEL_MAX + 512

tap1: times 4 dw  1, -5
tap2: times 4 dw 20, 20
tap3: times 4 dw -5,  1

pw_0xc000: times 8 dw 0xc000
pw_31: times 8 dw 31
pd_4: times 4 dd 4

SECTION .text

cextern pb_0
cextern pw_1
cextern pw_8
cextern pw_16
cextern pw_32
cextern pw_512
cextern pw_00ff
cextern pw_3fff
cextern pw_pixel_max
cextern pw_0to15
cextern pd_ffff

%macro LOAD_ADD 4
    movh       %4, %3
    movh       %1, %2
    punpcklbw  %4, m0
    punpcklbw  %1, m0
    paddw      %1, %4
%endmacro

%macro LOAD_ADD_2 6
    mova       %5, %3
    mova       %1, %4
    punpckhbw  %6, %5, m0
    punpcklbw  %5, m0
    punpckhbw  %2, %1, m0
    punpcklbw  %1, m0
    paddw      %1, %5
    paddw      %2, %6
%endmacro

%macro FILT_V2 6
    psubw  %1, %2  ; a-b
    psubw  %4, %5
    psubw  %2, %3  ; b-c
    psubw  %5, %6
    psllw  %2, 2
    psllw  %5, 2
    psubw  %1, %2  ; a-5*b+4*c
    psllw  %3, 4
    psubw  %4, %5
    psllw  %6, 4
    paddw  %1, %3  ; a-5*b+20*c
    paddw  %4, %6
%endmacro

%macro FILT_H 3
    psubw  %1, %2  ; a-b
    psraw  %1, 2   ; (a-b)/4
    psubw  %1, %2  ; (a-b)/4-b
    paddw  %1, %3  ; (a-b)/4-b+c
    psraw  %1, 2   ; ((a-b)/4-b+c)/4
    paddw  %1, %3  ; ((a-b)/4-b+c)/4+c = (a-5*b+20*c)/16
%endmacro

%macro FILT_H2 6
    psubw  %1, %2
    psubw  %4, %5
    psraw  %1, 2
    psraw  %4, 2
    psubw  %1, %2
    psubw  %4, %5
    paddw  %1, %3
    paddw  %4, %6
    psraw  %1, 2
    psraw  %4, 2
    paddw  %1, %3
    paddw  %4, %6
%endmacro

%macro FILT_PACK 3-5
%if cpuflag(ssse3)
    pmulhrsw %1, %3
    pmulhrsw %2, %3
%else
    paddw    %1, %3
    paddw    %2, %3
%if %0 == 5
    psubusw  %1, %5
    psubusw  %2, %5
    psrlw    %1, %4
    psrlw    %2, %4
%else
    psraw    %1, %4
    psraw    %2, %4
%endif
%endif
%if HIGH_BIT_DEPTH == 0
    packuswb %1, %2
%endif
%endmacro

;The hpel_filter routines use non-temporal writes for output.
;The following defines may be uncommented for testing.
;Doing the hpel_filter temporal may be a win if the last level cache
;is big enough (preliminary benching suggests on the order of 4* framesize).

;%define movntq movq
;%define movntps movaps
;%define sfence

%if HIGH_BIT_DEPTH
;-----------------------------------------------------------------------------
; void hpel_filter_v( uint16_t *dst, uint16_t *src, int16_t *buf, intptr_t stride, intptr_t width );
;-----------------------------------------------------------------------------
%macro HPEL_FILTER 0
cglobal hpel_filter_v, 5,6,11
    FIX_STRIDES r3, r4
    lea        r5, [r1+r3]
    sub        r1, r3
    sub        r1, r3
%if num_mmregs > 8
    mova       m8, [pad10]
    mova       m9, [pad20]
    mova      m10, [pad30]
    %define s10 m8
    %define s20 m9
    %define s30 m10
%else
    %define s10 [pad10]
    %define s20 [pad20]
    %define s30 [pad30]
%endif
    add        r0, r4
    add        r2, r4
    neg        r4
    mova       m7, [pw_pixel_max]
    pxor       m0, m0
.loop:
    mova       m1, [r1]
    mova       m2, [r1+r3]
    mova       m3, [r1+r3*2]
    mova       m4, [r1+mmsize]
    mova       m5, [r1+r3+mmsize]
    mova       m6, [r1+r3*2+mmsize]
    paddw      m1, [r5+r3*2]
    paddw      m2, [r5+r3]
    paddw      m3, [r5]
    paddw      m4, [r5+r3*2+mmsize]
    paddw      m5, [r5+r3+mmsize]
    paddw      m6, [r5+mmsize]
    add        r1, 2*mmsize
    add        r5, 2*mmsize
    FILT_V2    m1, m2, m3, m4, m5, m6
    mova       m6, [pw_16]
    psubw      m1, s20
    psubw      m4, s20
    mova      [r2+r4], m1
    mova      [r2+r4+mmsize], m4
    paddw      m1, s30
    paddw      m4, s30
    FILT_PACK  m1, m4, m6, 5, s10
    CLIPW      m1, m0, m7
    CLIPW      m4, m0, m7
    mova      [r0+r4], m1
    mova      [r0+r4+mmsize], m4
    add        r4, 2*mmsize
    jl .loop
    RET

;-----------------------------------------------------------------------------
; void hpel_filter_c( uint16_t *dst, int16_t *buf, intptr_t width );
;-----------------------------------------------------------------------------
cglobal hpel_filter_c, 3,3,10
    add        r2, r2
    add        r0, r2
    add        r1, r2
    neg        r2
    mova       m0, [tap1]
    mova       m7, [tap3]
%if num_mmregs > 8
    mova       m8, [tap2]
    mova       m9, [depad]
    %define s1 m8
    %define s2 m9
%else
    %define s1 [tap2]
    %define s2 [depad]
%endif
.loop:
    movu       m1, [r1+r2-4]
    movu       m2, [r1+r2-2]
    mova       m3, [r1+r2+0]
    movu       m4, [r1+r2+2]
    movu       m5, [r1+r2+4]
    movu       m6, [r1+r2+6]
    pmaddwd    m1, m0
    pmaddwd    m2, m0
    pmaddwd    m3, s1
    pmaddwd    m4, s1
    pmaddwd    m5, m7
    pmaddwd    m6, m7
    paddd      m1, s2
    paddd      m2, s2
    paddd      m3, m5
    paddd      m4, m6
    paddd      m1, m3
    paddd      m2, m4
    psrad      m1, 10
    psrad      m2, 10
    pslld      m2, 16
    pand       m1, [pd_0f]
    por        m1, m2
    CLIPW      m1, [pb_0], [pw_pixel_max]
    mova  [r0+r2], m1
    add        r2, mmsize
    jl .loop
    RET

;-----------------------------------------------------------------------------
; void hpel_filter_h( uint16_t *dst, uint16_t *src, intptr_t width );
;-----------------------------------------------------------------------------
cglobal hpel_filter_h, 3,4,8
    %define src r1+r2
    add        r2, r2
    add        r0, r2
    add        r1, r2
    neg        r2
    mova       m0, [pw_pixel_max]
.loop:
    movu       m1, [src-4]
    movu       m2, [src-2]
    mova       m3, [src+0]
    movu       m6, [src+2]
    movu       m4, [src+4]
    movu       m5, [src+6]
    paddw      m3, m6 ; c0
    paddw      m2, m4 ; b0
    paddw      m1, m5 ; a0
%if mmsize == 16
    movu       m4, [src-4+mmsize]
    movu       m5, [src-2+mmsize]
%endif
    movu       m7, [src+4+mmsize]
    movu       m6, [src+6+mmsize]
    paddw      m5, m7 ; b1
    paddw      m4, m6 ; a1
    movu       m7, [src+2+mmsize]
    mova       m6, [src+0+mmsize]
    paddw      m6, m7 ; c1
    FILT_H2    m1, m2, m3, m4, m5, m6
    mova       m7, [pw_1]
    pxor       m2, m2
    FILT_PACK  m1, m4, m7, 1
    CLIPW      m1, m2, m0
    CLIPW      m4, m2, m0
    mova      [r0+r2], m1
    mova      [r0+r2+mmsize], m4
    add        r2, mmsize*2
    jl .loop
    RET
%endmacro ; HPEL_FILTER

INIT_MMX mmx2
HPEL_FILTER
INIT_XMM sse2
HPEL_FILTER
%endif ; HIGH_BIT_DEPTH

%if HIGH_BIT_DEPTH == 0
%macro HPEL_V 1
;-----------------------------------------------------------------------------
; void hpel_filter_v( uint8_t *dst, uint8_t *src, int16_t *buf, intptr_t stride, intptr_t width );
;-----------------------------------------------------------------------------
cglobal hpel_filter_v, 5,6,%1
    lea r5, [r1+r3]
    sub r1, r3
    sub r1, r3
    add r0, r4
    lea r2, [r2+r4*2]
    neg r4
%if cpuflag(ssse3)
    mova m0, [filt_mul15]
%else
    pxor m0, m0
%endif
.loop:
%if cpuflag(ssse3)
    mova m1, [r1]
    mova m4, [r1+r3]
    mova m2, [r5+r3*2]
    mova m5, [r5+r3]
    mova m3, [r1+r3*2]
    mova m6, [r5]
    SBUTTERFLY bw, 1, 4, 7
    SBUTTERFLY bw, 2, 5, 7
    SBUTTERFLY bw, 3, 6, 7
    pmaddubsw m1, m0
    pmaddubsw m4, m0
    pmaddubsw m2, m0
    pmaddubsw m5, m0
    pmaddubsw m3, [filt_mul20]
    pmaddubsw m6, [filt_mul20]
    paddw  m1, m2
    paddw  m4, m5
    paddw  m1, m3
    paddw  m4, m6
    mova   m7, [pw_1024]
%else
    LOAD_ADD_2 m1, m4, [r1     ], [r5+r3*2], m6, m7            ; a0 / a1
    LOAD_ADD_2 m2, m5, [r1+r3  ], [r5+r3  ], m6, m7            ; b0 / b1
    LOAD_ADD   m3,     [r1+r3*2], [r5     ], m7                ; c0
    LOAD_ADD   m6,     [r1+r3*2+mmsize/2], [r5+mmsize/2], m7   ; c1
    FILT_V2 m1, m2, m3, m4, m5, m6
    mova   m7, [pw_16]
%endif
%if mmsize==32
    mova         [r2+r4*2], xm1
    mova         [r2+r4*2+mmsize/2], xm4
    vextracti128 [r2+r4*2+mmsize], m1, 1
    vextracti128 [r2+r4*2+mmsize*3/2], m4, 1
%else
    mova      [r2+r4*2], m1
    mova      [r2+r4*2+mmsize], m4
%endif
    FILT_PACK m1, m4, m7, 5
    movnta    [r0+r4], m1
    add r1, mmsize
    add r5, mmsize
    add r4, mmsize
    jl .loop
    RET
%endmacro

;-----------------------------------------------------------------------------
; void hpel_filter_c( uint8_t *dst, int16_t *buf, intptr_t width );
;-----------------------------------------------------------------------------
INIT_MMX mmx2
cglobal hpel_filter_c, 3,3
    add r0, r2
    lea r1, [r1+r2*2]
    neg r2
    %define src r1+r2*2
    movq m7, [pw_32]
.loop:
    movq   m1, [src-4]
    movq   m2, [src-2]
    movq   m3, [src  ]
    movq   m4, [src+4]
    movq   m5, [src+6]
    paddw  m3, [src+2]  ; c0
    paddw  m2, m4       ; b0
    paddw  m1, m5       ; a0
    movq   m6, [src+8]
    paddw  m4, [src+14] ; a1
    paddw  m5, [src+12] ; b1
    paddw  m6, [src+10] ; c1
    FILT_H2 m1, m2, m3, m4, m5, m6
    FILT_PACK m1, m4, m7, 6
    movntq [r0+r2], m1
    add r2, 8
    jl .loop
    RET

;-----------------------------------------------------------------------------
; void hpel_filter_h( uint8_t *dst, uint8_t *src, intptr_t width );
;-----------------------------------------------------------------------------
INIT_MMX mmx2
cglobal hpel_filter_h, 3,3
    add r0, r2
    add r1, r2
    neg r2
    %define src r1+r2
    pxor m0, m0
.loop:
    movd       m1, [src-2]
    movd       m2, [src-1]
    movd       m3, [src  ]
    movd       m6, [src+1]
    movd       m4, [src+2]
    movd       m5, [src+3]
    punpcklbw  m1, m0
    punpcklbw  m2, m0
    punpcklbw  m3, m0
    punpcklbw  m6, m0
    punpcklbw  m4, m0
    punpcklbw  m5, m0
    paddw      m3, m6 ; c0
    paddw      m2, m4 ; b0
    paddw      m1, m5 ; a0
    movd       m7, [src+7]
    movd       m6, [src+6]
    punpcklbw  m7, m0
    punpcklbw  m6, m0
    paddw      m4, m7 ; c1
    paddw      m5, m6 ; b1
    movd       m7, [src+5]
    movd       m6, [src+4]
    punpcklbw  m7, m0
    punpcklbw  m6, m0
    paddw      m6, m7 ; a1
    movq       m7, [pw_1]
    FILT_H2 m1, m2, m3, m4, m5, m6
    FILT_PACK m1, m4, m7, 1
    movntq     [r0+r2], m1
    add r2, 8
    jl .loop
    RET

%macro HPEL_C 0
;-----------------------------------------------------------------------------
; void hpel_filter_c( uint8_t *dst, int16_t *buf, intptr_t width );
;-----------------------------------------------------------------------------
cglobal hpel_filter_c, 3,3,9
    add r0, r2
    lea r1, [r1+r2*2]
    neg r2
    %define src r1+r2*2
%ifnidn cpuname, sse2
%if cpuflag(ssse3)
    mova    m7, [pw_512]
%else
    mova    m7, [pw_32]
%endif
    %define pw_rnd m7
%elif ARCH_X86_64
    mova    m8, [pw_32]
    %define pw_rnd m8
%else
    %define pw_rnd [pw_32]
%endif
; This doesn't seem to be faster (with AVX) on Sandy Bridge or Bulldozer...
%if mmsize==32
.loop:
    movu    m4, [src-4]
    movu    m5, [src-2]
    mova    m6, [src+0]
    movu    m3, [src-4+mmsize]
    movu    m2, [src-2+mmsize]
    mova    m1, [src+0+mmsize]
    paddw   m4, [src+6]
    paddw   m5, [src+4]
    paddw   m6, [src+2]
    paddw   m3, [src+6+mmsize]
    paddw   m2, [src+4+mmsize]
    paddw   m1, [src+2+mmsize]
    FILT_H2 m4, m5, m6, m3, m2, m1
%else
    mova      m0, [src-16]
    mova      m1, [src]
.loop:
    mova      m2, [src+16]
    PALIGNR   m4, m1, m0, 12, m7
    PALIGNR   m5, m1, m0, 14, m0
    PALIGNR   m0, m2, m1, 6, m7
    paddw     m4, m0
    PALIGNR   m0, m2, m1, 4, m7
    paddw     m5, m0
    PALIGNR   m6, m2, m1, 2, m7
    paddw     m6, m1
    FILT_H    m4, m5, m6

    mova      m0, m2
    mova      m5, m2
    PALIGNR   m2, m1, 12, m7
    PALIGNR   m5, m1, 14, m1
    mova      m1, [src+32]
    PALIGNR   m3, m1, m0, 6, m7
    paddw     m3, m2
    PALIGNR   m6, m1, m0, 4, m7
    paddw     m5, m6
    PALIGNR   m6, m1, m0, 2, m7
    paddw     m6, m0
    FILT_H    m3, m5, m6
%endif
    FILT_PACK m4, m3, pw_rnd, 6
%if mmsize==32
    vpermq    m4, m4, q3120
%endif
    movnta [r0+r2], m4
    add       r2, mmsize
    jl .loop
    RET
%endmacro

;-----------------------------------------------------------------------------
; void hpel_filter_h( uint8_t *dst, uint8_t *src, intptr_t width );
;-----------------------------------------------------------------------------
INIT_XMM sse2
cglobal hpel_filter_h, 3,3,8
    add r0, r2
    add r1, r2
    neg r2
    %define src r1+r2
    pxor m0, m0
.loop:
    movh       m1, [src-2]
    movh       m2, [src-1]
    movh       m3, [src  ]
    movh       m4, [src+1]
    movh       m5, [src+2]
    movh       m6, [src+3]
    punpcklbw  m1, m0
    punpcklbw  m2, m0
    punpcklbw  m3, m0
    punpcklbw  m4, m0
    punpcklbw  m5, m0
    punpcklbw  m6, m0
    paddw      m3, m4 ; c0
    paddw      m2, m5 ; b0
    paddw      m1, m6 ; a0
    movh       m4, [src+6]
    movh       m5, [src+7]
    movh       m6, [src+10]
    movh       m7, [src+11]
    punpcklbw  m4, m0
    punpcklbw  m5, m0
    punpcklbw  m6, m0
    punpcklbw  m7, m0
    paddw      m5, m6 ; b1
    paddw      m4, m7 ; a1
    movh       m6, [src+8]
    movh       m7, [src+9]
    punpcklbw  m6, m0
    punpcklbw  m7, m0
    paddw      m6, m7 ; c1
    mova       m7, [pw_1] ; FIXME xmm8
    FILT_H2 m1, m2, m3, m4, m5, m6
    FILT_PACK m1, m4, m7, 1
    movntps    [r0+r2], m1
    add r2, 16
    jl .loop
    RET

;-----------------------------------------------------------------------------
; void hpel_filter_h( uint8_t *dst, uint8_t *src, intptr_t width );
;-----------------------------------------------------------------------------
%macro HPEL_H 0
cglobal hpel_filter_h, 3,3
    add r0, r2
    add r1, r2
    neg r2
    %define src r1+r2
    mova      m0, [src-16]
    mova      m1, [src]
    mova      m7, [pw_1024]
.loop:
    mova      m2, [src+16]
    ; Using unaligned loads instead of palignr is marginally slower on SB and significantly
    ; slower on Bulldozer, despite their fast load units -- even though it would let us avoid
    ; the repeated loads of constants for pmaddubsw.
    palignr   m3, m1, m0, 14
    palignr   m4, m1, m0, 15
    palignr   m0, m2, m1, 2
    pmaddubsw m3, [filt_mul15]
    pmaddubsw m4, [filt_mul15]
    pmaddubsw m0, [filt_mul51]
    palignr   m5, m2, m1, 1
    palignr   m6, m2, m1, 3
    paddw     m3, m0
    mova      m0, m1
    pmaddubsw m1, [filt_mul20]
    pmaddubsw m5, [filt_mul20]
    pmaddubsw m6, [filt_mul51]
    paddw     m3, m1
    paddw     m4, m5
    paddw     m4, m6
    FILT_PACK m3, m4, m7, 5
    pshufb    m3, [hpel_shuf]
    mova      m1, m2
    movntps [r0+r2], m3
    add r2, 16
    jl .loop
    RET
%endmacro

INIT_MMX mmx2
HPEL_V 0
INIT_XMM sse2
HPEL_V 8
%if ARCH_X86_64 == 0
INIT_XMM sse2
HPEL_C
INIT_XMM ssse3
HPEL_C
HPEL_V 0
HPEL_H
INIT_XMM avx
HPEL_C
HPEL_V 0
HPEL_H
INIT_YMM avx2
HPEL_V 8
HPEL_C

INIT_YMM avx2
cglobal hpel_filter_h, 3,3,8
    add       r0, r2
    add       r1, r2
    neg       r2
    %define src r1+r2
    mova      m5, [filt_mul15]
    mova      m6, [filt_mul20]
    mova      m7, [filt_mul51]
.loop:
    movu      m0, [src-2]
    movu      m1, [src-1]
    movu      m2, [src+2]
    pmaddubsw m0, m5
    pmaddubsw m1, m5
    pmaddubsw m2, m7
    paddw     m0, m2

    mova      m2, [src+0]
    movu      m3, [src+1]
    movu      m4, [src+3]
    pmaddubsw m2, m6
    pmaddubsw m3, m6
    pmaddubsw m4, m7
    paddw     m0, m2
    paddw     m1, m3
    paddw     m1, m4

    mova      m2, [pw_1024]
    FILT_PACK m0, m1, m2, 5
    pshufb    m0, [hpel_shuf]
    movnta [r0+r2], m0
    add       r2, mmsize
    jl .loop
    RET
%endif

%if ARCH_X86_64
%macro DO_FILT_V 5
    ;The optimum prefetch distance is difficult to determine in checkasm:
    ;any prefetch seems slower than not prefetching.
    ;In real use, the prefetch seems to be a slight win.
    ;+mmsize is picked somewhat arbitrarily here based on the fact that even one
    ;loop iteration is going to take longer than the prefetch.
    prefetcht0 [r1+r2*2+mmsize]
%if cpuflag(ssse3)
    mova m1, [r3]
    mova m2, [r3+r2]
    mova %3, [r3+r2*2]
    mova m3, [r1]
    mova %1, [r1+r2]
    mova %2, [r1+r2*2]
    punpckhbw m4, m1, m2
    punpcklbw m1, m2
    punpckhbw m2, %1, %2
    punpcklbw %1, %2
    punpckhbw %2, m3, %3
    punpcklbw m3, %3

    pmaddubsw m1, m12
    pmaddubsw m4, m12
    pmaddubsw %1, m0
    pmaddubsw m2, m0
    pmaddubsw m3, m14
    pmaddubsw %2, m14

    paddw m1, %1
    paddw m4, m2
    paddw m1, m3
    paddw m4, %2
%else
    LOAD_ADD_2 m1, m4, [r3     ], [r1+r2*2], m2, m5            ; a0 / a1
    LOAD_ADD_2 m2, m5, [r3+r2  ], [r1+r2  ], m3, m6            ; b0 / b1
    LOAD_ADD_2 m3, m6, [r3+r2*2], [r1     ], %3, %4            ; c0 / c1
    packuswb %3, %4
    FILT_V2 m1, m2, m3, m4, m5, m6
%endif
    add       r3, mmsize
    add       r1, mmsize
%if mmsize==32
    vinserti128 %1, m1, xm4, 1
    vperm2i128  %2, m1, m4, q0301
%else
    mova      %1, m1
    mova      %2, m4
%endif
    FILT_PACK m1, m4, m15, 5
    movntps  [r8+r4+%5], m1
%endmacro

%macro FILT_C 3
%if mmsize==32
    vperm2i128 m3, %2, %1, q0003
%endif
    PALIGNR   m1, %2, %1, (mmsize-4), m3
    PALIGNR   m2, %2, %1, (mmsize-2), m3
%if mmsize==32
    vperm2i128 %1, %3, %2, q0003
%endif
    PALIGNR   m3, %3, %2, 4, %1
    PALIGNR   m4, %3, %2, 2, %1
    paddw     m3, m2
%if mmsize==32
    mova      m2, %1
%endif
    mova      %1, %3
    PALIGNR   %3, %3, %2, 6, m2
    paddw     m4, %2
    paddw     %3, m1
    FILT_H    %3, m3, m4
%endmacro

%macro DO_FILT_C 4
    FILT_C %1, %2, %3
    FILT_C %2, %1, %4
    FILT_PACK %3, %4, m15, 6
%if mmsize==32
    vpermq %3, %3, q3120
%endif
    movntps   [r5+r4], %3
%endmacro

%macro ADD8TO16 5
    punpckhbw %3, %1, %5
    punpcklbw %1, %5
    punpcklbw %4, %2, %5
    punpckhbw %2, %5
    paddw     %2, %3
    paddw     %1, %4
%endmacro

%macro DO_FILT_H 3
%if mmsize==32
    vperm2i128 m3, %2, %1, q0003
%endif
    PALIGNR   m1, %2, %1, (mmsize-2), m3
    PALIGNR   m2, %2, %1, (mmsize-1), m3
%if mmsize==32
    vperm2i128 m3, %3, %2, q0003
%endif
    PALIGNR   m4, %3, %2, 1 , m3
    PALIGNR   m5, %3, %2, 2 , m3
    PALIGNR   m6, %3, %2, 3 , m3
    mova      %1, %2
%if cpuflag(ssse3)
    pmaddubsw m1, m12
    pmaddubsw m2, m12
    pmaddubsw %2, m14
    pmaddubsw m4, m14
    pmaddubsw m5, m0
    pmaddubsw m6, m0
    paddw     m1, %2
    paddw     m2, m4
    paddw     m1, m5
    paddw     m2, m6
    FILT_PACK m1, m2, m15, 5
    pshufb    m1, [hpel_shuf]
%else ; ssse3, avx
    ADD8TO16  m1, m6, m12, m3, m0 ; a
    ADD8TO16  m2, m5, m12, m3, m0 ; b
    ADD8TO16  %2, m4, m12, m3, m0 ; c
    FILT_V2   m1, m2, %2, m6, m5, m4
    FILT_PACK m1, m6, m15, 5
%endif
    movntps [r0+r4], m1
    mova      %2, %3
%endmacro

%macro HPEL 0
;-----------------------------------------------------------------------------
; void hpel_filter( uint8_t *dsth, uint8_t *dstv, uint8_t *dstc,
;                   uint8_t *src, intptr_t stride, int width, int height )
;-----------------------------------------------------------------------------
cglobal hpel_filter, 7,9,16
    mov       r7, r3
    sub      r5d, mmsize
    mov       r8, r1
    and       r7, mmsize-1
    sub       r3, r7
    add       r0, r5
    add       r8, r5
    add       r7, r5
    add       r5, r2
    mov       r2, r4
    neg       r7
    lea       r1, [r3+r2]
    sub       r3, r2
    sub       r3, r2
    mov       r4, r7
%if cpuflag(ssse3)
    mova      m0, [filt_mul51]
    mova     m12, [filt_mul15]
    mova     m14, [filt_mul20]
    mova     m15, [pw_1024]
%else
    pxor      m0, m0
    mova     m15, [pw_16]
%endif
;ALIGN 16
.loopy:
; first filter_v
    DO_FILT_V m8, m7, m13, m12, 0
;ALIGN 16
.loopx:
    DO_FILT_V m6, m5, m11, m12, mmsize
.lastx:
%if cpuflag(ssse3)
    psrlw   m15, 1   ; pw_512
%else
    paddw   m15, m15 ; pw_32
%endif
    DO_FILT_C m9, m8, m7, m6
%if cpuflag(ssse3)
    paddw   m15, m15 ; pw_1024
%else
    psrlw   m15, 1   ; pw_16
%endif
    mova     m7, m5
    DO_FILT_H m10, m13, m11
    add      r4, mmsize
    jl .loopx
    cmp      r4, mmsize
    jl .lastx
; setup regs for next y
    sub      r4, r7
    sub      r4, r2
    sub      r1, r4
    sub      r3, r4
    add      r0, r2
    add      r8, r2
    add      r5, r2
    mov      r4, r7
    sub     r6d, 1
    jg .loopy
    sfence
    RET
%endmacro

INIT_XMM sse2
HPEL
INIT_XMM ssse3
HPEL
INIT_XMM avx
HPEL
INIT_YMM avx2
HPEL
%endif ; ARCH_X86_64

%undef movntq
%undef movntps
%undef sfence
%endif ; !HIGH_BIT_DEPTH

%macro PREFETCHNT_ITER 2 ; src, bytes/iteration
    %assign %%i 4*(%2) ; prefetch 4 iterations ahead. is this optimal?
    %rep (%2+63) / 64  ; assume 64 byte cache lines
        prefetchnta [%1+%%i]
        %assign %%i %%i + 64
    %endrep
%endmacro

;-----------------------------------------------------------------------------
; void plane_copy(_swap)_core( pixel *dst, intptr_t i_dst,
;                              pixel *src, intptr_t i_src, int w, int h )
;-----------------------------------------------------------------------------
; assumes i_dst and w are multiples of mmsize, and i_dst>w
%macro PLANE_COPY_CORE 1 ; swap
%if %1
cglobal plane_copy_swap_core, 6,7
    mova   m4, [copy_swap_shuf]
%else
cglobal plane_copy_core, 6,7
%endif
    FIX_STRIDES r1, r3
%if %1 && HIGH_BIT_DEPTH
    shl   r4d, 2
%elif %1 || HIGH_BIT_DEPTH
    add   r4d, r4d
%else
    movsxdifnidn r4, r4d
%endif
    add    r0, r4
    add    r2, r4
    neg    r4
.loopy:
    lea    r6, [r4+4*mmsize]
%if %1
    test  r6d, r6d
    jg .skip
%endif
.loopx:
    PREFETCHNT_ITER r2+r6, 4*mmsize
    movu   m0, [r2+r6-4*mmsize]
    movu   m1, [r2+r6-3*mmsize]
    movu   m2, [r2+r6-2*mmsize]
    movu   m3, [r2+r6-1*mmsize]
%if %1
    pshufb m0, m4
    pshufb m1, m4
    pshufb m2, m4
    pshufb m3, m4
%endif
    movnta [r0+r6-4*mmsize], m0
    movnta [r0+r6-3*mmsize], m1
    movnta [r0+r6-2*mmsize], m2
    movnta [r0+r6-1*mmsize], m3
    add    r6, 4*mmsize
    jle .loopx
.skip:
    PREFETCHNT_ITER r2+r6, 4*mmsize
    sub    r6, 4*mmsize
    jz .end
.loop_end:
    movu   m0, [r2+r6]
%if %1
    pshufb m0, m4
%endif
    movnta [r0+r6], m0
    add    r6, mmsize
    jl .loop_end
.end:
    add    r0, r1
    add    r2, r3
    dec   r5d
    jg .loopy
    sfence
    RET
%endmacro

INIT_XMM sse
PLANE_COPY_CORE 0
INIT_XMM ssse3
PLANE_COPY_CORE 1
INIT_YMM avx
PLANE_COPY_CORE 0
INIT_YMM avx2
PLANE_COPY_CORE 1

%macro INTERLEAVE 4-5 ; dst, srcu, srcv, is_aligned, nt_hint
%if HIGH_BIT_DEPTH
%assign x 0
%rep 16/mmsize
    mov%4     m0, [%2+(x/2)*mmsize]
    mov%4     m1, [%3+(x/2)*mmsize]
    punpckhwd m2, m0, m1
    punpcklwd m0, m1
    mov%5a    [%1+(x+0)*mmsize], m0
    mov%5a    [%1+(x+1)*mmsize], m2
    %assign x (x+2)
%endrep
%else
    movq   m0, [%2]
%if mmsize==16
%ifidn %4, a
    punpcklbw m0, [%3]
%else
    movq   m1, [%3]
    punpcklbw m0, m1
%endif
    mov%5a [%1], m0
%else
    movq   m1, [%3]
    punpckhbw m2, m0, m1
    punpcklbw m0, m1
    mov%5a [%1+0], m0
    mov%5a [%1+8], m2
%endif
%endif ; HIGH_BIT_DEPTH
%endmacro

%macro DEINTERLEAVE 6 ; dstu, dstv, src, dstv==dstu+8, shuffle constant, is aligned
%if HIGH_BIT_DEPTH
%assign n 0
%rep 16/mmsize
    mova     m0, [%3+(n+0)*mmsize]
    mova     m1, [%3+(n+1)*mmsize]
    psrld    m2, m0, 16
    psrld    m3, m1, 16
    pand     m0, %5
    pand     m1, %5
    packssdw m0, m1
    packssdw m2, m3
    mov%6    [%1+(n/2)*mmsize], m0
    mov%6    [%2+(n/2)*mmsize], m2
    %assign n (n+2)
%endrep
%else ; !HIGH_BIT_DEPTH
%if mmsize==16
    mova   m0, [%3]
%if cpuflag(ssse3)
    pshufb m0, %5
%else
    mova   m1, m0
    pand   m0, %5
    psrlw  m1, 8
    packuswb m0, m1
%endif
%if %4
    mova   [%1], m0
%else
    movq   [%1], m0
    movhps [%2], m0
%endif
%else
    mova   m0, [%3]
    mova   m1, [%3+8]
    mova   m2, m0
    mova   m3, m1
    pand   m0, %5
    pand   m1, %5
    psrlw  m2, 8
    psrlw  m3, 8
    packuswb m0, m1
    packuswb m2, m3
    mova   [%1], m0
    mova   [%2], m2
%endif ; mmsize == 16
%endif ; HIGH_BIT_DEPTH
%endmacro

%macro PLANE_INTERLEAVE 0
;-----------------------------------------------------------------------------
; void plane_copy_interleave_core( uint8_t *dst,  intptr_t i_dst,
;                                  uint8_t *srcu, intptr_t i_srcu,
;                                  uint8_t *srcv, intptr_t i_srcv, int w, int h )
;-----------------------------------------------------------------------------
; assumes i_dst and w are multiples of 16, and i_dst>2*w
cglobal plane_copy_interleave_core, 6,9
    mov   r6d, r6m
%if HIGH_BIT_DEPTH
    FIX_STRIDES r1, r3, r5, r6d
    movifnidn r1mp, r1
    movifnidn r3mp, r3
    mov  r6m, r6d
%endif
    lea    r0, [r0+r6*2]
    add    r2,  r6
    add    r4,  r6
%if ARCH_X86_64
    DECLARE_REG_TMP 7,8
%else
    DECLARE_REG_TMP 1,3
%endif
    mov  t1, r1
    shr  t1, SIZEOF_PIXEL
    sub  t1, r6
    mov  t0d, r7m
.loopy:
    mov    r6d, r6m
    neg    r6
.prefetch:
    prefetchnta [r2+r6]
    prefetchnta [r4+r6]
    add    r6, 64
    jl .prefetch
    mov    r6d, r6m
    neg    r6
.loopx:
    INTERLEAVE r0+r6*2+ 0*SIZEOF_PIXEL, r2+r6+0*SIZEOF_PIXEL, r4+r6+0*SIZEOF_PIXEL, u, nt
    INTERLEAVE r0+r6*2+16*SIZEOF_PIXEL, r2+r6+8*SIZEOF_PIXEL, r4+r6+8*SIZEOF_PIXEL, u, nt
    add    r6, 16*SIZEOF_PIXEL
    jl .loopx
.pad:
%assign n 0
%rep SIZEOF_PIXEL
%if mmsize==8
    movntq [r0+r6*2+(n+ 0)], m0
    movntq [r0+r6*2+(n+ 8)], m0
    movntq [r0+r6*2+(n+16)], m0
    movntq [r0+r6*2+(n+24)], m0
%else
    movntdq [r0+r6*2+(n+ 0)], m0
    movntdq [r0+r6*2+(n+16)], m0
%endif
    %assign n n+32
%endrep
    add    r6, 16*SIZEOF_PIXEL
    cmp    r6, t1
    jl .pad
    add    r0, r1mp
    add    r2, r3mp
    add    r4, r5
    dec    t0d
    jg .loopy
    sfence
    emms
    RET

;-----------------------------------------------------------------------------
; void store_interleave_chroma( uint8_t *dst, intptr_t i_dst, uint8_t *srcu, uint8_t *srcv, int height )
;-----------------------------------------------------------------------------
cglobal store_interleave_chroma, 5,5
    FIX_STRIDES r1
.loop:
    INTERLEAVE r0+ 0, r2+           0, r3+           0, a
    INTERLEAVE r0+r1, r2+FDEC_STRIDEB, r3+FDEC_STRIDEB, a
    add    r2, FDEC_STRIDEB*2
    add    r3, FDEC_STRIDEB*2
    lea    r0, [r0+r1*2]
    sub   r4d, 2
    jg .loop
    RET
%endmacro ; PLANE_INTERLEAVE

%macro DEINTERLEAVE_START 0
%if HIGH_BIT_DEPTH
    mova   m4, [pd_ffff]
%elif cpuflag(ssse3)
    mova   m4, [deinterleave_shuf]
%else
    mova   m4, [pw_00ff]
%endif ; HIGH_BIT_DEPTH
%endmacro

%macro PLANE_DEINTERLEAVE 0
;-----------------------------------------------------------------------------
; void plane_copy_deinterleave( pixel *dstu, intptr_t i_dstu,
;                               pixel *dstv, intptr_t i_dstv,
;                               pixel *src,  intptr_t i_src, int w, int h )
;-----------------------------------------------------------------------------
cglobal plane_copy_deinterleave, 6,7
    DEINTERLEAVE_START
    mov    r6d, r6m
    FIX_STRIDES r1, r3, r5, r6d
%if HIGH_BIT_DEPTH
    mov    r6m, r6d
%endif
    add    r0,  r6
    add    r2,  r6
    lea    r4, [r4+r6*2]
.loopy:
    mov    r6d, r6m
    neg    r6
.loopx:
    DEINTERLEAVE r0+r6+0*SIZEOF_PIXEL, r2+r6+0*SIZEOF_PIXEL, r4+r6*2+ 0*SIZEOF_PIXEL, 0, m4, u
    DEINTERLEAVE r0+r6+8*SIZEOF_PIXEL, r2+r6+8*SIZEOF_PIXEL, r4+r6*2+16*SIZEOF_PIXEL, 0, m4, u
    add    r6, 16*SIZEOF_PIXEL
    jl .loopx
    add    r0, r1
    add    r2, r3
    add    r4, r5
    dec dword r7m
    jg .loopy
    RET

;-----------------------------------------------------------------------------
; void load_deinterleave_chroma_fenc( pixel *dst, pixel *src, intptr_t i_src, int height )
;-----------------------------------------------------------------------------
cglobal load_deinterleave_chroma_fenc, 4,4
    DEINTERLEAVE_START
    FIX_STRIDES r2
.loop:
    DEINTERLEAVE r0+           0, r0+FENC_STRIDEB*1/2, r1+ 0, 1, m4, a
    DEINTERLEAVE r0+FENC_STRIDEB, r0+FENC_STRIDEB*3/2, r1+r2, 1, m4, a
    add    r0, FENC_STRIDEB*2
    lea    r1, [r1+r2*2]
    sub   r3d, 2
    jg .loop
    RET

;-----------------------------------------------------------------------------
; void load_deinterleave_chroma_fdec( pixel *dst, pixel *src, intptr_t i_src, int height )
;-----------------------------------------------------------------------------
cglobal load_deinterleave_chroma_fdec, 4,4
    DEINTERLEAVE_START
    FIX_STRIDES r2
.loop:
    DEINTERLEAVE r0+           0, r0+FDEC_STRIDEB*1/2, r1+ 0, 0, m4, a
    DEINTERLEAVE r0+FDEC_STRIDEB, r0+FDEC_STRIDEB*3/2, r1+r2, 0, m4, a
    add    r0, FDEC_STRIDEB*2
    lea    r1, [r1+r2*2]
    sub   r3d, 2
    jg .loop
    RET
%endmacro ; PLANE_DEINTERLEAVE

%macro PLANE_DEINTERLEAVE_RGB_CORE 9 ; pw, i_dsta, i_dstb, i_dstc, i_src, w, h, tmp1, tmp2
%if cpuflag(ssse3)
    mova        m3, [deinterleave_rgb_shuf+(%1-3)*16]
%endif
%%loopy:
    mov         %8, r6
    mov         %9, %6
%%loopx:
    movu        m0, [%8]
    movu        m1, [%8+%1*mmsize/4]
%if cpuflag(ssse3)
    pshufb      m0, m3        ; b0 b1 b2 b3 g0 g1 g2 g3 r0 r1 r2 r3
    pshufb      m1, m3        ; b4 b5 b6 b7 g4 g5 g6 g7 r4 r5 r6 r7
%elif %1 == 3
    psrldq      m2, m0, 6
    punpcklqdq  m0, m1        ; b0 g0 r0 b1 g1 r1 __ __ b4 g4 r4 b5 g5 r5
    psrldq      m1, 6
    punpcklqdq  m2, m1        ; b2 g2 r2 b3 g3 r3 __ __ b6 g6 r6 b7 g7 r7
    psrlq       m3, m0, 24
    psrlq       m4, m2, 24
    punpckhbw   m1, m0, m3    ; b4 b5 g4 g5 r4 r5
    punpcklbw   m0, m3        ; b0 b1 g0 g1 r0 r1
    punpckhbw   m3, m2, m4    ; b6 b7 g6 g7 r6 r7
    punpcklbw   m2, m4        ; b2 b3 g2 g3 r2 r3
    punpcklwd   m0, m2        ; b0 b1 b2 b3 g0 g1 g2 g3 r0 r1 r2 r3
    punpcklwd   m1, m3        ; b4 b5 b6 b7 g4 g5 g6 g7 r4 r5 r6 r7
%else
    pshufd      m3, m0, q2301
    pshufd      m4, m1, q2301
    punpckhbw   m2, m0, m3    ; b2 b3 g2 g3 r2 r3
    punpcklbw   m0, m3        ; b0 b1 g0 g1 r0 r1
    punpckhbw   m3, m1, m4    ; b6 b7 g6 g7 r6 r7
    punpcklbw   m1, m4        ; b4 b5 g4 g5 r4 r5
    punpcklwd   m0, m2        ; b0 b1 b2 b3 g0 g1 g2 g3 r0 r1 r2 r3
    punpcklwd   m1, m3        ; b4 b5 b6 b7 g4 g5 g6 g7 r4 r5 r6 r7
%endif
    punpckldq   m2, m0, m1    ; b0 b1 b2 b3 b4 b5 b6 b7 g0 g1 g2 g3 g4 g5 g6 g7
    punpckhdq   m0, m1        ; r0 r1 r2 r3 r4 r5 r6 r7
    movh   [r0+%9], m2
    movhps [r2+%9], m2
    movh   [r4+%9], m0
    add         %8, %1*mmsize/2
    add         %9, mmsize/2
    jl %%loopx
    add         r0, %2
    add         r2, %3
    add         r4, %4
    add         r6, %5
    dec        %7d
    jg %%loopy
%endmacro

%macro PLANE_DEINTERLEAVE_RGB 0
;-----------------------------------------------------------------------------
; void x264_plane_copy_deinterleave_rgb( pixel *dsta, intptr_t i_dsta,
;                                        pixel *dstb, intptr_t i_dstb,
;                                        pixel *dstc, intptr_t i_dstc,
;                                        pixel *src,  intptr_t i_src, int pw, int w, int h )
;-----------------------------------------------------------------------------
%if ARCH_X86_64
cglobal plane_copy_deinterleave_rgb, 8,12
    %define %%args r1, r3, r5, r7, r8, r9, r10, r11
    mov        r8d, r9m
    mov        r9d, r10m
    add         r0, r8
    add         r2, r8
    add         r4, r8
    neg         r8
%else
cglobal plane_copy_deinterleave_rgb, 1,7
    %define %%args r1m, r3m, r5m, r7m, r9m, r1, r3, r5
    mov         r1, r9m
    mov         r2, r2m
    mov         r4, r4m
    mov         r6, r6m
    add         r0, r1
    add         r2, r1
    add         r4, r1
    neg         r1
    mov        r9m, r1
    mov         r1, r10m
%endif
    cmp  dword r8m, 4
    je .pw4
    PLANE_DEINTERLEAVE_RGB_CORE 3, %%args ; BGR
    jmp .ret
.pw4:
    PLANE_DEINTERLEAVE_RGB_CORE 4, %%args ; BGRA
.ret:
    REP_RET
%endmacro

%if HIGH_BIT_DEPTH == 0
INIT_XMM sse2
PLANE_DEINTERLEAVE_RGB
INIT_XMM ssse3
PLANE_DEINTERLEAVE_RGB
%endif ; !HIGH_BIT_DEPTH

%macro PLANE_DEINTERLEAVE_V210 0
;-----------------------------------------------------------------------------
; void x264_plane_copy_deinterleave_v210( uint16_t *dsty, intptr_t i_dsty,
;                                         uint16_t *dstc, intptr_t i_dstc,
;                                         uint32_t *src, intptr_t i_src, int w, int h )
;-----------------------------------------------------------------------------
%if ARCH_X86_64
cglobal plane_copy_deinterleave_v210, 8,10,7
%define src   r8
%define org_w r9
%define h     r7d
%else
cglobal plane_copy_deinterleave_v210, 7,7,7
%define src   r4m
%define org_w r6m
%define h     dword r7m
%endif
    FIX_STRIDES r1, r3, r6d
    shl    r5, 2
    add    r0, r6
    add    r2, r6
    neg    r6
    mov   src, r4
    mov org_w, r6
    mova   m2, [v210_mask]
    mova   m3, [v210_luma_shuf]
    mova   m4, [v210_chroma_shuf]
    mova   m5, [v210_mult] ; also functions as vpermd index for avx2
    pshufd m6, m5, q1102

ALIGN 16
.loop:
    movu   m1, [r4]
    pandn  m0, m2, m1
    pand   m1, m2
    pshufb m0, m3
    pshufb m1, m4
    pmulhrsw m0, m5 ; y0 y1 y2 y3 y4 y5 __ __
    pmulhrsw m1, m6 ; u0 v0 u1 v1 u2 v2 __ __
%if mmsize == 32
    vpermd m0, m5, m0
    vpermd m1, m5, m1
%endif
    movu [r0+r6], m0
    movu [r2+r6], m1
    add    r4, mmsize
    add    r6, 3*mmsize/4
    jl .loop
    add    r0, r1
    add    r2, r3
    add   src, r5
    mov    r4, src
    mov    r6, org_w
    dec     h
    jg .loop
    RET
%endmacro ; PLANE_DEINTERLEAVE_V210

%if HIGH_BIT_DEPTH
INIT_MMX mmx2
PLANE_INTERLEAVE
INIT_MMX mmx
PLANE_DEINTERLEAVE
INIT_XMM sse2
PLANE_INTERLEAVE
PLANE_DEINTERLEAVE
INIT_XMM ssse3
PLANE_DEINTERLEAVE_V210
INIT_XMM avx
PLANE_INTERLEAVE
PLANE_DEINTERLEAVE
PLANE_DEINTERLEAVE_V210
INIT_YMM avx2
PLANE_DEINTERLEAVE_V210
%else
INIT_MMX mmx2
PLANE_INTERLEAVE
INIT_MMX mmx
PLANE_DEINTERLEAVE
INIT_XMM sse2
PLANE_INTERLEAVE
PLANE_DEINTERLEAVE
INIT_XMM ssse3
PLANE_DEINTERLEAVE
%endif

; These functions are not general-use; not only do the SSE ones require aligned input,
; but they also will fail if given a non-mod16 size.
; memzero SSE will fail for non-mod128.

;-----------------------------------------------------------------------------
; void *memcpy_aligned( void *dst, const void *src, size_t n );
;-----------------------------------------------------------------------------
%macro MEMCPY 0
cglobal memcpy_aligned, 3,3
%if mmsize == 16
    test r2d, 16
    jz .copy2
    mova  m0, [r1+r2-16]
    mova [r0+r2-16], m0
    sub  r2d, 16
.copy2:
%endif
    test r2d, 2*mmsize
    jz .copy4start
    mova  m0, [r1+r2-1*mmsize]
    mova  m1, [r1+r2-2*mmsize]
    mova [r0+r2-1*mmsize], m0
    mova [r0+r2-2*mmsize], m1
    sub  r2d, 2*mmsize
.copy4start:
    test r2d, r2d
    jz .ret
.copy4:
    mova  m0, [r1+r2-1*mmsize]
    mova  m1, [r1+r2-2*mmsize]
    mova  m2, [r1+r2-3*mmsize]
    mova  m3, [r1+r2-4*mmsize]
    mova [r0+r2-1*mmsize], m0
    mova [r0+r2-2*mmsize], m1
    mova [r0+r2-3*mmsize], m2
    mova [r0+r2-4*mmsize], m3
    sub  r2d, 4*mmsize
    jg .copy4
.ret:
    REP_RET
%endmacro

INIT_MMX mmx
MEMCPY
INIT_XMM sse
MEMCPY

;-----------------------------------------------------------------------------
; void *memzero_aligned( void *dst, size_t n );
;-----------------------------------------------------------------------------
%macro MEMZERO 1
cglobal memzero_aligned, 2,2
    add  r0, r1
    neg  r1
%if mmsize == 8
    pxor m0, m0
%else
    xorps m0, m0
%endif
.loop:
%assign i 0
%rep %1
    mova [r0 + r1 + i], m0
%assign i i+mmsize
%endrep
    add r1, mmsize*%1
    jl .loop
    RET
%endmacro

INIT_MMX mmx
MEMZERO 8
INIT_XMM sse
MEMZERO 8
INIT_YMM avx
MEMZERO 4

%if HIGH_BIT_DEPTH == 0
;-----------------------------------------------------------------------------
; void integral_init4h( uint16_t *sum, uint8_t *pix, intptr_t stride )
;-----------------------------------------------------------------------------
%macro INTEGRAL_INIT4H 0
cglobal integral_init4h, 3,4
    lea     r3, [r0+r2*2]
    add     r1, r2
    neg     r2
    pxor    m4, m4
.loop:
    mova   xm0, [r1+r2]
    mova   xm1, [r1+r2+16]
%if mmsize==32
    vinserti128 m0, m0, [r1+r2+ 8], 1
    vinserti128 m1, m1, [r1+r2+24], 1
%else
    palignr m1, m0, 8
%endif
    mpsadbw m0, m4, 0
    mpsadbw m1, m4, 0
    paddw   m0, [r0+r2*2]
    paddw   m1, [r0+r2*2+mmsize]
    mova  [r3+r2*2   ], m0
    mova  [r3+r2*2+mmsize], m1
    add     r2, mmsize
    jl .loop
    RET
%endmacro

INIT_XMM sse4
INTEGRAL_INIT4H
INIT_YMM avx2
INTEGRAL_INIT4H

%macro INTEGRAL_INIT8H 0
cglobal integral_init8h, 3,4
    lea     r3, [r0+r2*2]
    add     r1, r2
    neg     r2
    pxor    m4, m4
.loop:
    mova   xm0, [r1+r2]
    mova   xm1, [r1+r2+16]
%if mmsize==32
    vinserti128 m0, m0, [r1+r2+ 8], 1
    vinserti128 m1, m1, [r1+r2+24], 1
    mpsadbw m2, m0, m4, 100100b
    mpsadbw m3, m1, m4, 100100b
%else
    palignr m1, m0, 8
    mpsadbw m2, m0, m4, 100b
    mpsadbw m3, m1, m4, 100b
%endif
    mpsadbw m0, m4, 0
    mpsadbw m1, m4, 0
    paddw   m0, [r0+r2*2]
    paddw   m1, [r0+r2*2+mmsize]
    paddw   m0, m2
    paddw   m1, m3
    mova  [r3+r2*2   ], m0
    mova  [r3+r2*2+mmsize], m1
    add     r2, mmsize
    jl .loop
    RET
%endmacro

INIT_XMM sse4
INTEGRAL_INIT8H
INIT_XMM avx
INTEGRAL_INIT8H
INIT_YMM avx2
INTEGRAL_INIT8H
%endif ; !HIGH_BIT_DEPTH

%macro INTEGRAL_INIT_8V 0
;-----------------------------------------------------------------------------
; void integral_init8v( uint16_t *sum8, intptr_t stride )
;-----------------------------------------------------------------------------
cglobal integral_init8v, 3,3
    add   r1, r1
    add   r0, r1
    lea   r2, [r0+r1*8]
    neg   r1
.loop:
    mova  m0, [r2+r1]
    mova  m1, [r2+r1+mmsize]
    psubw m0, [r0+r1]
    psubw m1, [r0+r1+mmsize]
    mova  [r0+r1], m0
    mova  [r0+r1+mmsize], m1
    add   r1, 2*mmsize
    jl .loop
    RET
%endmacro

INIT_MMX mmx
INTEGRAL_INIT_8V
INIT_XMM sse2
INTEGRAL_INIT_8V
INIT_YMM avx2
INTEGRAL_INIT_8V

;-----------------------------------------------------------------------------
; void integral_init4v( uint16_t *sum8, uint16_t *sum4, intptr_t stride )
;-----------------------------------------------------------------------------
INIT_MMX mmx
cglobal integral_init4v, 3,5
    shl   r2, 1
    lea   r3, [r0+r2*4]
    lea   r4, [r0+r2*8]
    mova  m0, [r0+r2]
    mova  m4, [r4+r2]
.loop:
    mova  m1, m4
    psubw m1, m0
    mova  m4, [r4+r2-8]
    mova  m0, [r0+r2-8]
    paddw m1, m4
    mova  m3, [r3+r2-8]
    psubw m1, m0
    psubw m3, m0
    mova  [r0+r2-8], m1
    mova  [r1+r2-8], m3
    sub   r2, 8
    jge .loop
    RET

INIT_XMM sse2
cglobal integral_init4v, 3,5
    shl     r2, 1
    add     r0, r2
    add     r1, r2
    lea     r3, [r0+r2*4]
    lea     r4, [r0+r2*8]
    neg     r2
.loop:
    mova    m0, [r0+r2]
    mova    m1, [r4+r2]
    mova    m2, m0
    mova    m4, m1
    shufpd  m0, [r0+r2+16], 1
    shufpd  m1, [r4+r2+16], 1
    paddw   m0, m2
    paddw   m1, m4
    mova    m3, [r3+r2]
    psubw   m1, m0
    psubw   m3, m2
    mova  [r0+r2], m1
    mova  [r1+r2], m3
    add     r2, 16
    jl .loop
    RET

INIT_XMM ssse3
cglobal integral_init4v, 3,5
    shl     r2, 1
    add     r0, r2
    add     r1, r2
    lea     r3, [r0+r2*4]
    lea     r4, [r0+r2*8]
    neg     r2
.loop:
    mova    m2, [r0+r2]
    mova    m0, [r0+r2+16]
    mova    m4, [r4+r2]
    mova    m1, [r4+r2+16]
    palignr m0, m2, 8
    palignr m1, m4, 8
    paddw   m0, m2
    paddw   m1, m4
    mova    m3, [r3+r2]
    psubw   m1, m0
    psubw   m3, m2
    mova  [r0+r2], m1
    mova  [r1+r2], m3
    add     r2, 16
    jl .loop
    RET

INIT_YMM avx2
cglobal integral_init4v, 3,5
    add     r2, r2
    add     r0, r2
    add     r1, r2
    lea     r3, [r0+r2*4]
    lea     r4, [r0+r2*8]
    neg     r2
.loop:
    mova    m2, [r0+r2]
    movu    m1, [r4+r2+8]
    paddw   m0, m2, [r0+r2+8]
    paddw   m1, [r4+r2]
    mova    m3, [r3+r2]
    psubw   m1, m0
    psubw   m3, m2
    mova  [r0+r2], m1
    mova  [r1+r2], m3
    add     r2, 32
    jl .loop
    RET

%macro FILT8x4 7
    mova      %3, [r0+%7]
    mova      %4, [r0+r5+%7]
    pavgb     %3, %4
    pavgb     %4, [r0+r5*2+%7]
    PALIGNR   %1, %3, 1, m6
    PALIGNR   %2, %4, 1, m6
%if cpuflag(xop)
    pavgb     %1, %3
    pavgb     %2, %4
%else
    pavgb     %1, %3
    pavgb     %2, %4
    psrlw     %5, %1, 8
    psrlw     %6, %2, 8
    pand      %1, m7
    pand      %2, m7
%endif
%endmacro

%macro FILT32x4U 4
    mova      m1, [r0+r5]
    pavgb     m0, m1, [r0]
    movu      m3, [r0+r5+1]
    pavgb     m2, m3, [r0+1]
    pavgb     m1, [r0+r5*2]
    pavgb     m3, [r0+r5*2+1]
    pavgb     m0, m2
    pavgb     m1, m3

    mova      m3, [r0+r5+mmsize]
    pavgb     m2, m3, [r0+mmsize]
    movu      m5, [r0+r5+1+mmsize]
    pavgb     m4, m5, [r0+1+mmsize]
    pavgb     m3, [r0+r5*2+mmsize]
    pavgb     m5, [r0+r5*2+1+mmsize]
    pavgb     m2, m4
    pavgb     m3, m5

    pshufb    m0, m7
    pshufb    m1, m7
    pshufb    m2, m7
    pshufb    m3, m7
    punpckhqdq m4, m0, m2
    punpcklqdq m0, m0, m2
    punpckhqdq m5, m1, m3
    punpcklqdq m2, m1, m3
    vpermq    m0, m0, q3120
    vpermq    m1, m4, q3120
    vpermq    m2, m2, q3120
    vpermq    m3, m5, q3120
    mova    [%1], m0
    mova    [%2], m1
    mova    [%3], m2
    mova    [%4], m3
%endmacro

%macro FILT16x2 4
    mova      m3, [r0+%4+mmsize]
    mova      m2, [r0+%4]
    pavgb     m3, [r0+%4+r5+mmsize]
    pavgb     m2, [r0+%4+r5]
    PALIGNR   %1, m3, 1, m6
    pavgb     %1, m3
    PALIGNR   m3, m2, 1, m6
    pavgb     m3, m2
%if cpuflag(xop)
    vpperm    m5, m3, %1, m7
    vpperm    m3, m3, %1, m6
%else
    psrlw     m5, m3, 8
    psrlw     m4, %1, 8
    pand      m3, m7
    pand      %1, m7
    packuswb  m3, %1
    packuswb  m5, m4
%endif
    mova    [%2], m3
    mova    [%3], m5
    mova      %1, m2
%endmacro

%macro FILT8x2U 3
    mova      m3, [r0+%3+8]
    mova      m2, [r0+%3]
    pavgb     m3, [r0+%3+r5+8]
    pavgb     m2, [r0+%3+r5]
    mova      m1, [r0+%3+9]
    mova      m0, [r0+%3+1]
    pavgb     m1, [r0+%3+r5+9]
    pavgb     m0, [r0+%3+r5+1]
    pavgb     m1, m3
    pavgb     m0, m2
    psrlw     m3, m1, 8
    psrlw     m2, m0, 8
    pand      m1, m7
    pand      m0, m7
    packuswb  m0, m1
    packuswb  m2, m3
    mova    [%1], m0
    mova    [%2], m2
%endmacro

%macro FILT8xU 3
    mova      m3, [r0+%3+8]
    mova      m2, [r0+%3]
    pavgw     m3, [r0+%3+r5+8]
    pavgw     m2, [r0+%3+r5]
    movu      m1, [r0+%3+10]
    movu      m0, [r0+%3+2]
    pavgw     m1, [r0+%3+r5+10]
    pavgw     m0, [r0+%3+r5+2]
    pavgw     m1, m3
    pavgw     m0, m2
    psrld     m3, m1, 16
    psrld     m2, m0, 16
    pand      m1, m7
    pand      m0, m7
    packssdw  m0, m1
    packssdw  m2, m3
    movu    [%1], m0
    mova    [%2], m2
%endmacro

%macro FILT8xA 4
    mova      m3, [r0+%4+mmsize]
    mova      m2, [r0+%4]
    pavgw     m3, [r0+%4+r5+mmsize]
    pavgw     m2, [r0+%4+r5]
    PALIGNR   %1, m3, 2, m6
    pavgw     %1, m3
    PALIGNR   m3, m2, 2, m6
    pavgw     m3, m2
%if cpuflag(xop)
    vpperm    m5, m3, %1, m7
    vpperm    m3, m3, %1, m6
%else
    psrld     m5, m3, 16
    psrld     m4, %1, 16
    pand      m3, m7
    pand      %1, m7
    packssdw  m3, %1
    packssdw  m5, m4
%endif
    mova    [%2], m3
    mova    [%3], m5
    mova      %1, m2
%endmacro

;-----------------------------------------------------------------------------
; void frame_init_lowres_core( uint8_t *src0, uint8_t *dst0, uint8_t *dsth, uint8_t *dstv, uint8_t *dstc,
;                              intptr_t src_stride, intptr_t dst_stride, int width, int height )
;-----------------------------------------------------------------------------
%macro FRAME_INIT_LOWRES 0
cglobal frame_init_lowres_core, 6,7,(12-4*(BIT_DEPTH/9)) ; 8 for HIGH_BIT_DEPTH, 12 otherwise
%if HIGH_BIT_DEPTH
    shl   dword r6m, 1
    FIX_STRIDES r5
    shl   dword r7m, 1
%endif
%if mmsize >= 16
    add   dword r7m, mmsize-1
    and   dword r7m, ~(mmsize-1)
%endif
    ; src += 2*(height-1)*stride + 2*width
    mov      r6d, r8m
    dec      r6d
    imul     r6d, r5d
    add      r6d, r7m
    lea       r0, [r0+r6*2]
    ; dst += (height-1)*stride + width
    mov      r6d, r8m
    dec      r6d
    imul     r6d, r6m
    add      r6d, r7m
    add       r1, r6
    add       r2, r6
    add       r3, r6
    add       r4, r6
    ; gap = stride - width
    mov      r6d, r6m
    sub      r6d, r7m
    PUSH      r6
    %define dst_gap [rsp+gprsize]
    mov      r6d, r5d
    sub      r6d, r7m
    shl      r6d, 1
    PUSH      r6
    %define src_gap [rsp]
%if HIGH_BIT_DEPTH
%if cpuflag(xop)
    mova      m6, [deinterleave_shuf32a]
    mova      m7, [deinterleave_shuf32b]
%else
    pcmpeqw   m7, m7
    psrld     m7, 16
%endif
.vloop:
    mov      r6d, r7m
%ifnidn cpuname, mmx2
    mova      m0, [r0]
    mova      m1, [r0+r5]
    pavgw     m0, m1
    pavgw     m1, [r0+r5*2]
%endif
.hloop:
    sub       r0, mmsize*2
    sub       r1, mmsize
    sub       r2, mmsize
    sub       r3, mmsize
    sub       r4, mmsize
%ifidn cpuname, mmx2
    FILT8xU r1, r2, 0
    FILT8xU r3, r4, r5
%else
    FILT8xA m0, r1, r2, 0
    FILT8xA m1, r3, r4, r5
%endif
    sub      r6d, mmsize
    jg .hloop
%else ; !HIGH_BIT_DEPTH
%if cpuflag(avx2)
    mova      m7, [deinterleave_shuf]
%elif cpuflag(xop)
    mova      m6, [deinterleave_shuf32a]
    mova      m7, [deinterleave_shuf32b]
%else
    pcmpeqb   m7, m7
    psrlw     m7, 8
%endif
.vloop:
    mov      r6d, r7m
%ifnidn cpuname, mmx2
%if mmsize <= 16
    mova      m0, [r0]
    mova      m1, [r0+r5]
    pavgb     m0, m1
    pavgb     m1, [r0+r5*2]
%endif
%endif
.hloop:
    sub       r0, mmsize*2
    sub       r1, mmsize
    sub       r2, mmsize
    sub       r3, mmsize
    sub       r4, mmsize
%if mmsize==32
    FILT32x4U r1, r2, r3, r4
%elifdef m8
    FILT8x4   m0, m1, m2, m3, m10, m11, mmsize
    mova      m8, m0
    mova      m9, m1
    FILT8x4   m2, m3, m0, m1, m4, m5, 0
%if cpuflag(xop)
    vpperm    m4, m2, m8, m7
    vpperm    m2, m2, m8, m6
    vpperm    m5, m3, m9, m7
    vpperm    m3, m3, m9, m6
%else
    packuswb  m2, m8
    packuswb  m3, m9
    packuswb  m4, m10
    packuswb  m5, m11
%endif
    mova    [r1], m2
    mova    [r2], m4
    mova    [r3], m3
    mova    [r4], m5
%elifidn cpuname, mmx2
    FILT8x2U  r1, r2, 0
    FILT8x2U  r3, r4, r5
%else
    FILT16x2  m0, r1, r2, 0
    FILT16x2  m1, r3, r4, r5
%endif
    sub      r6d, mmsize
    jg .hloop
%endif ; HIGH_BIT_DEPTH
.skip:
    mov       r6, dst_gap
    sub       r0, src_gap
    sub       r1, r6
    sub       r2, r6
    sub       r3, r6
    sub       r4, r6
    dec    dword r8m
    jg .vloop
    ADD      rsp, 2*gprsize
    emms
    RET
%endmacro ; FRAME_INIT_LOWRES

INIT_MMX mmx2
FRAME_INIT_LOWRES
%if ARCH_X86_64 == 0
INIT_MMX cache32, mmx2
FRAME_INIT_LOWRES
%endif
INIT_XMM sse2
FRAME_INIT_LOWRES
INIT_XMM ssse3
FRAME_INIT_LOWRES
INIT_XMM avx
FRAME_INIT_LOWRES
INIT_XMM xop
FRAME_INIT_LOWRES
%if HIGH_BIT_DEPTH==0
INIT_YMM avx2
FRAME_INIT_LOWRES
%endif

;-----------------------------------------------------------------------------
; void mbtree_propagate_cost( int *dst, uint16_t *propagate_in, uint16_t *intra_costs,
;                             uint16_t *inter_costs, uint16_t *inv_qscales, float *fps_factor, int len )
;-----------------------------------------------------------------------------
%macro MBTREE 0
cglobal mbtree_propagate_cost, 6,6,7
    movss     m6, [r5]
    mov      r5d, r6m
    lea       r0, [r0+r5*2]
    add      r5d, r5d
    add       r1, r5
    add       r2, r5
    add       r3, r5
    add       r4, r5
    neg       r5
    pxor      m4, m4
    shufps    m6, m6, 0
    mova      m5, [pw_3fff]
.loop:
    movq      m2, [r2+r5] ; intra
    movq      m0, [r4+r5] ; invq
    movq      m3, [r3+r5] ; inter
    movq      m1, [r1+r5] ; prop
    pand      m3, m5
    pminsw    m3, m2
    punpcklwd m2, m4
    punpcklwd m0, m4
    pmaddwd   m0, m2
    punpcklwd m1, m4
    punpcklwd m3, m4
%if cpuflag(fma4)
    cvtdq2ps  m0, m0
    cvtdq2ps  m1, m1
    fmaddps   m0, m0, m6, m1
    cvtdq2ps  m1, m2
    psubd     m2, m3
    cvtdq2ps  m2, m2
    rcpps     m3, m1
    mulps     m1, m3
    mulps     m0, m2
    addps     m2, m3, m3
    fnmaddps  m3, m1, m3, m2
    mulps     m0, m3
%else
    cvtdq2ps  m0, m0
    mulps     m0, m6    ; intra*invq*fps_factor>>8
    cvtdq2ps  m1, m1    ; prop
    addps     m0, m1    ; prop + (intra*invq*fps_factor>>8)
    cvtdq2ps  m1, m2    ; intra
    psubd     m2, m3    ; intra - inter
    cvtdq2ps  m2, m2    ; intra - inter
    rcpps     m3, m1    ; 1 / intra 1st approximation
    mulps     m1, m3    ; intra * (1/intra 1st approx)
    mulps     m1, m3    ; intra * (1/intra 1st approx)^2
    mulps     m0, m2    ; (prop + (intra*invq*fps_factor>>8)) * (intra - inter)
    addps     m3, m3    ; 2 * (1/intra 1st approx)
    subps     m3, m1    ; 2nd approximation for 1/intra
    mulps     m0, m3    ; / intra
%endif
    cvtps2dq  m0, m0
    packssdw  m0, m0
    movh [r0+r5], m0
    add       r5, 8
    jl .loop
    RET
%endmacro

INIT_XMM sse2
MBTREE
; Bulldozer only has a 128-bit float unit, so the AVX version of this function is actually slower.
INIT_XMM fma4
MBTREE

%macro INT16_UNPACK 1
    punpckhwd   xm6, xm%1, xm7
    punpcklwd  xm%1, xm7
    vinsertf128 m%1, m%1, xm6, 1
%endmacro

; FIXME: align loads to 16 bytes
%macro MBTREE_AVX 0
cglobal mbtree_propagate_cost, 6,6,8-2*cpuflag(avx2)
    vbroadcastss m5, [r5]
    mov         r5d, r6m
    lea          r0, [r0+r5*2]
    add         r5d, r5d
    add          r1, r5
    add          r2, r5
    add          r3, r5
    add          r4, r5
    neg          r5
    mova        xm4, [pw_3fff]
%if notcpuflag(avx2)
    pxor        xm7, xm7
%endif
.loop:
%if cpuflag(avx2)
    pmovzxwd     m0, [r2+r5]      ; intra
    pmovzxwd     m1, [r4+r5]      ; invq
    pmovzxwd     m2, [r1+r5]      ; prop
    pand        xm3, xm4, [r3+r5] ; inter
    pmovzxwd     m3, xm3
    pminsd       m3, m0
    pmaddwd      m1, m0
    psubd        m3, m0, m3
    cvtdq2ps     m0, m0
    cvtdq2ps     m1, m1
    cvtdq2ps     m2, m2
    cvtdq2ps     m3, m3
    fmaddps      m1, m1, m5, m2
    rcpps        m2, m0
    mulps        m0, m2
    mulps        m1, m3
    addps        m3, m2, m2
    fnmaddps     m2, m2, m0, m3
    mulps        m1, m2
%else
    movu        xm0, [r2+r5]
    movu        xm1, [r4+r5]
    movu        xm2, [r1+r5]
    pand        xm3, xm4, [r3+r5]
    pminsw      xm3, xm0
    INT16_UNPACK 0
    INT16_UNPACK 1
    INT16_UNPACK 2
    INT16_UNPACK 3
    cvtdq2ps     m0, m0
    cvtdq2ps     m1, m1
    cvtdq2ps     m2, m2
    cvtdq2ps     m3, m3
    mulps        m1, m0
    subps        m3, m0, m3
    mulps        m1, m5         ; intra*invq*fps_factor>>8
    addps        m1, m2         ; prop + (intra*invq*fps_factor>>8)
    rcpps        m2, m0         ; 1 / intra 1st approximation
    mulps        m0, m2         ; intra * (1/intra 1st approx)
    mulps        m0, m2         ; intra * (1/intra 1st approx)^2
    mulps        m1, m3         ; (prop + (intra*invq*fps_factor>>8)) * (intra - inter)
    addps        m2, m2         ; 2 * (1/intra 1st approx)
    subps        m2, m0         ; 2nd approximation for 1/intra
    mulps        m1, m2         ; / intra
%endif
    vcvtps2dq    m1, m1
    vextractf128 xm2, m1, 1
    packssdw    xm1, xm2
    mova    [r0+r5], xm1
    add          r5, 16
    jl .loop
    RET
%endmacro

INIT_YMM avx
MBTREE_AVX
INIT_YMM avx2
MBTREE_AVX

%macro MBTREE_PROPAGATE_LIST 0
;-----------------------------------------------------------------------------
; void mbtree_propagate_list_internal( int16_t (*mvs)[2], int *propagate_amount, uint16_t *lowres_costs,
;                                      int16_t *output, int bipred_weight, int mb_y, int len )
;-----------------------------------------------------------------------------
cglobal mbtree_propagate_list_internal, 4,6,8
    movh     m6, [pw_0to15] ; mb_x
    movd     m7, r5m
    pshuflw  m7, m7, 0
    punpcklwd m6, m7       ; 0 y 1 y 2 y 3 y
    movd     m7, r4m
    SPLATW   m7, m7        ; bipred_weight
    psllw    m7, 9         ; bipred_weight << 9

    mov     r5d, r6m
    xor     r4d, r4d
.loop:
    mova     m3, [r1+r4*2]
    movu     m4, [r2+r4*2]
    mova     m5, [pw_0xc000]
    pand     m4, m5
    pcmpeqw  m4, m5
    pmulhrsw m5, m3, m7    ; propagate_amount = (propagate_amount * bipred_weight + 32) >> 6
%if cpuflag(avx)
    pblendvb m5, m3, m5, m4
%else
    pand     m5, m4
    pandn    m4, m3
    por      m5, m4        ; if( lists_used == 3 )
                           ;     propagate_amount = (propagate_amount * bipred_weight + 32) >> 6
%endif

    movu     m0, [r0+r4*4] ; x,y
    movu     m1, [r0+r4*4+mmsize]

    psraw    m2, m0, 5
    psraw    m3, m1, 5
    mova     m4, [pd_4]
    paddw    m2, m6        ; {mbx, mby} = ({x,y}>>5)+{h->mb.i_mb_x,h->mb.i_mb_y}
    paddw    m6, m4        ; {mbx, mby} += {4, 0}
    paddw    m3, m6        ; {mbx, mby} = ({x,y}>>5)+{h->mb.i_mb_x,h->mb.i_mb_y}
    paddw    m6, m4        ; {mbx, mby} += {4, 0}

    mova [r3+mmsize*0], m2
    mova [r3+mmsize*1], m3

    mova     m3, [pw_31]
    pand     m0, m3        ; x &= 31
    pand     m1, m3        ; y &= 31
    packuswb m0, m1
    psrlw    m1, m0, 3
    pand     m0, m3        ; x
    SWAP      1, 3
    pandn    m1, m3        ; y premultiplied by (1<<5) for later use of pmulhrsw

    mova     m3, [pw_32]
    psubw    m3, m0        ; 32 - x
    mova     m4, [pw_1024]
    psubw    m4, m1        ; (32 - y) << 5

    pmullw   m2, m3, m4    ; idx0weight = (32-y)*(32-x) << 5
    pmullw   m4, m0        ; idx1weight = (32-y)*x << 5
    pmullw   m0, m1        ; idx3weight = y*x << 5
    pmullw   m1, m3        ; idx2weight = y*(32-x) << 5

    ; avoid overflow in the input to pmulhrsw
    psrlw    m3, m2, 15
    psubw    m2, m3        ; idx0weight -= (idx0weight == 32768)

    pmulhrsw m2, m5        ; idx0weight * propagate_amount + 512 >> 10
    pmulhrsw m4, m5        ; idx1weight * propagate_amount + 512 >> 10
    pmulhrsw m1, m5        ; idx2weight * propagate_amount + 512 >> 10
    pmulhrsw m0, m5        ; idx3weight * propagate_amount + 512 >> 10

    SBUTTERFLY wd, 2, 4, 3
    SBUTTERFLY wd, 1, 0, 3
    mova [r3+mmsize*2], m2
    mova [r3+mmsize*3], m4
    mova [r3+mmsize*4], m1
    mova [r3+mmsize*5], m0
    add     r4d, mmsize/2
    add      r3, mmsize*6
    cmp     r4d, r5d
    jl .loop
    REP_RET
%endmacro

INIT_XMM ssse3
MBTREE_PROPAGATE_LIST
INIT_XMM avx
MBTREE_PROPAGATE_LIST

%macro MBTREE_FIX8 0
;-----------------------------------------------------------------------------
; void mbtree_fix8_pack( uint16_t *dst, float *src, int count )
;-----------------------------------------------------------------------------
cglobal mbtree_fix8_pack, 3,4
%if mmsize == 32
    vbroadcastf128 m2, [pf_256]
    vbroadcasti128 m3, [mbtree_fix8_pack_shuf]
%else
    movaps       m2, [pf_256]
    mova         m3, [mbtree_fix8_pack_shuf]
%endif
    sub         r2d, mmsize/2
    movsxdifnidn r2, r2d
    lea          r1, [r1+4*r2]
    lea          r0, [r0+2*r2]
    neg          r2
    jg .skip_loop
.loop:
    mulps        m0, m2, [r1+4*r2]
    mulps        m1, m2, [r1+4*r2+mmsize]
    cvttps2dq    m0, m0
    cvttps2dq    m1, m1
    packssdw     m0, m1
    pshufb       m0, m3
%if mmsize == 32
    vpermq       m0, m0, q3120
%endif
    mova  [r0+2*r2], m0
    add          r2, mmsize/2
    jle .loop
.skip_loop:
    sub          r2, mmsize/2
    jz .end
    ; Do the remaining values in scalar in order to avoid overreading src.
.scalar:
    mulss       xm0, xm2, [r1+4*r2+2*mmsize]
    cvttss2si   r3d, xm0
    rol         r3w, 8
    mov [r0+2*r2+mmsize], r3w
    inc          r2
    jl .scalar
.end:
    RET

;-----------------------------------------------------------------------------
; void mbtree_fix8_unpack( float *dst, uint16_t *src, int count )
;-----------------------------------------------------------------------------
cglobal mbtree_fix8_unpack, 3,4
%if mmsize == 32
    vbroadcastf128 m2, [pf_inv256]
%else
    movaps       m2, [pf_inv256]
    mova         m4, [mbtree_fix8_unpack_shuf+16]
%endif
    mova         m3, [mbtree_fix8_unpack_shuf]
    sub         r2d, mmsize/2
    movsxdifnidn r2, r2d
    lea          r1, [r1+2*r2]
    lea          r0, [r0+4*r2]
    neg          r2
    jg .skip_loop
.loop:
%if mmsize == 32
    vbroadcasti128 m0, [r1+2*r2]
    vbroadcasti128 m1, [r1+2*r2+16]
    pshufb       m0, m3
    pshufb       m1, m3
%else
    mova         m1, [r1+2*r2]
    pshufb       m0, m1, m3
    pshufb       m1, m4
%endif
    psrad        m0, 16 ; sign-extend
    psrad        m1, 16
    cvtdq2ps     m0, m0
    cvtdq2ps     m1, m1
    mulps        m0, m2
    mulps        m1, m2
    movaps [r0+4*r2], m0
    movaps [r0+4*r2+mmsize], m1
    add          r2, mmsize/2
    jle .loop
.skip_loop:
    sub          r2, mmsize/2
    jz .end
.scalar:
    movzx       r3d, word [r1+2*r2+mmsize]
    rol         r3w, 8
    movsx       r3d, r3w
    ; Use 3-arg cvtsi2ss as a workaround for the fact that the instruction has a stupid dependency on
    ; dst which causes terrible performance when used in a loop otherwise. Blame Intel for poor design.
    cvtsi2ss    xm0, xm2, r3d
    mulss       xm0, xm2
    movss [r0+4*r2+2*mmsize], xm0
    inc          r2
    jl .scalar
.end:
    RET
%endmacro

INIT_XMM ssse3
MBTREE_FIX8
INIT_YMM avx2
MBTREE_FIX8
