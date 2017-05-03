/*****************************************************************************
 * dct.c: ppc transform and zigzag
 *****************************************************************************
 * Copyright (C) 2003-2016 x264 project
 *
 * Authors: Guillaume Poirier <gpoirier@mplayerhq.hu>
 *          Eric Petit <eric.petit@lapsus.org>
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

#include "common/common.h"
#include "ppccommon.h"

#if !HIGH_BIT_DEPTH
#define VEC_DCT(a0,a1,a2,a3,b0,b1,b2,b3) \
    b1 = vec_add( a0, a3 );              \
    b3 = vec_add( a1, a2 );              \
    b0 = vec_add( b1, b3 );              \
    b2 = vec_sub( b1, b3 );              \
    a0 = vec_sub( a0, a3 );              \
    a1 = vec_sub( a1, a2 );              \
    b1 = vec_add( a0, a0 );              \
    b1 = vec_add( b1, a1 );              \
    b3 = vec_sub( a0, a1 );              \
    b3 = vec_sub( b3, a1 )

void x264_sub4x4_dct_altivec( int16_t dct[16], uint8_t *pix1, uint8_t *pix2 )
{
    PREP_DIFF_8BYTEALIGNED;
    vec_s16_t dct0v, dct1v, dct2v, dct3v;
    vec_s16_t tmp0v, tmp1v, tmp2v, tmp3v;

    vec_u8_t permHighv;

    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 4, dct0v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 4, dct1v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 4, dct2v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 4, dct3v );
    VEC_DCT( dct0v, dct1v, dct2v, dct3v, tmp0v, tmp1v, tmp2v, tmp3v );
    VEC_TRANSPOSE_4( tmp0v, tmp1v, tmp2v, tmp3v,
                     dct0v, dct1v, dct2v, dct3v );
    permHighv = (vec_u8_t) CV(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17);
    VEC_DCT( dct0v, dct1v, dct2v, dct3v, tmp0v, tmp1v, tmp2v, tmp3v );

    vec_st(vec_perm(tmp0v, tmp1v, permHighv), 0,  dct);
    vec_st(vec_perm(tmp2v, tmp3v, permHighv), 16, dct);
}

void x264_sub8x8_dct_altivec( int16_t dct[4][16], uint8_t *pix1, uint8_t *pix2 )
{
    PREP_DIFF_8BYTEALIGNED;
    vec_s16_t dct0v, dct1v, dct2v, dct3v, dct4v, dct5v, dct6v, dct7v;
    vec_s16_t tmp0v, tmp1v, tmp2v, tmp3v, tmp4v, tmp5v, tmp6v, tmp7v;

    vec_u8_t permHighv, permLowv;

    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct0v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct1v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct2v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct3v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct4v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct5v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct6v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct7v );
    VEC_DCT( dct0v, dct1v, dct2v, dct3v, tmp0v, tmp1v, tmp2v, tmp3v );
    VEC_DCT( dct4v, dct5v, dct6v, dct7v, tmp4v, tmp5v, tmp6v, tmp7v );
    VEC_TRANSPOSE_8( tmp0v, tmp1v, tmp2v, tmp3v,
                     tmp4v, tmp5v, tmp6v, tmp7v,
                     dct0v, dct1v, dct2v, dct3v,
                     dct4v, dct5v, dct6v, dct7v );

    permHighv = (vec_u8_t) CV(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17);
    permLowv  = (vec_u8_t) CV(0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F);

    VEC_DCT( dct0v, dct1v, dct2v, dct3v, tmp0v, tmp1v, tmp2v, tmp3v );
    VEC_DCT( dct4v, dct5v, dct6v, dct7v, tmp4v, tmp5v, tmp6v, tmp7v );

    vec_st(vec_perm(tmp0v, tmp1v, permHighv), 0,   *dct);
    vec_st(vec_perm(tmp2v, tmp3v, permHighv), 16,  *dct);
    vec_st(vec_perm(tmp4v, tmp5v, permHighv), 32,  *dct);
    vec_st(vec_perm(tmp6v, tmp7v, permHighv), 48,  *dct);
    vec_st(vec_perm(tmp0v, tmp1v, permLowv),  64,  *dct);
    vec_st(vec_perm(tmp2v, tmp3v, permLowv),  80,  *dct);
    vec_st(vec_perm(tmp4v, tmp5v, permLowv),  96,  *dct);
    vec_st(vec_perm(tmp6v, tmp7v, permLowv),  112, *dct);
}

void x264_sub16x16_dct_altivec( int16_t dct[16][16], uint8_t *pix1, uint8_t *pix2 )
{
    x264_sub8x8_dct_altivec( &dct[ 0], &pix1[0], &pix2[0] );
    x264_sub8x8_dct_altivec( &dct[ 4], &pix1[8], &pix2[8] );
    x264_sub8x8_dct_altivec( &dct[ 8], &pix1[8*FENC_STRIDE+0], &pix2[8*FDEC_STRIDE+0] );
    x264_sub8x8_dct_altivec( &dct[12], &pix1[8*FENC_STRIDE+8], &pix2[8*FDEC_STRIDE+8] );
}

/***************************************************************************
 * 8x8 transform:
 ***************************************************************************/

