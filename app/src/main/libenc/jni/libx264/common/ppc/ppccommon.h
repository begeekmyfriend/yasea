/*****************************************************************************
 * ppccommon.h: ppc utility macros
 *****************************************************************************
 * Copyright (C) 2003-2016 x264 project
 *
 * Authors: Eric Petit <eric.petit@lapsus.org>
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

#if HAVE_ALTIVEC_H
#include <altivec.h>
#endif

/***********************************************************************
 * For constant vectors, use parentheses on OS X and braces on Linux
 **********************************************************************/
#if defined(__APPLE__) && __GNUC__ < 4
#define CV(a...) (a)
#else
#define CV(a...) {a}
#endif

/***********************************************************************
 * Vector types
 **********************************************************************/
#define vec_u8_t  vector unsigned char
#define vec_s8_t  vector signed char
#define vec_u16_t vector unsigned short
#define vec_s16_t vector signed short
#define vec_u32_t vector unsigned int
#define vec_s32_t vector signed int

typedef union {
  uint32_t s[4];
  vec_u32_t v;
} vec_u32_u;

typedef union {
  uint16_t s[8];
  vec_u16_t v;
} vec_u16_u;

typedef union {
  int16_t s[8];
  vec_s16_t v;
} vec_s16_u;

typedef union {
  uint8_t s[16];
  vec_u8_t v;
} vec_u8_u;

/***********************************************************************
 * Null vector
 **********************************************************************/
#define LOAD_ZERO const vec_u8_t zerov = vec_splat_u8( 0 )

#define zero_u8v  (vec_u8_t)  zerov
#define zero_s8v  (vec_s8_t)  zerov
#define zero_u16v (vec_u16_t) zerov
#define zero_s16v (vec_s16_t) zerov
#define zero_u32v (vec_u32_t) zerov
#define zero_s32v (vec_s32_t) zerov

/***********************************************************************
 * 8 <-> 16 bits conversions
 **********************************************************************/
#ifdef WORDS_BIGENDIAN
#define vec_u8_to_u16_h(v) (vec_u16_t) vec_mergeh( zero_u8v, (vec_u8_t) v )
#define vec_u8_to_u16_l(v) (vec_u16_t) vec_mergel( zero_u8v, (vec_u8_t) v )
#define vec_u8_to_s16_h(v) (vec_s16_t) vec_mergeh( zero_u8v, (vec_u8_t) v )
#define vec_u8_to_s16_l(v) (vec_s16_t) vec_mergel( zero_u8v, (vec_u8_t) v )
#else
#define vec_u8_to_u16_h(v) (vec_u16_t) vec_mergeh( (vec_u8_t) v, zero_u8v )
#define vec_u8_to_u16_l(v) (vec_u16_t) vec_mergel( (vec_u8_t) v, zero_u8v )
#define vec_u8_to_s16_h(v) (vec_s16_t) vec_mergeh( (vec_u8_t) v, zero_u8v )
#define vec_u8_to_s16_l(v) (vec_s16_t) vec_mergel( (vec_u8_t) v, zero_u8v )
#endif

#define vec_u8_to_u16(v) vec_u8_to_u16_h(v)
#define vec_u8_to_s16(v) vec_u8_to_s16_h(v)

#define vec_u16_to_u8(v) vec_pack( v, zero_u16v )
#define vec_s16_to_u8(v) vec_packsu( v, zero_s16v )


/***********************************************************************
 * 16 <-> 32 bits conversions
 **********************************************************************/
