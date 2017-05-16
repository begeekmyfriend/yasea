/*****************************************************************************
 * set.h: quantization init
 *****************************************************************************
 * Copyright (C) 2003-2017 x264 project
 *
 * Authors: Loren Merritt <lorenm@u.washington.edu>
 *          Laurent Aimar <fenrir@via.ecp.fr>
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

#ifndef X264_SET_H
#define X264_SET_H

enum profile_e
{
    PROFILE_BASELINE = 66,
    PROFILE_MAIN     = 77,
    PROFILE_HIGH    = 100,
    PROFILE_HIGH10  = 110,
    PROFILE_HIGH422 = 122,
    PROFILE_HIGH444_PREDICTIVE = 244,
};

enum chroma_format_e
{
    CHROMA_400 = 0,
    CHROMA_420 = 1,
    CHROMA_422 = 2,
    CHROMA_444 = 3,
};

enum cqm4_e
{
    CQM_4IY = 0,
    CQM_4PY = 1,
    CQM_4IC = 2,
    CQM_4PC = 3
};
enum cqm8_e
{
    CQM_8IY = 0,
    CQM_8PY = 1,
    CQM_8IC = 2,
    CQM_8PC = 3,
};

typedef struct
{
    int i_id;

    int i_profile_idc;
    int i_level_idc;

    int b_constraint_set0;
    int b_constraint_set1;
    int b_constraint_set2;
    int b_constraint_set3;

    int i_log2_max_frame_num;

    int i_poc_type;
    /* poc 0 */
    int i_log2_max_poc_lsb;

    int i_num_ref_frames;
    int b_gaps_in_frame_num_value_allowed;
    int i_mb_width;
    int i_mb_height;
    int b_frame_mbs_only;
    int b_mb_adaptive_frame_field;
    int b_direct8x8_inference;

    int b_crop;
    struct
    {
        int i_left;
        int i_right;
        int i_top;
        int i_bottom;
    } crop;

    int b_vui;
    struct
    {
        int b_aspect_ratio_info_present;
        int i_sar_width;
        int i_sar_height;

        int b_overscan_info_present;
        int b_overscan_info;

        int b_signal_type_present;
        int i_vidformat;
        int b_fullrange;
        int b_color_description_present;
        int i_colorprim;
        int i_transfer;
        int i_colmatrix;

        int b_chroma_loc_info_present;
        int i_chroma_loc_top;
        int i_chroma_loc_bottom;

        int b_timing_info_present;
        uint32_t i_num_units_in_tick;
        uint32_t i_time_scale;
        int b_fixed_frame_rate;

        int b_nal_hrd_parameters_present;
        int b_vcl_hrd_parameters_present;

        struct
        {
            int i_cpb_cnt;
            int i_bit_rate_scale;
            int i_cpb_size_scale;
            int i_bit_rate_value;
            int i_cpb_size_value;
            int i_bit_rate_unscaled;
            int i_cpb_size_unscaled;
            int b_cbr_hrd;

            int i_initial_cpb_removal_delay_length;
            int i_cpb_removal_delay_length;
            int i_dpb_output_delay_length;
            int i_time_offset_length;
        } hrd;

        int b_pic_struct_present;
        int b_bitstream_restriction;
        int b_motion_vectors_over_pic_boundaries;
        int i_max_bytes_per_pic_denom;
        int i_max_bits_per_mb_denom;
        int i_log2_max_mv_length_horizontal;
        int i_log2_max_mv_length_vertical;
        int i_num_reorder_frames;
        int i_max_dec_frame_buffering;

        /* FIXME to complete */
    } vui;

    int b_qpprime_y_zero_transform_bypass;
    int i_chroma_format_idc;

} x264_sps_t;

typedef struct
{
    int i_id;
    int i_sps_id;

    int b_cabac;

    int b_pic_order;
    int i_num_slice_groups;

    int i_num_ref_idx_l0_default_active;
    int i_num_ref_idx_l1_default_active;

    int b_weighted_pred;
    int b_weighted_bipred;

    int i_pic_init_qp;
    int i_pic_init_qs;

    int i_chroma_qp_index_offset;

    int b_deblocking_filter_control;
    int b_constrained_intra_pred;
    int b_redundant_pic_cnt;

    int b_transform_8x8_mode;

    int i_cqm_preset;
    const uint8_t *scaling_list[8]; /* could be 12, but we don't allow separate Cb/Cr lists */

} x264_pps_t;