/* DCT8_1D unrolled by 8 in Altivec */
#define DCT8_1D_ALTIVEC( dct0v, dct1v, dct2v, dct3v, dct4v, dct5v, dct6v, dct7v ) \
{ \
    /* int s07 = SRC(0) + SRC(7);         */ \
    vec_s16_t s07v = vec_add( dct0v, dct7v); \
    /* int s16 = SRC(1) + SRC(6);         */ \
    vec_s16_t s16v = vec_add( dct1v, dct6v); \
    /* int s25 = SRC(2) + SRC(5);         */ \
    vec_s16_t s25v = vec_add( dct2v, dct5v); \
    /* int s34 = SRC(3) + SRC(4);         */ \
    vec_s16_t s34v = vec_add( dct3v, dct4v); \
\
    /* int a0 = s07 + s34;                */ \
    vec_s16_t a0v = vec_add(s07v, s34v);     \
    /* int a1 = s16 + s25;                */ \
    vec_s16_t a1v = vec_add(s16v, s25v);     \
    /* int a2 = s07 - s34;                */ \
    vec_s16_t a2v = vec_sub(s07v, s34v);     \
    /* int a3 = s16 - s25;                */ \
    vec_s16_t a3v = vec_sub(s16v, s25v);     \
\
    /* int d07 = SRC(0) - SRC(7);         */ \
    vec_s16_t d07v = vec_sub( dct0v, dct7v); \
    /* int d16 = SRC(1) - SRC(6);         */ \
    vec_s16_t d16v = vec_sub( dct1v, dct6v); \
    /* int d25 = SRC(2) - SRC(5);         */ \
    vec_s16_t d25v = vec_sub( dct2v, dct5v); \
    /* int d34 = SRC(3) - SRC(4);         */ \
    vec_s16_t d34v = vec_sub( dct3v, dct4v); \
\
    /* int a4 = d16 + d25 + (d07 + (d07>>1)); */ \
    vec_s16_t a4v = vec_add( vec_add(d16v, d25v), vec_add(d07v, vec_sra(d07v, onev)) );\
    /* int a5 = d07 - d34 - (d25 + (d25>>1)); */ \
    vec_s16_t a5v = vec_sub( vec_sub(d07v, d34v), vec_add(d25v, vec_sra(d25v, onev)) );\
    /* int a6 = d07 + d34 - (d16 + (d16>>1)); */ \
    vec_s16_t a6v = vec_sub( vec_add(d07v, d34v), vec_add(d16v, vec_sra(d16v, onev)) );\
    /* int a7 = d16 - d25 + (d34 + (d34>>1)); */ \
    vec_s16_t a7v = vec_add( vec_sub(d16v, d25v), vec_add(d34v, vec_sra(d34v, onev)) );\
\
    /* DST(0) =  a0 + a1;                    */ \
    dct0v = vec_add( a0v, a1v );                \
    /* DST(1) =  a4 + (a7>>2);               */ \
    dct1v = vec_add( a4v, vec_sra(a7v, twov) ); \
    /* DST(2) =  a2 + (a3>>1);               */ \
    dct2v = vec_add( a2v, vec_sra(a3v, onev) ); \
    /* DST(3) =  a5 + (a6>>2);               */ \
    dct3v = vec_add( a5v, vec_sra(a6v, twov) ); \
    /* DST(4) =  a0 - a1;                    */ \
    dct4v = vec_sub( a0v, a1v );                \
    /* DST(5) =  a6 - (a5>>2);               */ \
    dct5v = vec_sub( a6v, vec_sra(a5v, twov) ); \
    /* DST(6) = (a2>>1) - a3 ;               */ \
    dct6v = vec_sub( vec_sra(a2v, onev), a3v ); \
    /* DST(7) = (a4>>2) - a7 ;               */ \
    dct7v = vec_sub( vec_sra(a4v, twov), a7v ); \
}


