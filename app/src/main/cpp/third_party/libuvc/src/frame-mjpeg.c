/*********************************************************************
* Software License Agreement (BSD License)
*
*  Copyright (C) 2014 Robert Xiao
*  All rights reserved.
*
*  Redistribution and use in source and binary forms, with or without
*  modification, are permitted provided that the following conditions
*  are met:
*
*   * Redistributions of source code must retain the above copyright
*     notice, this list of conditions and the following disclaimer.
*   * Redistributions in binary form must reproduce the above
*     copyright notice, this list of conditions and the following
*     disclaimer in the documentation and/or other materials provided
*     with the distribution.
*   * Neither the name of the author nor other contributors may be
*     used to endorse or promote products derived from this software
*     without specific prior written permission.
*
*  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
*  "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
*  LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
*  FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
*  COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
*  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
*  BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
*  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
*  CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
*  LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
*  ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
*  POSSIBILITY OF SUCH DAMAGE.
*********************************************************************/

/**
 * @defgroup frame Frame processing
 */
#include "libuvc/libuvc.h"
#include "libuvc/libuvc_internal.h"
#include <jpeglib.h>
#include <setjmp.h>

extern uvc_error_t uvc_ensure_frame_size(uvc_frame_t *frame, size_t need_bytes);

struct error_mgr {
  struct jpeg_error_mgr super;
  jmp_buf jmp;
  char diagnostic[JMSG_LENGTH_MAX];
};

static void _diagnostic_message(j_common_ptr dinfo) {
  struct error_mgr *err = (struct error_mgr *)dinfo->err;
  (*dinfo->err->format_message)(dinfo, err->diagnostic);
}

static void _error_exit(j_common_ptr dinfo) {
  struct error_mgr *myerr = (struct error_mgr *)dinfo->err;
  (*dinfo->err->output_message)(dinfo);
  longjmp(myerr->jmp, 1);
}

/* ISO/IEC 10918-1:1993(E) K.3.3. Default Huffman tables used by MJPEG UVC devices
   which don't specify a Huffman table in the JPEG stream. */
