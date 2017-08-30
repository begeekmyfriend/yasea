/*****************************************************************************
 * pixel.h: aarch64 pixel metrics
 *****************************************************************************
 * Copyright (C) 2009-2017 x264 project
 *
 * Authors: David Conrad <lessen42@gmail.com>
 *          Janne Grunau <janne-x264@jannau.net>
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

#ifndef X264_AARCH64_PIXEL_H
#define X264_AARCH64_PIXEL_H

#define DECL_PIXELS( ret, name, suffix, args ) \
    ret x264_pixel_##name##_16x16_##suffix args;\
    ret x264_pixel_##name##_16x8_##suffix args;\
    ret x264_pixel_##name##_8x16_##suffix args;\
    ret x264_pixel_##name##_8x8_##suffix args;\
    ret x264_pixel_##name##_8x4_##suffix args;\
    ret x264_pixel_##name##_4x16_##suffix args;\
    ret x264_pixel_##name##_4x8_##suffix args;\
    ret x264_pixel_##name##_4x4_##suffix args;\

#define DECL_X1( name, suffix ) \
    DECL_PIXELS( int, name, suffix, ( uint8_t *, intptr_t, uint8_t *, intptr_t ) )

#define DECL_X4( name, suffix ) \
    DECL_PIXELS( void, name##_x3, suffix, ( uint8_t *, uint8_t *, uint8_t *, uint8_t *, intptr_t, int * ) )\
    DECL_PIXELS( void, name##_x4, suffix, ( uint8_t *, uint8_t *, uint8_t *, uint8_t *, uint8_t *, intptr_t, int * ) )

DECL_X1( sad, neon )
DECL_X4( sad, neon )
DECL_X1( satd, neon )
DECL_X1( ssd, neon )


void x264_pixel_ssd_nv12_core_neon( uint8_t *, intptr_t, uint8_t *, intptr_t, int, int, uint64_t *, uint64_t * );

int x264_pixel_vsad_neon( uint8_t *, intptr_t, int );

int x264_pixel_sa8d_8x8_neon  ( uint8_t *, intptr_t, uint8_t *, intptr_t );
int x264_pixel_sa8d_16x16_neon( uint8_t *, intptr_t, uint8_t *, intptr_t );
uint64_t x264_pixel_sa8d_satd_16x16_neon( uint8_t *, intptr_t, uint8_t *, intptr_t );

uint64_t x264_pixel_var_8x8_neon  ( uint8_t *, intptr_t );
uint64_t x264_pixel_var_8x16_neon ( uint8_t *, intptr_t );
uint64_t x264_pixel_var_16x16_neon( uint8_t *, intptr_t );
int x264_pixel_var2_8x8_neon ( uint8_t *, uint8_t *, int * );
int x264_pixel_var2_8x16_neon( uint8_t *, uint8_t *, int * );

uint64_t x264_pixel_hadamard_ac_8x8_neon  ( uint8_t *, intptr_t );
uint64_t x264_pixel_hadamard_ac_8x16_neon ( uint8_t *, intptr_t );
uint64_t x264_pixel_hadamard_ac_16x8_neon ( uint8_t *, intptr_t );
uint64_t x264_pixel_hadamard_ac_16x16_neon( uint8_t *, intptr_t );

void x264_pixel_ssim_4x4x2_core_neon( const uint8_t *, intptr_t,
                                      const uint8_t *, intptr_t,
                                      int sums[2][4] );
float x264_pixel_ssim_end4_neon( int sum0[5][4], int sum1[5][4], int width );

int x264_pixel_asd8_neon( uint8_t *, intptr_t,  uint8_t *, intptr_t, int );

#endif