void x264_sub8x8_dct8_altivec( int16_t dct[64], uint8_t *pix1, uint8_t *pix2 )
{
    vec_u16_t onev = vec_splat_u16(1);
    vec_u16_t twov = vec_add( onev, onev );

    PREP_DIFF_8BYTEALIGNED;

    vec_s16_t dct0v, dct1v, dct2v, dct3v,
              dct4v, dct5v, dct6v, dct7v;

    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct0v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct1v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct2v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct3v );

    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct4v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct5v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct6v );
    VEC_DIFF_H_8BYTE_ALIGNED( pix1, FENC_STRIDE, pix2, FDEC_STRIDE, 8, dct7v );

    DCT8_1D_ALTIVEC( dct0v, dct1v, dct2v, dct3v,
                     dct4v, dct5v, dct6v, dct7v );

    vec_s16_t dct_tr0v, dct_tr1v, dct_tr2v, dct_tr3v,
        dct_tr4v, dct_tr5v, dct_tr6v, dct_tr7v;

    VEC_TRANSPOSE_8(dct0v, dct1v, dct2v, dct3v,
                    dct4v, dct5v, dct6v, dct7v,
                    dct_tr0v, dct_tr1v, dct_tr2v, dct_tr3v,
                    dct_tr4v, dct_tr5v, dct_tr6v, dct_tr7v );

    DCT8_1D_ALTIVEC( dct_tr0v, dct_tr1v, dct_tr2v, dct_tr3v,
                     dct_tr4v, dct_tr5v, dct_tr6v, dct_tr7v );

    vec_st( dct_tr0v,  0,  dct );
    vec_st( dct_tr1v, 16,  dct );
    vec_st( dct_tr2v, 32,  dct );
    vec_st( dct_tr3v, 48,  dct );

    vec_st( dct_tr4v, 64,  dct );
    vec_st( dct_tr5v, 80,  dct );
    vec_st( dct_tr6v, 96,  dct );
    vec_st( dct_tr7v, 112, dct );
}

void x264_sub16x16_dct8_altivec( int16_t dct[4][64], uint8_t *pix1, uint8_t *pix2 )
{
    x264_sub8x8_dct8_altivec( dct[0], &pix1[0],               &pix2[0] );
    x264_sub8x8_dct8_altivec( dct[1], &pix1[8],               &pix2[8] );
    x264_sub8x8_dct8_altivec( dct[2], &pix1[8*FENC_STRIDE+0], &pix2[8*FDEC_STRIDE+0] );
    x264_sub8x8_dct8_altivec( dct[3], &pix1[8*FENC_STRIDE+8], &pix2[8*FDEC_STRIDE+8] );
}


/****************************************************************************
 * IDCT transform:
 ****************************************************************************/

