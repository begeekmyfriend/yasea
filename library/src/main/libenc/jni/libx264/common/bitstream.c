/*****************************************************************************
 * bitstream.c: bitstream writing
 *****************************************************************************
 * Copyright (C) 2003-2016 x264 project
 *
 * Authors: Laurent Aimar <fenrir@via.ecp.fr>
 *          Fiona Glaser <fiona@x264.com>
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

#include "common.h"

static uint8_t *x264_nal_escape_c( uint8_t *dst, uint8_t *src, uint8_t *end )
{
    if( src < end ) *dst++ = *src++;
    if( src < end ) *dst++ = *src++;
    while( src < end )
    {
        if( src[0] <= 0x03 && !dst[-2] && !dst[-1] )
            *dst++ = 0x03;
        *dst++ = *src++;
    }
    return dst;
}

uint8_t *x264_nal_escape_mmx2( uint8_t *dst, uint8_t *src, uint8_t *end );
uint8_t *x264_nal_escape_sse2( uint8_t *dst, uint8_t *src, uint8_t *end );
uint8_t *x264_nal_escape_avx2( uint8_t *dst, uint8_t *src, uint8_t *end );
void x264_cabac_block_residual_rd_internal_sse2       ( dctcoef *l, int b_interlaced, intptr_t ctx_block_cat, x264_cabac_t *cb );
void x264_cabac_block_residual_rd_internal_sse2_lzcnt ( dctcoef *l, int b_interlaced, intptr_t ctx_block_cat, x264_cabac_t *cb );
void x264_cabac_block_residual_rd_internal_ssse3      ( dctcoef *l, int b_interlaced, intptr_t ctx_block_cat, x264_cabac_t *cb );
void x264_cabac_block_residual_rd_internal_ssse3_lzcnt( dctcoef *l, int b_interlaced, intptr_t ctx_block_cat, x264_cabac_t *cb );
void x264_cabac_block_residual_8x8_rd_internal_sse2       ( dctcoef *l, int b_interlaced, intptr_t ctx_block_cat, x264_cabac_t *cb );
void x264_cabac_block_residual_8x8_rd_internal_sse2_lzcnt ( dctcoef *l, int b_interlaced, intptr_t ctx_block_cat, x264_cabac_t *cb );
void x264_cabac_block_residual_8x8_rd_internal_ssse3      ( dctcoef *l, int b_interlaced, intptr_t ctx_block_cat, x264_cabac_t *cb );
void x264_cabac_block_residual_8x8_rd_internal_ssse3_lzcnt( dctcoef *l, int b_interlaced, intptr_t ctx_block_cat, x264_cabac_t *cb );
void x264_cabac_block_residual_internal_sse2       ( dctcoef *l, int b_interlaced, intptr_t ctx_block_cat, x264_cabac_t *cb );
void x264_cabac_block_residual_internal_sse2_lzcnt ( dctcoef *l, int b_interlaced, intptr_t ctx_block_cat, x264_cabac_t *cb );
void x264_cabac_block_residual_internal_avx2_bmi2 ( dctcoef *l, int b_interlaced, intptr_t ctx_block_cat, x264_cabac_t *cb );

uint8_t *x264_nal_escape_neon( uint8_t *dst, uint8_t *src, uint8_t *end );

/****************************************************************************
 * x264_nal_encode:
 ****************************************************************************/
void x264_nal_encode( x264_t *h, uint8_t *dst, x264_nal_t *nal )
{
    uint8_t *src = nal->p_payload;
    uint8_t *end = nal->p_payload + nal->i_payload;
    uint8_t *orig_dst = dst;

    if( h->param.b_annexb )
    {
        if( nal->b_long_startcode )
            *dst++ = 0x00;
        *dst++ = 0x00;
        *dst++ = 0x00;
        *dst++ = 0x01;
    }
    else /* save room for size later */
        dst += 4;

    /* nal header */
    *dst++ = ( 0x00 << 7 ) | ( nal->i_ref_idc << 5 ) | nal->i_type;

    dst = h->bsf.nal_escape( dst, src, end );
    int size = dst - orig_dst;

    /* Apply AVC-Intra padding */
    if( h->param.i_avcintra_class )
    {
        int padding = nal->i_payload + nal->i_padding + NALU_OVERHEAD - size;
        if( padding > 0 )
        {
            memset( dst, 0, padding );
            size += padding;
        }
        nal->i_padding = X264_MAX( padding, 0 );
    }

    /* Write the size header for mp4/etc */
    if( !h->param.b_annexb )
    {
        /* Size doesn't include the size of the header we're writing now. */
        int chunk_size = size - 4;
        orig_dst[0] = chunk_size >> 24;
        orig_dst[1] = chunk_size >> 16;
        orig_dst[2] = chunk_size >> 8;
        orig_dst[3] = chunk_size >> 0;
    }

    nal->i_payload = size;
    nal->p_payload = orig_dst;
    x264_emms();
}

void x264_bitstream_init( int cpu, x264_bitstream_function_t *pf )
{
    memset( pf, 0, sizeof(*pf) );

    pf->nal_escape = x264_nal_escape_c;
#if HAVE_MMX
#if ARCH_X86_64
    pf->cabac_block_residual_internal = x264_cabac_block_residual_internal_sse2;
    pf->cabac_block_residual_rd_internal = x264_cabac_block_residual_rd_internal_sse2;
    pf->cabac_block_residual_8x8_rd_internal = x264_cabac_block_residual_8x8_rd_internal_sse2;
#endif

    if( cpu&X264_CPU_MMX2 )
        pf->nal_escape = x264_nal_escape_mmx2;
    if( cpu&X264_CPU_SSE2 )
    {
#if ARCH_X86_64
        if( cpu&X264_CPU_LZCNT )
        {
            pf->cabac_block_residual_internal = x264_cabac_block_residual_internal_sse2_lzcnt;
            pf->cabac_block_residual_rd_internal = x264_cabac_block_residual_rd_internal_sse2_lzcnt;
            pf->cabac_block_residual_8x8_rd_internal = x264_cabac_block_residual_8x8_rd_internal_sse2_lzcnt;
        }
#endif
        if( cpu&X264_CPU_SSE2_IS_FAST )
            pf->nal_escape = x264_nal_escape_sse2;
    }
#if ARCH_X86_64
    if( cpu&X264_CPU_SSSE3 )
    {
        pf->cabac_block_residual_rd_internal = x264_cabac_block_residual_rd_internal_ssse3;
        pf->cabac_block_residual_8x8_rd_internal = x264_cabac_block_residual_8x8_rd_internal_ssse3;
        if( cpu&X264_CPU_LZCNT )
        {
            pf->cabac_block_residual_rd_internal = x264_cabac_block_residual_rd_internal_ssse3_lzcnt;
            pf->cabac_block_residual_8x8_rd_internal = x264_cabac_block_residual_8x8_rd_internal_ssse3_lzcnt;
        }
    }

    if( cpu&X264_CPU_AVX2 )
    {
        pf->nal_escape = x264_nal_escape_avx2;
        if( cpu&X264_CPU_BMI2 )
            pf->cabac_block_residual_internal = x264_cabac_block_residual_internal_avx2_bmi2;
    }
#endif
#endif
#if HAVE_ARMV6
    if( cpu&X264_CPU_NEON )
        pf->nal_escape = x264_nal_escape_neon;
#endif
#if ARCH_AARCH64
    if( cpu&X264_CPU_NEON )
        pf->nal_escape = x264_nal_escape_neon;
#endif
}