/* default quant matrices */
static const uint8_t x264_cqm_jvt4i[16] =
{
      6,13,20,28,
     13,20,28,32,
     20,28,32,37,
     28,32,37,42
};
static const uint8_t x264_cqm_jvt4p[16] =
{
    10,14,20,24,
    14,20,24,27,
    20,24,27,30,
    24,27,30,34
};
static const uint8_t x264_cqm_jvt8i[64] =
{
     6,10,13,16,18,23,25,27,
    10,11,16,18,23,25,27,29,
    13,16,18,23,25,27,29,31,
    16,18,23,25,27,29,31,33,
    18,23,25,27,29,31,33,36,
    23,25,27,29,31,33,36,38,
    25,27,29,31,33,36,38,40,
    27,29,31,33,36,38,40,42
};
static const uint8_t x264_cqm_jvt8p[64] =
{
     9,13,15,17,19,21,22,24,
    13,13,17,19,21,22,24,25,
    15,17,19,21,22,24,25,27,
    17,19,21,22,24,25,27,28,
    19,21,22,24,25,27,28,30,
    21,22,24,25,27,28,30,32,
    22,24,25,27,28,30,32,33,
    24,25,27,28,30,32,33,35
};
static const uint8_t x264_cqm_flat16[64] =
{
    16,16,16,16,16,16,16,16,
    16,16,16,16,16,16,16,16,
    16,16,16,16,16,16,16,16,
    16,16,16,16,16,16,16,16,
    16,16,16,16,16,16,16,16,
    16,16,16,16,16,16,16,16,
    16,16,16,16,16,16,16,16,
    16,16,16,16,16,16,16,16
};
static const uint8_t * const x264_cqm_jvt[8] =
{
    x264_cqm_jvt4i, x264_cqm_jvt4p,
    x264_cqm_jvt4i, x264_cqm_jvt4p,
    x264_cqm_jvt8i, x264_cqm_jvt8p,
    x264_cqm_jvt8i, x264_cqm_jvt8p
};

// 1080i25_avci50, 1080p25_avci50
static const uint8_t x264_cqm_avci50_4ic[16] =
{
    16,22,28,40,
    22,28,40,44,
    28,40,44,48,
    40,44,48,60
};

//  1080i25_avci50,
static const uint8_t x264_cqm_avci50_1080i_8iy[64] =
{
    16,18,19,21,27,33,81,87,
    18,19,21,24,30,33,81,87,
    19,21,24,27,30,78,84,90,
    21,24,27,30,33,78,84,90,
    24,27,30,33,78,81,84,90,
    24,27,30,33,78,81,84,93,
    27,30,33,78,78,81,87,93,
    30,33,33,78,81,84,87,96
};

//  1080p25_avci50, 720p25_avci50, 720p50_avci50
static const uint8_t x264_cqm_avci50_p_8iy[64] =
{
    16,18,19,21,24,27,30,33,
    18,19,21,24,27,30,33,78,
    19,21,24,27,30,33,78,81,
    21,24,27,30,33,78,81,84,
    24,27,30,33,78,81,84,87,
    27,30,33,78,81,84,87,90,
    30,33,78,81,84,87,90,93,
    33,78,81,84,87,90,93,96
};

//  1080i25_avci100, 1080p25_avci100
static const uint8_t x264_cqm_avci100_1080_4ic[16] =
{
    16,20,26,32,
    20,26,32,38,
    26,32,38,44,
    32,38,44,50
};

// 720p25_avci100, 720p50_avci100
static const uint8_t x264_cqm_avci100_720p_4ic[16] =
{
    16,21,27,34,
    21,27,34,41,
    27,34,41,46,
    34,41,46,54
};

//  1080i25_avci100,
static const uint8_t x264_cqm_avci100_1080i_8iy[64] =
{
    16,19,20,23,24,26,32,42,
    18,19,22,24,26,32,36,42,
    18,20,23,24,26,32,36,63,
    19,20,23,26,32,36,42,63,
    20,22,24,26,32,36,59,63,
    22,23,24,26,32,36,59,68,
    22,23,24,26,32,42,59,68,
    22,23,24,26,36,42,59,72
};

// 1080p25_avci100,
static const uint8_t x264_cqm_avci100_1080p_8iy[64] =
{
    16,18,19,20,22,23,24,26,
    18,19,20,22,23,24,26,32,
    19,20,22,23,24,26,32,36,
    20,22,23,24,26,32,36,42,
    22,23,24,26,32,36,42,59,
    23,24,26,32,36,42,59,63,
    24,26,32,36,42,59,63,68,
    26,32,36,42,59,63,68,72
};

// 720p25_avci100, 720p50_avci100
static const uint8_t x264_cqm_avci100_720p_8iy[64] =
{
    16,18,19,21,22,24,26,32,
    18,19,19,21,22,24,26,32,
    19,19,21,22,22,24,26,32,
    21,21,22,22,23,24,26,34,
    22,22,22,23,24,25,26,34,
    24,24,24,24,25,26,34,36,
    26,26,26,26,26,34,36,38,
    32,32,32,34,34,36,38,42
};

int  x264_cqm_init( x264_t *h );
void x264_cqm_delete( x264_t *h );
int  x264_cqm_parse_file( x264_t *h, const char *filename );

#endif