#define IDCT_1D_ALTIVEC(s0, s1, s2, s3,  d0, d1, d2, d3) \
{                                                        \
    /*        a0  = SRC(0) + SRC(2); */                  \
    vec_s16_t a0v = vec_add(s0, s2);                     \
    /*        a1  = SRC(0) - SRC(2); */                  \
    vec_s16_t a1v = vec_sub(s0, s2);                     \
    /*        a2  =           (SRC(1)>>1) - SRC(3); */   \
    vec_s16_t a2v = vec_sub(vec_sra(s1, onev), s3);      \
    /*        a3  =           (SRC(3)>>1) + SRC(1); */   \
    vec_s16_t a3v = vec_add(vec_sra(s3, onev), s1);      \
    /* DST(0,    a0 + a3); */                            \
    d0 = vec_add(a0v, a3v);                              \
    /* DST(1,    a1 + a2); */                            \
    d1 = vec_add(a1v, a2v);                              \
    /* DST(2,    a1 - a2); */                            \
    d2 = vec_sub(a1v, a2v);                              \
    /* DST(3,    a0 - a3); */                            \
    d3 = vec_sub(a0v, a3v);                              \
}

#define VEC_LOAD_U8_ADD_S16_STORE_U8(va)             \
    vdst_orig = vec_ld(0, dst);                      \
    vdst = vec_perm(vdst_orig, zero_u8v, vdst_mask); \
    vdst_ss = (vec_s16_t)vec_mergeh(zero_u8v, vdst); \
    va = vec_add(va, vdst_ss);                       \
    va_u8 = vec_s16_to_u8(va);                       \
    va_u32 = vec_splat((vec_u32_t)va_u8, 0);         \
    vec_ste(va_u32, element, (uint32_t*)dst);

#define ALTIVEC_STORE4_SUM_CLIP(dest, idctv, perm_ldv)          \
{                                                               \
    /* unaligned load */                                        \
    vec_u8_t lv = vec_ld(0, dest);                              \
    vec_u8_t dstv = vec_perm(lv, zero_u8v, (vec_u8_t)perm_ldv); \
    vec_s16_t idct_sh6 = vec_sra(idctv, sixv);                  \
    vec_u16_t dst16 = vec_u8_to_u16_h(dstv);                    \
    vec_s16_t idstsum = vec_adds(idct_sh6, (vec_s16_t)dst16);   \
    vec_u8_t idstsum8 = vec_s16_to_u8(idstsum);                 \
    /* unaligned store */                                       \
    vec_u32_t bodyv = vec_splat((vec_u32_t)idstsum8, 0);        \
    int element = ((unsigned long)dest & 0xf) >> 2;             \
    vec_ste(bodyv, element, (uint32_t *)dest);                  \
}

void x264_add4x4_idct_altivec( uint8_t *dst, int16_t dct[16] )
{
    vec_u16_t onev = vec_splat_u16(1);

    dct[0] += 32; // rounding for the >>6 at the end

    vec_s16_t s0, s1, s2, s3;

    s0 = vec_ld( 0x00, dct );
    s1 = vec_sld( s0, s0, 8 );
    s2 = vec_ld( 0x10, dct );
    s3 = vec_sld( s2, s2, 8 );

    vec_s16_t d0, d1, d2, d3;
    IDCT_1D_ALTIVEC( s0, s1, s2, s3, d0, d1, d2, d3 );

    vec_s16_t tr0, tr1, tr2, tr3;

    VEC_TRANSPOSE_4( d0, d1, d2, d3, tr0, tr1, tr2, tr3 );

    vec_s16_t idct0, idct1, idct2, idct3;
    IDCT_1D_ALTIVEC( tr0, tr1, tr2, tr3, idct0, idct1, idct2, idct3 );

    vec_u8_t perm_ldv = vec_lvsl( 0, dst );
    vec_u16_t sixv = vec_splat_u16(6);
    LOAD_ZERO;

    ALTIVEC_STORE4_SUM_CLIP( &dst[0*FDEC_STRIDE], idct0, perm_ldv );
    ALTIVEC_STORE4_SUM_CLIP( &dst[1*FDEC_STRIDE], idct1, perm_ldv );
    ALTIVEC_STORE4_SUM_CLIP( &dst[2*FDEC_STRIDE], idct2, perm_ldv );
    ALTIVEC_STORE4_SUM_CLIP( &dst[3*FDEC_STRIDE], idct3, perm_ldv );
}