static const unsigned char dc_lumi_len[] = 
  {0, 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
static const unsigned char dc_lumi_val[] = 
  {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};

static const unsigned char dc_chromi_len[] = 
  {0, 0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
static const unsigned char dc_chromi_val[] = 
  {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};

static const unsigned char ac_lumi_len[] = 
  {0, 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d};
static const unsigned char ac_lumi_val[] = 
  {0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21,
   0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71,
   0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1,
   0xc1, 0x15, 0x52, 0xd1, 0xf0, 0x24, 0x33, 0x62, 0x72,
   0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25,
   0x26, 0x27, 0x28, 0x29, 0x2a, 0x34, 0x35, 0x36, 0x37,
   0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
   0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
   0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a,
   0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83,
   0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x92, 0x93,
   0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3,
   0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3,
   0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
   0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3,
   0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
   0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1,
   0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa};
static const unsigned char ac_chromi_len[] = 
  {0, 0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
static const unsigned char ac_chromi_val[] = 
  {0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31,
   0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71, 0x13, 0x22,
   0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1,
   0x09, 0x23, 0x33, 0x52, 0xf0, 0x15, 0x62, 0x72, 0xd1,
   0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18,
   0x19, 0x1a, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x35, 0x36,
   0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47,
   0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
   0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
   0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a,
   0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a,
   0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a,
   0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa,
   0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba,
   0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca,
   0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
   0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea,
   0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa};

#define COPY_HUFF_TABLE(dinfo,tbl,name) do { \
  if(dinfo->tbl == NULL) dinfo->tbl = jpeg_alloc_huff_table((j_common_ptr)dinfo); \
  memcpy(dinfo->tbl->bits, name##_len, sizeof(name##_len)); \
  memset(dinfo->tbl->huffval, 0, sizeof(dinfo->tbl->huffval)); \
  memcpy(dinfo->tbl->huffval, name##_val, sizeof(name##_val)); \
} while(0)

static void insert_huff_tables(j_decompress_ptr dinfo) {
  COPY_HUFF_TABLE(dinfo, dc_huff_tbl_ptrs[0], dc_lumi);
  COPY_HUFF_TABLE(dinfo, dc_huff_tbl_ptrs[1], dc_chromi);
  COPY_HUFF_TABLE(dinfo, ac_huff_tbl_ptrs[0], ac_lumi);
  COPY_HUFF_TABLE(dinfo, ac_huff_tbl_ptrs[1], ac_chromi);
}

/* Decode matching component rows directly into caller-owned output. Only
   padded edge rows and components needing resampling use libjpeg-owned scratch. */
static uvc_error_t decode_yuv_planes(uvc_frame_t *in, uvc_frame_t *out,
    unsigned int source_cw, unsigned int source_ch,
    long *warnings, char *message, size_t message_size) {
  struct jpeg_decompress_struct dinfo = {0};
  struct error_mgr jerr = {0};
  JSAMPARRAY planes[3] = {0};
  JSAMPARRAY strip[3] = {0};
  JSAMPARRAY edges[3] = {0};
  int matching[3] = {0};
  size_t strides[3] = {0};
  size_t pixels = (size_t)in->width * in->height;
  size_t cw = (in->width + 1) / 2, ch = (in->height + 1) / 2;
  dinfo.err = jpeg_std_error(&jerr.super);
  jerr.super.error_exit = _error_exit;
  jerr.super.output_message = _diagnostic_message;
  if (warnings) *warnings = 0;
  if (message && message_size) message[0] = '\0';
  if (setjmp(jerr.jmp)) goto fail_yuv;
  jpeg_create_decompress(&dinfo);
  jpeg_mem_src(&dinfo, in->data, in->data_bytes);
  jpeg_read_header(&dinfo, TRUE);
  if (dinfo.image_width != in->width || dinfo.image_height != in->height ||
      (dinfo.jpeg_color_space != JCS_YCbCr && dinfo.jpeg_color_space != JCS_GRAYSCALE) ||
      (dinfo.num_components != 1 && dinfo.num_components != 3)) {
    snprintf(jerr.diagnostic, sizeof(jerr.diagnostic),
        "JPEG geometry/color mismatch: %ux%u space=%d components=%d",
        dinfo.image_width, dinfo.image_height, dinfo.jpeg_color_space, dinfo.num_components);
    goto fail_yuv;
  }
  if (!dinfo.dc_huff_tbl_ptrs[0]) insert_huff_tables(&dinfo);
  dinfo.raw_data_out = TRUE;
  dinfo.dct_method = JDCT_IFAST;
  jpeg_start_decompress(&dinfo);
  if (source_cw || source_ch) {
    if (!source_cw || !source_ch || source_cw > in->width || source_ch > in->height ||
        dinfo.comp_info[0].downsampled_width != in->width ||
        dinfo.comp_info[0].downsampled_height != in->height ||
        (dinfo.num_components == 3 &&
         (dinfo.comp_info[1].downsampled_width != source_cw ||
          dinfo.comp_info[2].downsampled_width != source_cw ||
          dinfo.comp_info[1].downsampled_height != source_ch ||
          dinfo.comp_info[2].downsampled_height != source_ch))) {
      snprintf(jerr.diagnostic, sizeof(jerr.diagnostic), "JPEG chroma geometry mismatch");
      goto fail_yuv;
    }
    cw = source_cw; ch = source_ch;
  }
  if (uvc_ensure_frame_size(out, pixels + 2 * cw * ch) < 0) goto fail_yuv;
  for (int c = 0; c < dinfo.num_components; ++c) {
    jpeg_component_info *comp = &dinfo.comp_info[c];
    size_t dw = c == 0 ? in->width : cw, dh = c == 0 ? in->height : ch;
    JDIMENSION strip_rows = comp->v_samp_factor * DCTSIZE;
    strides[c] = comp->width_in_blocks * DCTSIZE;
    matching[c] = comp->downsampled_width == dw && comp->downsampled_height == dh;
    if (matching[c]) {
      // JPEG may write complete DCT rows. Redirect padded rows/columns to a
      // small MCU strip so tightly packed output never gets overrun.
      edges[c] = (*dinfo.mem->alloc_sarray)((j_common_ptr)&dinfo, JPOOL_IMAGE,
          strides[c], strip_rows);
      strip[c] = (JSAMPARRAY)(*dinfo.mem->alloc_small)((j_common_ptr)&dinfo,
          JPOOL_IMAGE, strip_rows * sizeof(JSAMPROW));
    } else {
      JDIMENSION rows = ((comp->height_in_blocks + comp->v_samp_factor - 1) /
          comp->v_samp_factor) * strip_rows;
      planes[c] = (*dinfo.mem->alloc_sarray)((j_common_ptr)&dinfo, JPOOL_IMAGE,
          strides[c], rows);
    }
  }
  while (dinfo.output_scanline < dinfo.output_height) {
    JDIMENSION mcu = dinfo.output_scanline / (dinfo.max_v_samp_factor * DCTSIZE);
    for (int c = 0; c < dinfo.num_components; ++c) {
      size_t dw = c == 0 ? in->width : cw, dh = c == 0 ? in->height : ch;
      JDIMENSION rows = dinfo.comp_info[c].v_samp_factor * DCTSIZE;
      if (matching[c]) {
        unsigned char *dst = (unsigned char *)out->data + (c == 0 ? 0 : pixels + (c - 1) * cw * ch);
        for (JDIMENSION row = 0; row < rows; ++row) {
          size_t y = (size_t)mcu * rows + row;
          strip[c][row] = strides[c] == dw && y < dh ? dst + y * dw : edges[c][row];
        }
      } else strip[c] = planes[c] + mcu * rows;
    }
    if (!jpeg_read_raw_data(&dinfo, strip, dinfo.max_v_samp_factor * DCTSIZE)) goto fail_yuv;
    for (int c = 0; c < dinfo.num_components; ++c) {
      size_t dw = c == 0 ? in->width : cw, dh = c == 0 ? in->height : ch;
      if (matching[c] && strides[c] != dw) {
        unsigned char *dst = (unsigned char *)out->data + (c == 0 ? 0 : pixels + (c - 1) * cw * ch);
        JDIMENSION rows = dinfo.comp_info[c].v_samp_factor * DCTSIZE;
        for (JDIMENSION row = 0; row < rows; ++row) {
          size_t y = (size_t)mcu * rows + row;
          if (y < dh) memcpy(dst + y * dw, edges[c][row], dw);
        }
      }
    }
  }
  for (int c = 0; c < 3; ++c) {
    size_t dw = c == 0 ? in->width : cw, dh = c == 0 ? in->height : ch;
    unsigned char *dst = (unsigned char *)out->data + (c == 0 ? 0 : pixels + (c - 1) * cw * ch);
    if (c >= dinfo.num_components) { memset(dst, 128, dw * dh); continue; }
    if (matching[c]) continue;
    size_t sw = dinfo.comp_info[c].downsampled_width, sh = dinfo.comp_info[c].downsampled_height;
    for (size_t y = 0; y < dh; ++y) {
      size_t y0 = y * sh / dh, y1 = (y + 1) * sh / dh;
      if (y1 <= y0) y1 = y0 + 1;
      for (size_t x = 0; x < dw; ++x) {
        size_t x0 = x * sw / dw, x1 = (x + 1) * sw / dw;
        if (x1 <= x0) x1 = x0 + 1;
        unsigned int sum = 0, count = 0;
        for (size_t yy = y0; yy < y1; ++yy)
          for (size_t xx = x0; xx < x1; ++xx) { sum += planes[c][yy][xx]; ++count; }
        dst[y * dw + x] = (sum + count / 2) / count;
      }
    }
  }
  out->width = in->width;
  out->height = in->height;
  out->data_bytes = pixels + 2 * cw * ch;
  out->frame_format = cw == (in->width + 1) / 2 && ch == (in->height + 1) / 2
      ? UVC_FRAME_FORMAT_I420 : UVC_FRAME_FORMAT_UNKNOWN;
  out->step = in->width;
  jpeg_finish_decompress(&dinfo);
  if (warnings) *warnings = jerr.super.num_warnings;
  if (message && message_size) snprintf(message, message_size, "%s", jerr.diagnostic);
  jpeg_destroy_decompress(&dinfo);
  return UVC_SUCCESS;
fail_yuv:
  if (warnings) *warnings = jerr.super.num_warnings;
  if (message && message_size) snprintf(message, message_size, "%s",
      jerr.diagnostic[0] ? jerr.diagnostic : "JPEG decode/allocation failed");
  jpeg_destroy_decompress(&dinfo);
  return UVC_ERROR_OTHER;
}

uvc_error_t uvc_mjpeg2yuv_diagnostic(uvc_frame_t *in, uvc_frame_t *out,
    unsigned int chroma_width, unsigned int chroma_height,
    long *warnings, char *message, size_t message_size) {
  if (!chroma_width || !chroma_height) return UVC_ERROR_INVALID_PARAM;
  return decode_yuv_planes(in, out, chroma_width, chroma_height, warnings, message, message_size);
}

uvc_error_t uvc_mjpeg2i420_diagnostic(uvc_frame_t *in, uvc_frame_t *out,
    long *warnings, char *message, size_t message_size) {
  return decode_yuv_planes(in, out, 0, 0, warnings, message, message_size);
}

uvc_error_t uvc_mjpeg2i420(uvc_frame_t *in, uvc_frame_t *out) {
  return uvc_mjpeg2i420_diagnostic(in, out, NULL, NULL, 0);
}

static uvc_error_t uvc_mjpeg_convert(uvc_frame_t *in, uvc_frame_t *out) {
  struct jpeg_decompress_struct dinfo;
  struct error_mgr jerr;
  size_t lines_read;
  dinfo.err = jpeg_std_error(&jerr.super);
  jerr.super.error_exit = _error_exit;

  if (setjmp(jerr.jmp)) {
    goto fail;
  }

  jpeg_create_decompress(&dinfo);
  jpeg_mem_src(&dinfo, in->data, in->data_bytes);
  jpeg_read_header(&dinfo, TRUE);

  /* The destination frame is sized from the UVC mode.  Reject malformed
     packets whose JPEG header advertises a different geometry before the
     scanline converter can write past the destination buffer. */
  if (dinfo.image_width != in->width || dinfo.image_height != in->height)
    goto fail;

  if (dinfo.dc_huff_tbl_ptrs[0] == NULL) {
    /* This frame is missing the Huffman tables: fill in the standard ones */
    insert_huff_tables(&dinfo);
  }

  if (out->frame_format == UVC_FRAME_FORMAT_RGB)
    dinfo.out_color_space = JCS_RGB;
  else if (out->frame_format == UVC_FRAME_FORMAT_BGR)
    dinfo.out_color_space = JCS_EXT_BGR;
  else if (out->frame_format == UVC_FRAME_FORMAT_GRAY8)
    dinfo.out_color_space = JCS_GRAYSCALE;
  else
    goto fail;

  dinfo.dct_method = JDCT_IFAST;

  jpeg_start_decompress(&dinfo);

  if (dinfo.output_width != in->width || dinfo.output_height != in->height ||
      dinfo.output_components != 3 || out->step < dinfo.output_width * 3)
    goto fail;

  lines_read = 0;
  while (dinfo.output_scanline < dinfo.output_height) {
    unsigned char *buffer[1] = {( unsigned char*) out->data + lines_read * out->step };
    int num_scanlines;

    num_scanlines = jpeg_read_scanlines(&dinfo, buffer, 1);
    lines_read += num_scanlines;
  }

  jpeg_finish_decompress(&dinfo);
  jpeg_destroy_decompress(&dinfo);
  return 0;

fail:
  jpeg_destroy_decompress(&dinfo);
  return UVC_ERROR_OTHER;
}


/** @brief Convert an MJPEG frame to BGR
 * @ingroup frame
 *
 * @param in MJPEG frame
 * @param out BGR frame
 */
uvc_error_t uvc_mjpeg2bgr(uvc_frame_t *in, uvc_frame_t *out) {
  if (in->frame_format != UVC_FRAME_FORMAT_MJPEG)
    return UVC_ERROR_INVALID_PARAM;

  if (uvc_ensure_frame_size(out, (size_t)in->width * in->height * 3) < 0)
    return UVC_ERROR_NO_MEM;

  out->width = in->width;
  out->height = in->height;
  out->frame_format = UVC_FRAME_FORMAT_BGR;
  out->step = in->width * 3;
  out->sequence = in->sequence;
  out->capture_time = in->capture_time;
  out->capture_time_finished = in->capture_time_finished;
  out->source = in->source;

  return uvc_mjpeg_convert(in, out);
}

/** @brief Convert an MJPEG frame to RGB
 * @ingroup frame
 *
 * @param in MJPEG frame
 * @param out RGB frame
 */
uvc_error_t uvc_mjpeg2rgb(uvc_frame_t *in, uvc_frame_t *out) {
  if (in->frame_format != UVC_FRAME_FORMAT_MJPEG)
    return UVC_ERROR_INVALID_PARAM;

  if (uvc_ensure_frame_size(out, (size_t)in->width * in->height * 3) < 0)
    return UVC_ERROR_NO_MEM;

  out->width = in->width;
  out->height = in->height;
  out->frame_format = UVC_FRAME_FORMAT_RGB;
  out->step = in->width * 3;
  out->sequence = in->sequence;
  out->capture_time = in->capture_time;
  out->capture_time_finished = in->capture_time_finished;
  out->source = in->source;

  return uvc_mjpeg_convert(in, out);
}

/** @brief Convert an MJPEG frame to GRAY8
 * @ingroup frame
 *
 * @param in MJPEG frame
 * @param out GRAY8 frame
 */
uvc_error_t uvc_mjpeg2gray(uvc_frame_t *in, uvc_frame_t *out) {
  if (in->frame_format != UVC_FRAME_FORMAT_MJPEG)
    return UVC_ERROR_INVALID_PARAM;

  if (uvc_ensure_frame_size(out, (size_t)in->width * in->height) < 0)
    return UVC_ERROR_NO_MEM;

  out->width = in->width;
  out->height = in->height;
  out->frame_format = UVC_FRAME_FORMAT_GRAY8;
  out->step = in->width;
  out->sequence = in->sequence;
  out->capture_time = in->capture_time;
  out->capture_time_finished = in->capture_time_finished;
  out->source = in->source;

  return uvc_mjpeg_convert(in, out);
}