#ifdef WORDS_BIGENDIAN
#define vec_u16_to_u32_h(v) (vec_u32_t) vec_mergeh( zero_u16v, (vec_u16_t) v )
#define vec_u16_to_u32_l(v) (vec_u32_t) vec_mergel( zero_u16v, (vec_u16_t) v )
#define vec_u16_to_s32_h(v) (vec_s32_t) vec_mergeh( zero_u16v, (vec_u16_t) v )
#define vec_u16_to_s32_l(v) (vec_s32_t) vec_mergel( zero_u16v, (vec_u16_t) v )
#else
#define vec_u16_to_u32_h(v) (vec_u32_t) vec_mergeh( (vec_u16_t) v, zero_u16v )
#define vec_u16_to_u32_l(v) (vec_u32_t) vec_mergel( (vec_u16_t) v, zero_u16v )
#define vec_u16_to_s32_h(v) (vec_s32_t) vec_mergeh( (vec_u16_t) v, zero_u16v )
#define vec_u16_to_s32_l(v) (vec_s32_t) vec_mergel( (vec_u16_t) v, zero_u16v )
#endif

#define vec_u16_to_u32(v) vec_u16_to_u32_h(v)
#define vec_u16_to_s32(v) vec_u16_to_s32_h(v)

#define vec_u32_to_u16(v) vec_pack( v, zero_u32v )
#define vec_s32_to_u16(v) vec_packsu( v, zero_s32v )


/***********************************************************************
 * PREP_LOAD: declares two vectors required to perform unaligned loads
 * VEC_LOAD:  loads n bytes from u8 * p into vector v of type t where o is from original src offset
 * VEC_LOAD:_G: loads n bytes from u8 * p into vectory v of type t - use when offset is not known
 * VEC_LOAD_OFFSET: as above, but with offset vector known in advance
 **********************************************************************/
#define PREP_LOAD     \
    vec_u8_t _hv, _lv

#define PREP_LOAD_SRC( src )              \
    vec_u8_t _##src##_ = vec_lvsl(0, src)

#define VEC_LOAD_G( p, v, n, t )                 \
    _hv = vec_ld( 0, p );                        \
    v   = (t) vec_lvsl( 0, p );                  \
    _lv = vec_ld( n - 1, p );                    \
    v   = (t) vec_perm( _hv, _lv, (vec_u8_t) v )