void x264_add8x8_idct_altivec( uint8_t *p_dst, int16_t dct[4][16] )
{
    x264_add4x4_idct_altivec( &p_dst[0],               dct[0] );
    x264_add4x4_idct_altivec( &p_dst[4],               dct[1] );
    x264_add4x4_idct_altivec( &p_dst[4*FDEC_STRIDE+0], dct[2] );
    x264_add4x4_idct_altivec( &p_dst[4*FDEC_STRIDE+4], dct[3] );
}

void x264_add16x16_idct_altivec( uint8_t *p_dst, int16_t dct[16][16] )
{
    x264_add8x8_idct_altivec( &p_dst[0],               &dct[0] );
    x264_add8x8_idct_altivec( &p_dst[8],               &dct[4] );
    x264_add8x8_idct_altivec( &p_dst[8*FDEC_STRIDE+0], &dct[8] );
    x264_add8x8_idct_altivec( &p_dst[8*FDEC_STRIDE+8], &dct[12] );
}

#define IDCT8_1D_ALTIVEC(s0, s1, s2, s3, s4, s5, s6, s7,  d0, d1, d2, d3, d4, d5, d6, d7)\
{\
    /*        a0  = SRC(0) + SRC(4); */ \
    vec_s16_t a0v = vec_add(s0, s4);    \
    /*        a2  = SRC(0) - SRC(4); */ \
    vec_s16_t a2v = vec_sub(s0, s4);    \
    /*        a4  =           (SRC(2)>>1) - SRC(6); */ \
    vec_s16_t a4v = vec_sub(vec_sra(s2, onev), s6);    \
    /*        a6  =           (SRC(6)>>1) + SRC(2); */ \
    vec_s16_t a6v = vec_add(vec_sra(s6, onev), s2);    \
    /*        b0  =         a0 + a6; */ \
    vec_s16_t b0v = vec_add(a0v, a6v);  \
    /*        b2  =         a2 + a4; */ \
    vec_s16_t b2v = vec_add(a2v, a4v);  \
    /*        b4  =         a2 - a4; */ \
    vec_s16_t b4v = vec_sub(a2v, a4v);  \
    /*        b6  =         a0 - a6; */ \
    vec_s16_t b6v = vec_sub(a0v, a6v);  \
    /* a1 =  SRC(5) - SRC(3) - SRC(7) - (SRC(7)>>1); */ \
    /*        a1 =             (SRC(5)-SRC(3)) -  (SRC(7)  +  (SRC(7)>>1)); */ \
    vec_s16_t a1v = vec_sub( vec_sub(s5, s3), vec_add(s7, vec_sra(s7, onev)) );\
    /* a3 =  SRC(7) + SRC(1) - SRC(3) - (SRC(3)>>1); */ \
    /*        a3 =             (SRC(7)+SRC(1)) -  (SRC(3)  +  (SRC(3)>>1)); */ \
    vec_s16_t a3v = vec_sub( vec_add(s7, s1), vec_add(s3, vec_sra(s3, onev)) );\
    /* a5 =  SRC(7) - SRC(1) + SRC(5) + (SRC(5)>>1); */ \
    /*        a5 =             (SRC(7)-SRC(1)) +   SRC(5) +   (SRC(5)>>1); */  \
    vec_s16_t a5v = vec_add( vec_sub(s7, s1), vec_add(s5, vec_sra(s5, onev)) );\
    /*        a7 =                SRC(5)+SRC(3) +  SRC(1) +   (SRC(1)>>1); */  \
    vec_s16_t a7v = vec_add( vec_add(s5, s3), vec_add(s1, vec_sra(s1, onev)) );\
    /*        b1 =                  (a7>>2)  +  a1; */  \
    vec_s16_t b1v = vec_add( vec_sra(a7v, twov), a1v);  \
    /*        b3 =          a3 +        (a5>>2); */     \
    vec_s16_t b3v = vec_add(a3v, vec_sra(a5v, twov));   \
    /*        b5 =                  (a3>>2)  -   a5; */ \
    vec_s16_t b5v = vec_sub( vec_sra(a3v, twov), a5v);  \
    /*        b7 =           a7 -        (a1>>2); */    \
    vec_s16_t b7v = vec_sub( a7v, vec_sra(a1v, twov));  \
    /* DST(0,    b0 + b7); */ \
    d0 = vec_add(b0v, b7v); \
    /* DST(1,    b2 + b5); */ \
    d1 = vec_add(b2v, b5v); \
    /* DST(2,    b4 + b3); */ \
    d2 = vec_add(b4v, b3v); \
    /* DST(3,    b6 + b1); */ \
    d3 = vec_add(b6v, b1v); \
    /* DST(4,    b6 - b1); */ \
    d4 = vec_sub(b6v, b1v); \
    /* DST(5,    b4 - b3); */ \
    d5 = vec_sub(b4v, b3v); \
    /* DST(6,    b2 - b5); */ \
    d6 = vec_sub(b2v, b5v); \
    /* DST(7,    b0 - b7); */ \
    d7 = vec_sub(b0v, b7v); \
}

