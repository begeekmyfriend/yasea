/*****************************************************************************
 * quant.h: msa quantization and level-run
 *****************************************************************************
 * Copyright (C) 2015-2017 x264 project
 *
 * Authors: Rishikesh More <rishikesh.more@imgtec.com>
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

#ifndef X264_MIPS_QUANT_H
#define X264_MIPS_QUANT_H

void x264_dequant_4x4_msa( int16_t *p_dct, int32_t pi_dequant_mf[6][16],
                           int32_t i_qp );
void x264_dequant_8x8_msa( int16_t *p_dct, int32_t pi_dequant_mf[6][64],
                           int32_t i_qp );
void x264_dequant_4x4_dc_msa( int16_t *p_dct, int32_t pi_dequant_mf[6][16],
                              int32_t i_qp );
int32_t x264_quant_4x4_msa( int16_t *p_dct, uint16_t *p_mf, uint16_t *p_bias );
int32_t x264_quant_4x4x4_msa( int16_t p_dct[4][16],
                              uint16_t pu_mf[16], uint16_t pu_bias[16] );
int32_t x264_quant_8x8_msa( int16_t *p_dct, uint16_t *p_mf, uint16_t *p_bias );
int32_t x264_quant_4x4_dc_msa( int16_t *p_dct, int32_t i_mf, int32_t i_bias );
int32_t x264_coeff_last64_msa( int16_t *p_src );
int32_t x264_coeff_last16_msa( int16_t *p_src );

#endif