#define VEC_LOAD( p, v, n, t, g )                   \
    _hv = vec_ld( 0, p );                           \
    _lv = vec_ld( n - 1, p );                       \
    v = (t) vec_perm( _hv, _lv, (vec_u8_t) _##g##_ )

#define VEC_LOAD_OFFSET( p, v, n, t, o )         \
    _hv = vec_ld( 0, p);                         \
    _lv = vec_ld( n - 1, p );                    \
    v   = (t) vec_perm( _hv, _lv, (vec_u8_t) o )

#define VEC_LOAD_PARTIAL( p, v, n, t, g)               \
    _hv = vec_ld( 0, p);                               \
    v   = (t) vec_perm( _hv, _hv, (vec_u8_t) _##g##_ )


/***********************************************************************
 * PREP_STORE##n: declares required vectors to store n bytes to a
 *                potentially unaligned address
 * VEC_STORE##n:  stores n bytes from vector v to address p
 **********************************************************************/
#define PREP_STORE16 \
    vec_u8_t _tmp1v  \

#define PREP_STORE16_DST( dst )             \
    vec_u8_t _##dst##l_ = vec_lvsl(0, dst); \
    vec_u8_t _##dst##r_ = vec_lvsr(0, dst);

#define VEC_STORE16( v, p, o )                           \
    _hv    = vec_ld( 0, p );                             \
    _lv    = vec_ld( 15, p );                            \
    _tmp1v = vec_perm( _lv, _hv, _##o##l_ );             \
    _lv    = vec_perm( (vec_u8_t) v, _tmp1v, _##o##r_ ); \
    vec_st( _lv, 15, (uint8_t *) p );                    \
    _hv    = vec_perm( _tmp1v, (vec_u8_t) v, _##o##r_ ); \
    vec_st( _hv, 0, (uint8_t *) p )


#define PREP_STORE8 \
    vec_u8_t _tmp3v \

#define VEC_STORE8( v, p )                \
    _tmp3v = vec_lvsl(0, p);              \
    v = vec_perm(v, v, _tmp3v);           \
    vec_ste((vec_u32_t)v,0,(uint32_t*)p); \
    vec_ste((vec_u32_t)v,4,(uint32_t*)p)


#define PREP_STORE4                                        \
    PREP_STORE16;                                          \
    vec_u8_t _tmp2v, _tmp3v;                               \
    const vec_u8_t sel =                                   \
        (vec_u8_t) CV(-1,-1,-1,-1,0,0,0,0,0,0,0,0,0,0,0,0)

#define VEC_STORE4( v, p )                      \
    _tmp3v = vec_lvsr( 0, p );                  \
    v      = vec_perm( v, v, _tmp3v );          \
    _lv    = vec_ld( 3, p );                    \
    _tmp1v = vec_perm( sel, zero_u8v, _tmp3v ); \
    _lv    = vec_sel( _lv, v, _tmp1v );         \
    vec_st( _lv, 3, p );                        \
    _hv    = vec_ld( 0, p );                    \
    _tmp2v = vec_perm( zero_u8v, sel, _tmp3v ); \
    _hv    = vec_sel( _hv, v, _tmp2v );         \
    vec_st( _hv, 0, p )

/***********************************************************************
 * VEC_TRANSPOSE_8
 ***********************************************************************
 * Transposes a 8x8 matrix of s16 vectors
 **********************************************************************/
#define VEC_TRANSPOSE_8(a0,a1,a2,a3,a4,a5,a6,a7,b0,b1,b2,b3,b4,b5,b6,b7) \
    b0 = vec_mergeh( a0, a4 ); \
    b1 = vec_mergel( a0, a4 ); \
    b2 = vec_mergeh( a1, a5 ); \
    b3 = vec_mergel( a1, a5 ); \
    b4 = vec_mergeh( a2, a6 ); \
    b5 = vec_mergel( a2, a6 ); \
    b6 = vec_mergeh( a3, a7 ); \
    b7 = vec_mergel( a3, a7 ); \
    a0 = vec_mergeh( b0, b4 ); \
    a1 = vec_mergel( b0, b4 ); \
    a2 = vec_mergeh( b1, b5 ); \
    a3 = vec_mergel( b1, b5 ); \
    a4 = vec_mergeh( b2, b6 ); \
    a5 = vec_mergel( b2, b6 ); \
    a6 = vec_mergeh( b3, b7 ); \
    a7 = vec_mergel( b3, b7 ); \
    b0 = vec_mergeh( a0, a4 ); \
    b1 = vec_mergel( a0, a4 ); \
    b2 = vec_mergeh( a1, a5 ); \
    b3 = vec_mergel( a1, a5 ); \
    b4 = vec_mergeh( a2, a6 ); \
    b5 = vec_mergel( a2, a6 ); \
    b6 = vec_mergeh( a3, a7 ); \
    b7 = vec_mergel( a3, a7 )

/***********************************************************************
 * VEC_TRANSPOSE_4
 ***********************************************************************
 * Transposes a 4x4 matrix of s16 vectors.
 * Actually source and destination are 8x4. The low elements of the
 * source are discarded and the low elements of the destination mustn't
 * be used.
 **********************************************************************/
#define VEC_TRANSPOSE_4(a0,a1,a2,a3,b0,b1,b2,b3) \
    b0 = vec_mergeh( a0, a0 ); \
    b1 = vec_mergeh( a1, a0 ); \
    b2 = vec_mergeh( a2, a0 ); \
    b3 = vec_mergeh( a3, a0 ); \
    a0 = vec_mergeh( b0, b2 ); \
    a1 = vec_mergel( b0, b2 ); \
    a2 = vec_mergeh( b1, b3 ); \
    a3 = vec_mergel( b1, b3 ); \
    b0 = vec_mergeh( a0, a2 ); \
    b1 = vec_mergel( a0, a2 ); \
    b2 = vec_mergeh( a1, a3 ); \
    b3 = vec_mergel( a1, a3 )

/***********************************************************************
 * VEC_DIFF_H
 ***********************************************************************
 * p1, p2:    u8 *
 * i1, i2, n: int
 * d:         s16v
 *
 * Loads n bytes from p1 and p2, do the diff of the high elements into
 * d, increments p1 and p2 by i1 and i2 into known offset g
 **********************************************************************/
#define PREP_DIFF           \
    LOAD_ZERO;              \
    PREP_LOAD;              \
    vec_s16_t pix1v, pix2v;


#define VEC_DIFF_H(p1,i1,p2,i2,n,d,g)               \
    VEC_LOAD_PARTIAL( p1, pix1v, n, vec_s16_t, p1); \
    pix1v = vec_u8_to_s16( pix1v );                 \
    VEC_LOAD( p2, pix2v, n, vec_s16_t, g);          \
    pix2v = vec_u8_to_s16( pix2v );                 \
    d     = vec_sub( pix1v, pix2v );                \
    p1   += i1;                                     \
    p2   += i2

#define VEC_DIFF_H_OFFSET(p1,i1,p2,i2,n,d,g1,g2)    \
    pix1v = (vec_s16_t)vec_perm( vec_ld( 0, p1 ), zero_u8v, _##g1##_ );\
    pix1v = vec_u8_to_s16( pix1v );                 \
    VEC_LOAD( p2, pix2v, n, vec_s16_t, g2);         \
    pix2v = vec_u8_to_s16( pix2v );                 \
    d     = vec_sub( pix1v, pix2v );                \
    p1   += i1;                                     \
    p2   += i2


/***********************************************************************
 * VEC_DIFF_HL
 ***********************************************************************
 * p1, p2: u8 *
 * i1, i2: int
 * dh, dl: s16v
 *
 * Loads 16 bytes from p1 and p2, do the diff of the high elements into
 * dh, the diff of the low elements into dl, increments p1 and p2 by i1
 * and i2
 **********************************************************************/
#define VEC_DIFF_HL(p1,i1,p2,i2,dh,dl)       \
    pix1v = (vec_s16_t)vec_ld(0, p1);        \
    temp0v = vec_u8_to_s16_h( pix1v );       \
    temp1v = vec_u8_to_s16_l( pix1v );       \
    VEC_LOAD( p2, pix2v, 16, vec_s16_t, p2); \
    temp2v = vec_u8_to_s16_h( pix2v );       \
    temp3v = vec_u8_to_s16_l( pix2v );       \
    dh     = vec_sub( temp0v, temp2v );      \
    dl     = vec_sub( temp1v, temp3v );      \
    p1    += i1;                             \
    p2    += i2

/***********************************************************************
* VEC_DIFF_H_8BYTE_ALIGNED
***********************************************************************
* p1, p2:    u8 *
* i1, i2, n: int
* d:         s16v
*
* Loads n bytes from p1 and p2, do the diff of the high elements into
* d, increments p1 and p2 by i1 and i2
* Slightly faster when we know we are loading/diffing 8bytes which
* are 8 byte aligned. Reduces need for two loads and two vec_lvsl()'s
**********************************************************************/
#define PREP_DIFF_8BYTEALIGNED \
LOAD_ZERO;                     \
vec_s16_t pix1v, pix2v;        \
vec_u8_t pix1v8, pix2v8;       \
vec_u8_t permPix1, permPix2;   \
permPix1 = vec_lvsl(0, pix1);  \
permPix2 = vec_lvsl(0, pix2);  \

#define VEC_DIFF_H_8BYTE_ALIGNED(p1,i1,p2,i2,n,d)     \
pix1v8 = vec_perm(vec_ld(0,p1), zero_u8v, permPix1);  \
pix2v8 = vec_perm(vec_ld(0, p2), zero_u8v, permPix2); \
pix1v = vec_u8_to_s16( pix1v8 );                      \
pix2v = vec_u8_to_s16( pix2v8 );                      \
d = vec_sub( pix1v, pix2v);                           \
p1 += i1;                                             \
p2 += i2;