#define ALTIVEC_STORE_SUM_CLIP(dest, idctv, perm_ldv, perm_stv, sel)\
{\
    /* unaligned load */                                       \
    vec_u8_t hv = vec_ld( 0, dest );                           \
    vec_u8_t lv = vec_ld( 7, dest );                           \
    vec_u8_t dstv   = vec_perm( hv, lv, (vec_u8_t)perm_ldv );  \
    vec_s16_t idct_sh6 = vec_sra(idctv, sixv);                 \
    vec_u16_t dst16 = vec_u8_to_u16_h(dstv);                   \
    vec_s16_t idstsum = vec_adds(idct_sh6, (vec_s16_t)dst16);  \
    vec_u8_t idstsum8 = vec_packsu(zero_s16v, idstsum);        \
    /* unaligned store */                                      \
    vec_u8_t bodyv  = vec_perm( idstsum8, idstsum8, perm_stv );\
    vec_u8_t edgelv = vec_perm( sel, zero_u8v, perm_stv );     \
    lv    = vec_sel( lv, bodyv, edgelv );                      \
    vec_st( lv, 7, dest );                                     \
    hv    = vec_ld( 0, dest );                                 \
    vec_u8_t edgehv = vec_perm( zero_u8v, sel, perm_stv );     \
    hv    = vec_sel( hv, bodyv, edgehv );                      \
    vec_st( hv, 0, dest );                                     \
}

void x264_add8x8_idct8_altivec( uint8_t *dst, int16_t dct[64] )
{
    vec_u16_t onev = vec_splat_u16(1);
    vec_u16_t twov = vec_splat_u16(2);

    dct[0] += 32; // rounding for the >>6 at the end

    vec_s16_t s0, s1, s2, s3, s4, s5, s6, s7;

    s0 = vec_ld(0x00, dct);
    s1 = vec_ld(0x10, dct);
    s2 = vec_ld(0x20, dct);
    s3 = vec_ld(0x30, dct);
    s4 = vec_ld(0x40, dct);
    s5 = vec_ld(0x50, dct);
    s6 = vec_ld(0x60, dct);
    s7 = vec_ld(0x70, dct);

    vec_s16_t d0, d1, d2, d3, d4, d5, d6, d7;
    IDCT8_1D_ALTIVEC(s0, s1, s2, s3, s4, s5, s6, s7,  d0, d1, d2, d3, d4, d5, d6, d7);

    vec_s16_t tr0, tr1, tr2, tr3, tr4, tr5, tr6, tr7;

    VEC_TRANSPOSE_8( d0,  d1,  d2,  d3,  d4,  d5,  d6, d7,
                    tr0, tr1, tr2, tr3, tr4, tr5, tr6, tr7);

    vec_s16_t idct0, idct1, idct2, idct3, idct4, idct5, idct6, idct7;
    IDCT8_1D_ALTIVEC(tr0,     tr1,   tr2,   tr3,   tr4,   tr5,   tr6,   tr7,
                     idct0, idct1, idct2, idct3, idct4, idct5, idct6, idct7);

    vec_u8_t perm_ldv = vec_lvsl(0, dst);
    vec_u8_t perm_stv = vec_lvsr(8, dst);
    vec_u16_t sixv = vec_splat_u16(6);
    const vec_u8_t sel = (vec_u8_t) CV(0,0,0,0,0,0,0,0,-1,-1,-1,-1,-1,-1,-1,-1);
    LOAD_ZERO;

    ALTIVEC_STORE_SUM_CLIP(&dst[0*FDEC_STRIDE], idct0, perm_ldv, perm_stv, sel);
    ALTIVEC_STORE_SUM_CLIP(&dst[1*FDEC_STRIDE], idct1, perm_ldv, perm_stv, sel);
    ALTIVEC_STORE_SUM_CLIP(&dst[2*FDEC_STRIDE], idct2, perm_ldv, perm_stv, sel);
    ALTIVEC_STORE_SUM_CLIP(&dst[3*FDEC_STRIDE], idct3, perm_ldv, perm_stv, sel);
    ALTIVEC_STORE_SUM_CLIP(&dst[4*FDEC_STRIDE], idct4, perm_ldv, perm_stv, sel);
    ALTIVEC_STORE_SUM_CLIP(&dst[5*FDEC_STRIDE], idct5, perm_ldv, perm_stv, sel);
    ALTIVEC_STORE_SUM_CLIP(&dst[6*FDEC_STRIDE], idct6, perm_ldv, perm_stv, sel);
    ALTIVEC_STORE_SUM_CLIP(&dst[7*FDEC_STRIDE], idct7, perm_ldv, perm_stv, sel);
}

void x264_add16x16_idct8_altivec( uint8_t *dst, int16_t dct[4][64] )
{
    x264_add8x8_idct8_altivec( &dst[0],               dct[0] );
    x264_add8x8_idct8_altivec( &dst[8],               dct[1] );
    x264_add8x8_idct8_altivec( &dst[8*FDEC_STRIDE+0], dct[2] );
    x264_add8x8_idct8_altivec( &dst[8*FDEC_STRIDE+8], dct[3] );
}

void x264_zigzag_scan_4x4_frame_altivec( int16_t level[16], int16_t dct[16] )
{
    vec_s16_t dct0v, dct1v;
    vec_s16_t tmp0v, tmp1v;

    dct0v = vec_ld(0x00, dct);
    dct1v = vec_ld(0x10, dct);

    const vec_u8_t sel0 = (vec_u8_t) CV(0,1,8,9,2,3,4,5,10,11,16,17,24,25,18,19);
    const vec_u8_t sel1 = (vec_u8_t) CV(12,13,6,7,14,15,20,21,26,27,28,29,22,23,30,31);

    tmp0v = vec_perm( dct0v, dct1v, sel0 );
    tmp1v = vec_perm( dct0v, dct1v, sel1 );

    vec_st( tmp0v, 0x00, level );
    vec_st( tmp1v, 0x10, level );
}

void x264_zigzag_scan_4x4_field_altivec( int16_t level[16], int16_t dct[16] )
{
    vec_s16_t dct0v, dct1v;
    vec_s16_t tmp0v, tmp1v;

    dct0v = vec_ld(0x00, dct);
    dct1v = vec_ld(0x10, dct);

    const vec_u8_t sel0 = (vec_u8_t) CV(0,1,2,3,8,9,4,5,6,7,10,11,12,13,14,15);

    tmp0v = vec_perm( dct0v, dct1v, sel0 );
    tmp1v = dct1v;

    vec_st( tmp0v, 0x00, level );
    vec_st( tmp1v, 0x10, level );
}
#endif // !HIGH_BIT_DEPTH

