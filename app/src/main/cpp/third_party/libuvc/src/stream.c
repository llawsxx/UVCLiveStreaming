/*********************************************************************
* Software License Agreement (BSD License)
*
*  Copyright (C) 2010-2012 Ken Tossell
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
 * @defgroup streaming Streaming control functions
 * @brief Tools for creating, managing and consuming video streams
 */

#include "libuvc/libuvc.h"
#include "libuvc/libuvc_internal.h"
#include "errno.h"
#ifdef __ANDROID__
#include <android/log.h>
#endif

#ifdef _MSC_VER

#define DELTA_EPOCH_IN_MICROSECS  116444736000000000Ui64

// gettimeofday - get time of day for Windows;
// A gettimeofday implementation for Microsoft Windows;
// Public domain code, author "ponnada";
int gettimeofday(struct timeval *tv, struct timezone *tz)
{
    FILETIME ft;
    unsigned __int64 tmpres = 0;
    static int tzflag = 0;
    if (NULL != tv)
    {
        GetSystemTimeAsFileTime(&ft);
        tmpres |= ft.dwHighDateTime;
        tmpres <<= 32;
        tmpres |= ft.dwLowDateTime;
        tmpres /= 10;
        tmpres -= DELTA_EPOCH_IN_MICROSECS;
        tv->tv_sec = (long)(tmpres / 1000000UL);
        tv->tv_usec = (long)(tmpres % 1000000UL);
    }
    return 0;
}
#endif // _MSC_VER
uvc_frame_desc_t *uvc_find_frame_desc_stream(uvc_stream_handle_t *strmh,
    uint16_t format_id, uint16_t frame_id);
uvc_frame_desc_t *uvc_find_frame_desc(uvc_device_handle_t *devh,
    uint16_t format_id, uint16_t frame_id);
void *_uvc_user_caller(void *arg);
void _uvc_populate_frame(uvc_stream_handle_t *strmh);

static uvc_streaming_interface_t *_uvc_get_stream_if(uvc_device_handle_t *devh, int interface_idx);
static uvc_stream_handle_t *_uvc_get_stream_by_interface(uvc_device_handle_t *devh, int interface_idx);

struct format_table_entry {
  enum uvc_frame_format format;
  uint8_t abstract_fmt;
  uint8_t guid[16];
  int children_count;
  enum uvc_frame_format *children;
};

struct format_table_entry *_get_format_entry(enum uvc_frame_format format) {
  #define ABS_FMT(_fmt, _num, ...) \
    case _fmt: { \
    static enum uvc_frame_format _fmt##_children[] = __VA_ARGS__; \
    static struct format_table_entry _fmt##_entry = { \
      _fmt, 0, {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0}, _num, _fmt##_children }; \
    return &_fmt##_entry; }

  #define FMT(_fmt, ...) \
    case _fmt: { \
    static struct format_table_entry _fmt##_entry = { \
      _fmt, 0, __VA_ARGS__, 0, NULL }; \
    return &_fmt##_entry; }

  switch(format) {
    /* Define new formats here */
    ABS_FMT(UVC_FRAME_FORMAT_ANY, 2,
      {UVC_FRAME_FORMAT_UNCOMPRESSED, UVC_FRAME_FORMAT_COMPRESSED})

    ABS_FMT(UVC_FRAME_FORMAT_UNCOMPRESSED, 10,
      {UVC_FRAME_FORMAT_YUYV, UVC_FRAME_FORMAT_UYVY, UVC_FRAME_FORMAT_GRAY8,
       UVC_FRAME_FORMAT_GRAY16, UVC_FRAME_FORMAT_NV12, UVC_FRAME_FORMAT_P010,
       UVC_FRAME_FORMAT_BGR, UVC_FRAME_FORMAT_RGB,
       UVC_FRAME_FORMAT_I420, UVC_FRAME_FORMAT_NV21})
    FMT(UVC_FRAME_FORMAT_YUYV,
      {'Y',  'U',  'Y',  '2', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})
    FMT(UVC_FRAME_FORMAT_UYVY,
      {'U',  'Y',  'V',  'Y', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})
    FMT(UVC_FRAME_FORMAT_GRAY8,
      {'Y',  '8',  '0',  '0', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})
    FMT(UVC_FRAME_FORMAT_GRAY16,
      {'Y',  '1',  '6',  ' ', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})
    FMT(UVC_FRAME_FORMAT_NV12,
      {'N',  'V',  '1',  '2', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})
    FMT(UVC_FRAME_FORMAT_P010,
      {'P',  '0',  '1',  '0', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})
    FMT(UVC_FRAME_FORMAT_I420,
      {'I',  '4',  '2',  '0', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})
    FMT(UVC_FRAME_FORMAT_NV21,
      {'N',  'V',  '2',  '1', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})
    FMT(UVC_FRAME_FORMAT_BGR,
      {0x7d, 0xeb, 0x36, 0xe4, 0x4f, 0x52, 0xce, 0x11, 0x9f, 0x53, 0x00, 0x20, 0xaf, 0x0b, 0xa7, 0x70})
    FMT(UVC_FRAME_FORMAT_RGB,
        {0x7e, 0xeb, 0x36, 0xe4, 0x4f, 0x52, 0xce, 0x11, 0x9f, 0x53, 0x00, 0x20, 0xaf, 0x0b, 0xa7, 0x70})
    FMT(UVC_FRAME_FORMAT_BY8,
      {'B',  'Y',  '8',  ' ', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})
    FMT(UVC_FRAME_FORMAT_BA81,
      {'B',  'A',  '8',  '1', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})
    FMT(UVC_FRAME_FORMAT_SGRBG8,
      {'G',  'R',  'B',  'G', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})
    FMT(UVC_FRAME_FORMAT_SGBRG8,
      {'G',  'B',  'R',  'G', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})
    FMT(UVC_FRAME_FORMAT_SRGGB8,
      {'R',  'G',  'G',  'B', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})
    FMT(UVC_FRAME_FORMAT_SBGGR8,
      {'B',  'G',  'G',  'R', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})
    ABS_FMT(UVC_FRAME_FORMAT_COMPRESSED, 2,
      {UVC_FRAME_FORMAT_MJPEG, UVC_FRAME_FORMAT_H264})
    FMT(UVC_FRAME_FORMAT_MJPEG,
      {'M',  'J',  'P',  'G'})
    FMT(UVC_FRAME_FORMAT_H264,
      {'H',  '2',  '6',  '4', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})

    default:
      return NULL;
  }

  #undef ABS_FMT
  #undef FMT
}

static uint8_t _uvc_frame_format_matches_guid(enum uvc_frame_format fmt, uint8_t guid[16]) {
  struct format_table_entry *format;
  int child_idx;

  format = _get_format_entry(fmt);
  if (!format)
    return 0;

  if (!format->abstract_fmt && !memcmp(guid, format->guid, 16))
    return 1;

  for (child_idx = 0; child_idx < format->children_count; child_idx++) {
    if (_uvc_frame_format_matches_guid(format->children[child_idx], guid))
      return 1;
  }

  return 0;
}

/** Look up the frame format matching a UVC format GUID.
 * @ingroup streaming
 *
 * @param guid 16-byte format GUID, as found in a format descriptor
 * @return The matching frame format, or UVC_FRAME_FORMAT_UNKNOWN if the GUID
 * is not one libuvc knows about
 */
enum uvc_frame_format uvc_frame_format_for_guid(uint8_t guid[16]) {
  struct format_table_entry *format;
  enum uvc_frame_format fmt;

  for (fmt = 0; fmt < UVC_FRAME_FORMAT_COUNT; ++fmt) {
    format = _get_format_entry(fmt);
    if (!format || format->abstract_fmt)
      continue;
    if (!memcmp(format->guid, guid, 16))
      return format->format;
  }

  return UVC_FRAME_FORMAT_UNKNOWN;
}

/** @internal
 * Run a streaming control query
 * @param[in] devh UVC device
 * @param[in,out] ctrl Control block
 * @param[in] probe Whether this is a probe query or a commit query
 * @param[in] req Query type
 */
uvc_error_t uvc_query_stream_ctrl(
    uvc_device_handle_t *devh,
    uvc_stream_ctrl_t *ctrl,
    uint8_t probe,
    enum uvc_req_code req) {
  uint8_t buf[48];
  size_t len;
  uvc_error_t err;

  memset(buf, 0, sizeof(buf));

  if (devh->info->ctrl_if.bcdUVC < 0x0110)
    len = 26;
  else if (devh->info->ctrl_if.bcdUVC < 0x0150)
    len = 34;
  else
    len = 48;

  /* prepare for a SET transfer */
  if (req == UVC_SET_CUR) {
    SHORT_TO_SW(ctrl->bmHint, buf);
    buf[2] = ctrl->bFormatIndex;
    buf[3] = ctrl->bFrameIndex;
    INT_TO_DW(ctrl->dwFrameInterval, buf + 4);
    SHORT_TO_SW(ctrl->wKeyFrameRate, buf + 8);
    SHORT_TO_SW(ctrl->wPFrameRate, buf + 10);
    SHORT_TO_SW(ctrl->wCompQuality, buf + 12);
    SHORT_TO_SW(ctrl->wCompWindowSize, buf + 14);
    SHORT_TO_SW(ctrl->wDelay, buf + 16);
    INT_TO_DW(ctrl->dwMaxVideoFrameSize, buf + 18);
    INT_TO_DW(ctrl->dwMaxPayloadTransferSize, buf + 22);

    if (len >= 34) {
      INT_TO_DW ( ctrl->dwClockFrequency, buf + 26 );
      buf[30] = ctrl->bmFramingInfo;
      buf[31] = ctrl->bPreferredVersion;
      buf[32] = ctrl->bMinVersion;
      buf[33] = ctrl->bMaxVersion;
      /** @todo support UVC 1.1 */
    }
  }

  /* do the transfer */
  err = libusb_control_transfer(
      devh->usb_devh,
      req == UVC_SET_CUR ? 0x21 : 0xA1,
      req,
      probe ? (UVC_VS_PROBE_CONTROL << 8) : (UVC_VS_COMMIT_CONTROL << 8),
      ctrl->bInterfaceNumber,
      buf, len, 0
  );

  if (err < 0) {
    return err;
  }
  /* Even the UVC 1.0 probe block needs 26 bytes. A zero/short response must
   * not be mistaken for success with the previous control values. */
  if (err < 26) return UVC_ERROR_IO;

  /* now decode following a GET transfer */
  if (req != UVC_SET_CUR) {
    ctrl->bmHint = SW_TO_SHORT(buf);
    ctrl->bFormatIndex = buf[2];
    ctrl->bFrameIndex = buf[3];
    ctrl->dwFrameInterval = DW_TO_INT(buf + 4);
    ctrl->wKeyFrameRate = SW_TO_SHORT(buf + 8);
    ctrl->wPFrameRate = SW_TO_SHORT(buf + 10);
    ctrl->wCompQuality = SW_TO_SHORT(buf + 12);
    ctrl->wCompWindowSize = SW_TO_SHORT(buf + 14);
    ctrl->wDelay = SW_TO_SHORT(buf + 16);
    ctrl->dwMaxVideoFrameSize = DW_TO_INT(buf + 18);
    ctrl->dwMaxPayloadTransferSize = DW_TO_INT(buf + 22);

    if (len >= 34) {
      ctrl->dwClockFrequency = DW_TO_INT ( buf + 26 );
      ctrl->bmFramingInfo = buf[30];
      ctrl->bPreferredVersion = buf[31];
      ctrl->bMinVersion = buf[32];
      ctrl->bMaxVersion = buf[33];
      /** @todo support UVC 1.1 */
    }
    else
      ctrl->dwClockFrequency = devh->info->ctrl_if.dwClockFrequency;

    /* fix up block for cameras that fail to set dwMax* */
    if (ctrl->dwMaxVideoFrameSize == 0) {
      uvc_frame_desc_t *frame = uvc_find_frame_desc(devh, ctrl->bFormatIndex, ctrl->bFrameIndex);

      if (frame) {
        ctrl->dwMaxVideoFrameSize = frame->dwMaxVideoFrameBufferSize;
      }
    }
  }

  return UVC_SUCCESS;
}

/** @internal
 * Run a streaming control query
 * @param[in] devh UVC device
 * @param[in,out] ctrl Control block
 * @param[in] probe Whether this is a probe query or a commit query
 * @param[in] req Query type
 */
uvc_error_t uvc_query_still_ctrl(
  uvc_device_handle_t *devh,
  uvc_still_ctrl_t *still_ctrl,
  uint8_t probe,
  enum uvc_req_code req) {

  uint8_t buf[11];
  const size_t len = 11;
  uvc_error_t err;

  memset(buf, 0, sizeof(buf));

  if (req == UVC_SET_CUR) {
    /* prepare for a SET transfer */
    buf[0] = still_ctrl->bFormatIndex;
    buf[1] = still_ctrl->bFrameIndex;
    buf[2] = still_ctrl->bCompressionIndex;
    INT_TO_DW(still_ctrl->dwMaxVideoFrameSize, buf + 3);
    INT_TO_DW(still_ctrl->dwMaxPayloadTransferSize, buf + 7);
  }

  /* do the transfer */
  err = libusb_control_transfer(
      devh->usb_devh,
      req == UVC_SET_CUR ? 0x21 : 0xA1,
      req,
      probe ? (UVC_VS_STILL_PROBE_CONTROL << 8) : (UVC_VS_STILL_COMMIT_CONTROL << 8),
      still_ctrl->bInterfaceNumber,
      buf, len, 0
  );

  if (err <= 0) {
    return err;
  }

  /* now decode following a GET transfer */
  if (req != UVC_SET_CUR) {
    still_ctrl->bFormatIndex = buf[0];
    still_ctrl->bFrameIndex = buf[1];
    still_ctrl->bCompressionIndex = buf[2];
    still_ctrl->dwMaxVideoFrameSize = DW_TO_INT(buf + 3);
    still_ctrl->dwMaxPayloadTransferSize = DW_TO_INT(buf + 7);
  }

  return UVC_SUCCESS;
}

/** Initiate a method 2 (in stream) still capture
 * @ingroup streaming
 *
 * @param[in] devh Device handle
 * @param[in] still_ctrl Still capture control block
 */
uvc_error_t uvc_trigger_still(
    uvc_device_handle_t *devh,
    uvc_still_ctrl_t *still_ctrl) {
  uvc_stream_handle_t* stream;
  uvc_streaming_interface_t* stream_if;
  uint8_t buf;
  uvc_error_t err;

  /* Stream must be running for method 2 to work */
  stream = _uvc_get_stream_by_interface(devh, still_ctrl->bInterfaceNumber);
  if (!stream || !stream->running)
    return UVC_ERROR_NOT_SUPPORTED;

  /* Only method 2 is supported */
  stream_if = _uvc_get_stream_if(devh, still_ctrl->bInterfaceNumber);
  if(!stream_if || stream_if->bStillCaptureMethod != 2)
      return UVC_ERROR_NOT_SUPPORTED;

  /* prepare for a SET transfer */
  buf = 1;

  /* do the transfer */
  err = libusb_control_transfer(
      devh->usb_devh,
      0x21, //type set
      UVC_SET_CUR,
      (UVC_VS_STILL_IMAGE_TRIGGER_CONTROL << 8),
      still_ctrl->bInterfaceNumber,
      &buf, 1, 0);

  if (err <= 0) {
    return err;
  }

  return UVC_SUCCESS;
}

/** @brief Reconfigure stream with a new stream format.
 * @ingroup streaming
 *
 * This may be executed whether or not the stream is running.
 *
 * @param[in] strmh Stream handle
 * @param[in] ctrl Control block, processed using {uvc_probe_stream_ctrl} or
 *             {uvc_get_stream_ctrl_format_size}
 */
uvc_error_t uvc_stream_ctrl(uvc_stream_handle_t *strmh, uvc_stream_ctrl_t *ctrl) {
  uvc_error_t ret;

  if (strmh->stream_if->bInterfaceNumber != ctrl->bInterfaceNumber)
    return UVC_ERROR_INVALID_PARAM;

  /* @todo Allow the stream to be modified without restarting the stream */
  if (strmh->running)
    return UVC_ERROR_BUSY;

  ret = uvc_query_stream_ctrl(strmh->devh, ctrl, 0, UVC_SET_CUR);
  if (ret != UVC_SUCCESS)
    return ret;

  strmh->cur_ctrl = *ctrl;
  return UVC_SUCCESS;
}

/** @brief Gets current stream control block
 * @ingroup streaming
 *
 * This may be executed whether or not the stream is running.
 *
 * @param[in] strmh Stream handle
 * @param[out] ctrl Current control block
 */
uvc_error_t uvc_stream_get_current_ctrl(uvc_stream_handle_t *strmh, uvc_stream_ctrl_t *ctrl) {

    *ctrl = strmh->cur_ctrl;
    return UVC_SUCCESS;
}

/** @internal
 * @brief Find the descriptor for a specific frame configuration
 * @param stream_if Stream interface
 * @param format_id Index of format class descriptor
 * @param frame_id Index of frame descriptor
 */
static uvc_frame_desc_t *_uvc_find_frame_desc_stream_if(uvc_streaming_interface_t *stream_if,
    uint16_t format_id, uint16_t frame_id) {
 
  uvc_format_desc_t *format = NULL;
  uvc_frame_desc_t *frame = NULL;

  DL_FOREACH(stream_if->format_descs, format) {
    if (format->bFormatIndex == format_id) {
      DL_FOREACH(format->frame_descs, frame) {
        if (frame->bFrameIndex == frame_id)
          return frame;
      }
    }
  }

  return NULL;
}

uvc_frame_desc_t *uvc_find_frame_desc_stream(uvc_stream_handle_t *strmh,
    uint16_t format_id, uint16_t frame_id) {
  return _uvc_find_frame_desc_stream_if(strmh->stream_if, format_id, frame_id);
}

/** @internal
 * @brief Find the descriptor for a specific frame configuration
 * @param devh UVC device
 * @param format_id Index of format class descriptor
 * @param frame_id Index of frame descriptor
 */
uvc_frame_desc_t *uvc_find_frame_desc(uvc_device_handle_t *devh,
    uint16_t format_id, uint16_t frame_id) {
 
  uvc_streaming_interface_t *stream_if;
  uvc_frame_desc_t *frame;

  DL_FOREACH(devh->info->stream_ifs, stream_if) {
    frame = _uvc_find_frame_desc_stream_if(stream_if, format_id, frame_id);
    if (frame)
      return frame;
  }

  return NULL;
}

/** Get a negotiated streaming control block for some common parameters.
 * @ingroup streaming
 *
 * @param[in] devh Device handle
 * @param[in,out] ctrl Control block
 * @param[in] format_class Type of streaming format
 * @param[in] width Desired frame width
 * @param[in] height Desired frame height
 * @param[in] fps Frame rate, frames per second
 */
uvc_error_t uvc_get_stream_ctrl_format_size(
    uvc_device_handle_t *devh,
    uvc_stream_ctrl_t *ctrl,
    enum uvc_frame_format cf,
    int width, int height,
    int fps) {
  uvc_streaming_interface_t *stream_if;

  /* find a matching frame descriptor and interval */
  DL_FOREACH(devh->info->stream_ifs, stream_if) {
    uvc_format_desc_t *format;

    DL_FOREACH(stream_if->format_descs, format) {
      uvc_frame_desc_t *frame;

      if (!_uvc_frame_format_matches_guid(cf, format->guidFormat))
        continue;

      DL_FOREACH(format->frame_descs, frame) {
        if (frame->wWidth != width || frame->wHeight != height)
          continue;

        uint32_t *interval;

        ctrl->bInterfaceNumber = stream_if->bInterfaceNumber;
        UVC_DEBUG("claiming streaming interface %d", stream_if->bInterfaceNumber );
        uvc_claim_if(devh, ctrl->bInterfaceNumber);
        /* get the max values */
        uvc_query_stream_ctrl( devh, ctrl, 1, UVC_GET_MAX);

        if (frame->intervals) {
          for (interval = frame->intervals; *interval; ++interval) {
            // allow a fps rate of zero to mean "accept first rate available"
            if ((10000000ULL + *interval / 2) / *interval == (unsigned int) fps || fps == 0) {

              ctrl->bmHint = (1 << 0); /* don't negotiate interval */
              ctrl->bFormatIndex = format->bFormatIndex;
              ctrl->bFrameIndex = frame->bFrameIndex;
              ctrl->dwFrameInterval = *interval;

              goto found;
            }
          }
        } else {
          uint32_t interval_100ns = fps > 0 ? (10000000U + fps / 2) / fps :
              (frame->dwDefaultFrameInterval ? frame->dwDefaultFrameInterval : frame->dwMinFrameInterval);
          uint32_t interval_offset = interval_100ns - frame->dwMinFrameInterval;

          if (interval_100ns && interval_100ns >= frame->dwMinFrameInterval
              && interval_100ns <= frame->dwMaxFrameInterval
              && !(interval_offset
                   && frame->dwFrameIntervalStep
                   && (interval_offset % frame->dwFrameIntervalStep))) {

            ctrl->bmHint = (1 << 0);
            ctrl->bFormatIndex = format->bFormatIndex;
            ctrl->bFrameIndex = frame->bFrameIndex;
            ctrl->dwFrameInterval = interval_100ns;

            goto found;
          }
        }
      }
    }
  }

  return UVC_ERROR_INVALID_MODE;

found:
  return uvc_probe_stream_ctrl(devh, ctrl);
}

const uvc_frame_desc_t *uvc_get_frame_desc_for_ctrl(
    uvc_device_handle_t *devh, const uvc_stream_ctrl_t *ctrl) {
  uvc_streaming_interface_t *stream_if = _uvc_get_stream_if(devh, ctrl->bInterfaceNumber);
  return stream_if ? _uvc_find_frame_desc_stream_if(stream_if, ctrl->bFormatIndex, ctrl->bFrameIndex) : NULL;
}

/** Get a negotiated still control block for some common parameters.
 * @ingroup streaming
 *
 * @param[in] devh Device handle
 * @param[in] ctrl Control block
 * @param[in, out] still_ctrl Still capture control block
 * @param[in] width Desired frame width
 * @param[in] height Desired frame height
 */
uvc_error_t uvc_get_still_ctrl_format_size(
    uvc_device_handle_t *devh,
    uvc_stream_ctrl_t *ctrl,
    uvc_still_ctrl_t *still_ctrl,
    int width, int height) {
  uvc_streaming_interface_t *stream_if;
  uvc_still_frame_desc_t *still;
  uvc_format_desc_t *format;
  uvc_still_frame_res_t *sizePattern;

  stream_if = _uvc_get_stream_if(devh, ctrl->bInterfaceNumber);

  /* Only method 2 is supported */
  if(!stream_if || stream_if->bStillCaptureMethod != 2)
    return UVC_ERROR_NOT_SUPPORTED;

  DL_FOREACH(stream_if->format_descs, format) {

    if (ctrl->bFormatIndex != format->bFormatIndex)
      continue;

    /* get the max values */
    uvc_query_still_ctrl(devh, still_ctrl, 1, UVC_GET_MAX);

    //look for still format
    DL_FOREACH(format->still_frame_desc, still) {
      DL_FOREACH(still->imageSizePatterns, sizePattern) {

        if (sizePattern->wWidth != width || sizePattern->wHeight != height)
          continue;

        still_ctrl->bInterfaceNumber = ctrl->bInterfaceNumber;
        still_ctrl->bFormatIndex = format->bFormatIndex;
        still_ctrl->bFrameIndex = sizePattern->bResolutionIndex;
        still_ctrl->bCompressionIndex = 0; //TODO support compression index
        goto found;
      }
    }
  }

  return UVC_ERROR_INVALID_MODE;

  found:
    return uvc_probe_still_ctrl(devh, still_ctrl);
}

static int _uvc_stream_params_negotiated(
  uvc_stream_ctrl_t *required,
  uvc_stream_ctrl_t *actual) {
    return required->bFormatIndex == actual->bFormatIndex &&
    required->bFrameIndex == actual->bFrameIndex;
}

/** @internal
 * Negotiate streaming parameters with the device
 *
 * @param[in] devh UVC device
 * @param[in,out] ctrl Control block
 */
uvc_error_t uvc_probe_stream_ctrl(
    uvc_device_handle_t *devh,
    uvc_stream_ctrl_t *ctrl) {
  uvc_stream_ctrl_t required_ctrl = *ctrl;

  uvc_error_t result = uvc_query_stream_ctrl( devh, ctrl, 1, UVC_SET_CUR );
  if (result != UVC_SUCCESS) return result;
  result = uvc_query_stream_ctrl( devh, ctrl, 1, UVC_GET_CUR );
  if (result != UVC_SUCCESS) return result;

  if(!_uvc_stream_params_negotiated(&required_ctrl, ctrl)) {
    UVC_DEBUG("Unable to negotiate streaming format");
    return UVC_ERROR_INVALID_MODE;
  }

  return UVC_SUCCESS;
}

/** @internal
 * Negotiate still parameters with the device
 *
 * @param[in] devh UVC device
 * @param[in,out] still_ctrl Still capture control block
 */
uvc_error_t uvc_probe_still_ctrl(
    uvc_device_handle_t *devh,
    uvc_still_ctrl_t *still_ctrl) {

  int res = uvc_query_still_ctrl(
    devh, still_ctrl, 1, UVC_SET_CUR
  );

  if(res == UVC_SUCCESS) {
    res = uvc_query_still_ctrl(
      devh, still_ctrl, 1, UVC_GET_CUR
    );

    if(res == UVC_SUCCESS) {
      res = uvc_query_still_ctrl(
        devh, still_ctrl, 0, UVC_SET_CUR
      );
    }
  }

  return res;
}

/** @internal
 * @brief Swap the working buffer with the presented buffer and notify consumers
 */
static int64_t _uvc_diagnostic_now(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void _uvc_log_payload_record(const struct uvc_payload_diagnostic *record,
                                  const char *phase) {
#ifdef __ANDROID__
  char hex[49];
  for (unsigned i = 0; i < record->head_length; ++i)
    snprintf(hex + 2 * i, 3, "%02X", record->head[i]);
  hex[2 * record->head_length] = 0;
  __android_log_print(ANDROID_LOG_INFO, "UVCLiveStreamingUsb",
      "UVC raw %s: payload=%llu monoUs=%lld len=%zu seq=%u assembled=%zu fid=%u pts=%u head=%s "
      "markersInspected=%u soiAt=%d eoiAt=%d embeddedFrameHeaderAt=%d",
      phase, (unsigned long long)record->number, (long long)(record->time_ns / 1000),
      record->length, record->sequence, record->assembled_before,
      record->fid_before, record->pts_before, hex, record->markers_inspected,
      record->first_soi, record->first_eoi, record->embedded_frame_header);
#else
  (void)record; (void)phase;
#endif
}

static void _uvc_trace_anomaly(uvc_stream_handle_t *strmh, const char *reason);

static void _uvc_trace_payload(uvc_stream_handle_t *strmh, const uint8_t *payload,
                               size_t length) {
  struct uvc_payload_diagnostic *record =
      &strmh->diagnostic_history[strmh->diagnostic_history_next];
  record->number = strmh->diagnostic_payloads;
  record->time_ns = _uvc_diagnostic_now();
  record->length = length;
  record->assembled_before = strmh->got_bytes;
  record->sequence = strmh->seq;
  record->pts_before = strmh->pts;
  record->fid_before = strmh->fid;
  record->head_length = length < sizeof(record->head) ? length : sizeof(record->head);
  if (record->head_length) memcpy(record->head, payload, record->head_length);
  record->markers_inspected = 0;
  record->first_soi = record->first_eoi = record->embedded_frame_header = -1;
  /* U4's observed header is 12 bytes, with PTS and SCR and EOH. Inspect
   * suspicious returns only; these diagnostics never change parsing. */
  int u4_header = length >= 12 && payload[0] == 12 && (payload[1] & 0xfc) == 0x8c;
  int full_eof = u4_header && (payload[1] & UVC_STREAM_EOF) &&
      length == strmh->cur_ctrl.dwMaxPayloadTransferSize;
  if (full_eof) strmh->diagnostic_full_eof_transfers++;
  if (strmh->frame_format == UVC_FRAME_FORMAT_MJPEG && length && (!u4_header || full_eof)) {
    record->markers_inspected = 1;
    for (size_t i = u4_header ? 12 : 0; i + 1 < length; ++i) {
      if (payload[i] == 0xff && payload[i + 1] == 0xd8 && record->first_soi < 0)
        record->first_soi = (int32_t)i;
      if (payload[i] == 0xff && payload[i + 1] == 0xd9 && record->first_eoi < 0)
        record->first_eoi = (int32_t)i;
    }
    /* A complete observed UVC header followed immediately by a JPEG SOI
     * and another JPEG marker is strong evidence of a new frame inside
     * this return. The offset exposes a missing short/ZLP delimiter. */
    for (size_t i = 1; i + 16 < length; ++i) {
      if (payload[i] == 12 && (payload[i + 1] & 0xfc) == 0x8c &&
          payload[i + 12] == 0xff && payload[i + 13] == 0xd8 &&
          payload[i + 14] == 0xff && payload[i + 15] != 0 && payload[i + 15] != 0xff) {
        record->embedded_frame_header = (int32_t)i;
        strmh->diagnostic_embedded_frame_headers++;
        break;
      }
    }
  }
  strmh->diagnostic_history_next =
      (strmh->diagnostic_history_next + 1) % LIBUVC_DIAGNOSTIC_HISTORY;
  if (strmh->diagnostic_history_count < LIBUVC_DIAGNOSTIC_HISTORY)
    strmh->diagnostic_history_count++;
  if (strmh->diagnostic_post_trace) {
    _uvc_log_payload_record(record, "after");
    strmh->diagnostic_post_trace--;
  }
  if (full_eof || record->embedded_frame_header >= 0) {
    _uvc_trace_anomaly(strmh, record->embedded_frame_header >= 0
        ? "embedded-UVC-header-and-JPEG-SOI" : "full-length-EOF-return");
    /* Keep marker evidence even if another anomaly used the history dump
     * limit. Full EOF returns occur rarely on the observed device. */
    uint64_t marker_count = record->embedded_frame_header >= 0
        ? strmh->diagnostic_embedded_frame_headers : strmh->diagnostic_full_eof_transfers;
    if (marker_count <= 3 || marker_count % 100 == 0)
      _uvc_log_payload_record(record, "boundary-markers");
  }
}

static void _uvc_trace_anomaly(uvc_stream_handle_t *strmh, const char *reason) {
  int64_t now = _uvc_diagnostic_now();
  if (strmh->diagnostic_last_trace_ns && now - strmh->diagnostic_last_trace_ns < 5000000000LL)
    return;
  strmh->diagnostic_last_trace_ns = now;
#ifdef __ANDROID__
  __android_log_print(ANDROID_LOG_WARN, "UVCLiveStreamingUsb",
      "UVC boundary anomaly: reason=%s payload=%llu seq=%u assembled=%zu fid=%u pts=%u",
      reason, (unsigned long long)strmh->diagnostic_payloads, strmh->seq,
      strmh->got_bytes, strmh->fid, strmh->pts);
#else
  (void)reason;
#endif
  unsigned start = (strmh->diagnostic_history_next + LIBUVC_DIAGNOSTIC_HISTORY -
                    strmh->diagnostic_history_count) % LIBUVC_DIAGNOSTIC_HISTORY;
  for (unsigned i = 0; i < strmh->diagnostic_history_count; ++i)
    _uvc_log_payload_record(&strmh->diagnostic_history[
        (start + i) % LIBUVC_DIAGNOSTIC_HISTORY], "before");
  strmh->diagnostic_post_trace = 8;
}

static void _uvc_check_frame_soi(uvc_stream_handle_t *strmh) {
  if (strmh->frame_format != UVC_FRAME_FORMAT_MJPEG || strmh->got_bytes < 2) return;
  /* Normal frames incur only a two-byte check. Scan only abnormal prefixes,
   * matching the decoder's existing acceptance of leading bytes. */
  for (size_t i = 0; i + 1 < strmh->got_bytes; ++i)
    if (strmh->outbuf[i] == 0xff && strmh->outbuf[i + 1] == 0xd8) return;
  strmh->diagnostic_frames_without_soi++;
  _uvc_trace_anomaly(strmh, "published-frame-without-SOI");
}

void _uvc_swap_buffers(uvc_stream_handle_t *strmh) {
  uint8_t *tmp_buf;

  _uvc_check_frame_soi(strmh);

  int64_t lock_start = _uvc_diagnostic_now();
  pthread_mutex_lock(&strmh->cb_mutex);
  int64_t now = _uvc_diagnostic_now();
  int64_t wait = now - lock_start;
  if (wait > strmh->diagnostic_swap_wait_max_ns) strmh->diagnostic_swap_wait_max_ns = wait;

  if (strmh->bulk_fixed_frame_size) {
    if (strmh->got_bytes < strmh->bulk_fixed_frame_size) strmh->diagnostic_raw_short_frames++;
    if (strmh->got_bytes > strmh->bulk_fixed_frame_size) strmh->diagnostic_raw_long_frames++;
  }
  if (strmh->diagnostic_previous_frame_ns) {
    int64_t delta = now - strmh->diagnostic_previous_frame_ns;
    if (!strmh->diagnostic_frame_intervals || delta < strmh->diagnostic_frame_min_ns)
      strmh->diagnostic_frame_min_ns = delta;
    if (delta > strmh->diagnostic_frame_max_ns) strmh->diagnostic_frame_max_ns = delta;
    strmh->diagnostic_frame_ns += delta;
    strmh->diagnostic_frame_intervals++;
    if (strmh->cur_ctrl.dwFrameInterval && delta > (int64_t)strmh->cur_ctrl.dwFrameInterval * 150)
      strmh->diagnostic_long_frame_intervals++;
  }
  strmh->diagnostic_previous_frame_ns = now;
  if (!strmh->diagnostic_pts_present) strmh->diagnostic_pts_missing++;
  else if (strmh->diagnostic_previous_pts_present) {
    uint32_t delta = strmh->pts - strmh->diagnostic_previous_pts;
    if (!delta) strmh->diagnostic_pts_repeated++;
    else if (delta >= 0x80000000u) strmh->diagnostic_pts_backward++;
    else {
      if (!strmh->diagnostic_pts_samples || delta < strmh->diagnostic_pts_min)
        strmh->diagnostic_pts_min = delta;
      if (delta > strmh->diagnostic_pts_max) strmh->diagnostic_pts_max = delta;
      strmh->diagnostic_pts_ticks += delta;
      strmh->diagnostic_pts_samples++;
    }
  }
  strmh->diagnostic_previous_pts_present = strmh->diagnostic_pts_present;
  strmh->diagnostic_previous_pts = strmh->pts;

  (void)clock_gettime(CLOCK_MONOTONIC, &strmh->capture_time_finished);

  /* swap the buffers */
  tmp_buf = strmh->holdbuf;
  strmh->hold_bytes = strmh->got_bytes;
  strmh->holdbuf = strmh->outbuf;
  strmh->outbuf = tmp_buf;
  strmh->hold_last_scr = strmh->last_scr;
  strmh->hold_pts = strmh->pts;
  strmh->hold_seq = strmh->seq;
  
  /* swap metadata buffer */
  tmp_buf = strmh->meta_holdbuf;
  strmh->meta_holdbuf = strmh->meta_outbuf;
  strmh->meta_outbuf = tmp_buf;
  strmh->meta_hold_bytes = strmh->meta_got_bytes;

  pthread_cond_broadcast(&strmh->cb_cond);
  pthread_mutex_unlock(&strmh->cb_mutex);

  strmh->seq++;
  strmh->got_bytes = 0;
  strmh->meta_got_bytes = 0;
  strmh->last_scr = 0;
  strmh->pts = 0;
  strmh->diagnostic_pts_present = 0;
}

/** @internal
 * @brief Process a payload transfer
 * 
 * Processes stream, places frames into buffer, signals listeners
 * (such as user callback thread and any polling thread) on new frame
 *
 * @param payload Contents of the payload transfer, either a packet (isochronous) or a full
 * transfer (bulk mode)
 * @param payload_len Length of the payload transfer
 */
static void _uvc_process_payload_data(uvc_stream_handle_t *strmh, uint8_t *payload, size_t payload_len) {
  size_t header_len;
  uint8_t header_info;
  size_t data_len;

  /* magic numbers for identifying header packets from some iSight cameras */
  static uint8_t isight_tag[] = {
    0x11, 0x22, 0x33, 0x44,
    0xde, 0xad, 0xbe, 0xef, 0xde, 0xad, 0xfa, 0xce
  };

  /* ignore empty payload transfers */
  if (payload_len == 0) return;

  /* Certain iSight cameras have strange behavior: They send header
   * information in a packet with no image data, and then the following
   * packets have only image data, with no more headers until the next frame.
   *
   * The iSight header: len(1), flags(1 or 2), 0x11223344(4),
   * 0xdeadbeefdeadface(8), ??(16)
   */

  if (strmh->devh->is_isight &&
      (payload_len < 14 || memcmp(isight_tag, payload + 2, sizeof(isight_tag))) &&
      (payload_len < 15 || memcmp(isight_tag, payload + 3, sizeof(isight_tag)))) {
    /* The payload transfer doesn't have any iSight magic, so it's all image data */
    header_len = 0;
    data_len = payload_len;
  } else {
    header_len = payload[0];

    if (header_len < 2 || header_len > payload_len) {
      strmh->diagnostic_bad_headers++;
      _uvc_trace_anomaly(strmh, "invalid-header-length");
      UVC_DEBUG("bogus packet: actual_len=%zd, header_len=%zd\n", payload_len, header_len);
      return;
    }

    if (strmh->devh->is_isight)
      data_len = 0;
    else
      data_len = payload_len - header_len;
  }

  if (header_len < 2) {
    header_info = 0;
  } else {
    /** @todo we should be checking the end-of-header bit */
    size_t variable_offset = 2;

    header_info = payload[1];
    /* Observe weak headers without rejecting nonconforming devices yet. */
    if (!(header_info & UVC_STREAM_EOH)) {
      strmh->diagnostic_missing_eoh++;
      _uvc_trace_anomaly(strmh, "missing-EOH");
    }
    if (header_info & UVC_STREAM_RES) {
      strmh->diagnostic_reserved_flags++;
      _uvc_trace_anomaly(strmh, "reserved-header-flag");
    }

    /* Validate optional fields before reading them or acting on FID. A
     * malformed/truncated bulk header must not create a false frame boundary. */
    size_t required_header = 2 + ((header_info & UVC_STREAM_PTS) ? 4 : 0) +
        ((header_info & UVC_STREAM_SCR) ? 6 : 0);
    if (header_len < required_header) {
      strmh->diagnostic_bad_headers++;
      _uvc_trace_anomaly(strmh, "truncated-PTS-SCR");
      return;
    }

    if (header_info & UVC_STREAM_ERR) {
      strmh->diagnostic_error_payloads++;
      _uvc_trace_anomaly(strmh, "parsed-ERR-flag");
      UVC_DEBUG("bad packet: error bit set");
      return;
    }

    if (strmh->fid != (header_info & UVC_STREAM_FID) && strmh->got_bytes != 0) {
      strmh->diagnostic_fid_boundaries++;
      _uvc_trace_anomaly(strmh, "FID-changed-without-EOF");
      /* The frame ID bit was flipped, but we have image data sitting
         around from prior transfers. This means the camera didn't send
         an EOF for the last transfer of the previous frame. */
      _uvc_swap_buffers(strmh);
    }

    strmh->fid = (uint8_t) (header_info & UVC_STREAM_FID);

    if (header_info & UVC_STREAM_PTS) {
      strmh->pts = DW_TO_INT(payload + variable_offset);
      strmh->diagnostic_pts_present = 1;
      variable_offset += 4;
    }

    if (header_info & UVC_STREAM_SCR) {
      /** @todo read the SOF token counter */
      strmh->last_scr = DW_TO_INT(payload + variable_offset);
      variable_offset += 6;
    }

    if (header_len > variable_offset) {
        // Metadata is attached to header
        size_t meta_len = header_len - variable_offset;
        if (strmh->meta_got_bytes + meta_len > LIBUVC_XFER_META_BUF_SIZE)
          meta_len = LIBUVC_XFER_META_BUF_SIZE - strmh->meta_got_bytes; /* Avoid overflow. */
        memcpy(strmh->meta_outbuf + strmh->meta_got_bytes, payload + variable_offset, meta_len);
        strmh->meta_got_bytes += meta_len;
    }
  }

  if (data_len > 0) {
    if (data_len > strmh->cur_ctrl.dwMaxVideoFrameSize - strmh->got_bytes) {
      strmh->diagnostic_truncated_bytes +=
          data_len - (strmh->cur_ctrl.dwMaxVideoFrameSize - strmh->got_bytes);
      data_len = strmh->cur_ctrl.dwMaxVideoFrameSize - strmh->got_bytes; /* Avoid overflow. */
    }
    memcpy(strmh->outbuf + strmh->got_bytes, payload + header_len, data_len);
    strmh->got_bytes += data_len;
  }

  /* EOF can be carried by a header-only payload. Publish the accumulated
   * frame even when this particular transfer contains no image bytes. */
  if (strmh->got_bytes > 0) {
    if (header_info & UVC_STREAM_EOF || strmh->got_bytes == strmh->cur_ctrl.dwMaxVideoFrameSize) {
      if (!(header_info & UVC_STREAM_EOF)) strmh->diagnostic_size_boundaries++;
      /* The EOF bit is set, so publish the complete frame */
      _uvc_swap_buffers(strmh);
    }
  }
}

static void _uvc_record_payload(uvc_stream_handle_t *strmh, const uint8_t *payload, size_t length) {
  if (length) strmh->diagnostic_payloads++;
  _uvc_trace_payload(strmh, payload, length);
}

void _uvc_process_payload(uvc_stream_handle_t *strmh, uint8_t *payload, size_t length) {
  _uvc_record_payload(strmh, payload, length);
  _uvc_process_payload_data(strmh, payload, length);
}

/* Restricted to the observed MJPEG failure: an EOF payload ends with EOI
 * on a physical USB packet boundary, immediately followed by a new frame's
 * 12-byte PTS/SCR header, toggled FID, changed PTS and JPEG beginning. */
static size_t _uvc_mjpeg_bulk_boundary(uvc_stream_handle_t *strmh,
                                      const uint8_t *data, size_t length) {
  size_t packet_size = strmh->bulk_packet_size;
  if (strmh->frame_format != UVC_FRAME_FORMAT_MJPEG || strmh->devh->is_isight ||
      packet_size < 16 || packet_size > 1024 || length < 12 ||
      length > strmh->cur_ctrl.dwMaxPayloadTransferSize ||
      data[0] != 12 || (data[1] & 0xfc) != 0x8c || !(data[1] & UVC_STREAM_EOF))
    return SIZE_MAX;
  for (size_t offset = packet_size; offset + 16 < length; offset += packet_size) {
    const uint8_t *next = data + offset;
    if (data[offset - 2] != 0xff || data[offset - 1] != 0xd9 ||
        next[0] != 12 || (next[1] & 0xfc) != 0x8c ||
        !((next[1] ^ data[1]) & UVC_STREAM_FID) ||
        DW_TO_INT(next + 2) == DW_TO_INT(data + 2) ||
        next[12] != 0xff || next[13] != 0xd8 || next[14] != 0xff)
      continue;
    uint8_t marker = next[15];
    if (marker != 0xdb && marker != 0xc0 && marker != 0xc4 && marker != 0xdd &&
        marker != 0xfe && !(marker >= 0xe0 && marker <= 0xef))
      continue;
    return offset;
  }
  return SIZE_MAX;
}

/* Only tightly packed fixed-size formats used by the current app. A larger
 * allocation limit is not a frame length; derive the actual size from the
 * format and geometry and refuse incompatible advertised row strides. */
size_t _uvc_bulk_fixed_frame_size(enum uvc_frame_format format, size_t width, size_t height,
                                  size_t row_stride, size_t allocation_size) {
  if (!width || !height || width > SIZE_MAX / height) return 0;
  size_t pixels = width * height, multiplier = 0, divisor = 1, row_bytes = 0;
  switch (format) {
  case UVC_FRAME_FORMAT_YUYV:
  case UVC_FRAME_FORMAT_UYVY:
    if (width & 1) return 0;
    multiplier = 2; row_bytes = width * 2; break;
  case UVC_FRAME_FORMAT_RGB:
  case UVC_FRAME_FORMAT_BGR:
    multiplier = 3; row_bytes = width * 3; break;
  case UVC_FRAME_FORMAT_GRAY8:
    multiplier = 1; row_bytes = width; break;
  case UVC_FRAME_FORMAT_GRAY16:
    multiplier = 2; row_bytes = width * 2; break;
  case UVC_FRAME_FORMAT_NV12:
  case UVC_FRAME_FORMAT_NV21:
  case UVC_FRAME_FORMAT_I420:
    if ((width | height) & 1) return 0;
    multiplier = 3; divisor = 2; row_bytes = width; break;
  case UVC_FRAME_FORMAT_P010:
    if ((width | height) & 1) return 0;
    multiplier = 3; row_bytes = width * 2; break;
  default:
    return 0;
  }
  if (pixels > SIZE_MAX / multiplier || (row_stride && row_stride != row_bytes)) return 0;
  size_t bytes = pixels * multiplier / divisor;
  return bytes <= allocation_size ? bytes : 0;
}

static int _uvc_bulk_pts_header(const uint8_t *data, size_t length) {
  if (length < 6) return 0;
  uint8_t flags = data[1];
  if ((flags & (UVC_STREAM_EOH | UVC_STREAM_PTS | UVC_STREAM_ERR | UVC_STREAM_RES)) !=
      (UVC_STREAM_EOH | UVC_STREAM_PTS)) return 0;
  size_t header = 6 + ((flags & UVC_STREAM_SCR) ? 6 : 0);
  return data[0] == header && length >= header;
}

static size_t _uvc_raw_bulk_boundary(uvc_stream_handle_t *strmh,
                                    const uint8_t *data, size_t length) {
  size_t frame_size = strmh->bulk_fixed_frame_size, packet_size = strmh->bulk_packet_size;
  if (!frame_size || strmh->devh->is_isight || packet_size < 16 || packet_size > 1024 ||
      length > strmh->cur_ctrl.dwMaxPayloadTransferSize ||
      !_uvc_bulk_pts_header(data, length) || !(data[1] & UVC_STREAM_EOF) ||
      strmh->got_bytes > frame_size)
    return SIZE_MAX;
  /* Data already assembled must belong to this same EOF frame. A missing
   * earlier payload makes the exact-size check fail instead of guessing. */
  if (strmh->got_bytes && (strmh->fid != (data[1] & UVC_STREAM_FID) ||
                          strmh->pts != DW_TO_INT(data + 2))) return SIZE_MAX;
  size_t remaining = frame_size - strmh->got_bytes;
  if (remaining > length - data[0]) return SIZE_MAX;
  size_t offset = remaining + data[0];
  if (offset < packet_size || offset % packet_size || offset >= length ||
      !_uvc_bulk_pts_header(data + offset, length - offset)) return SIZE_MAX;
  const uint8_t *next = data + offset;
  if (!((next[1] ^ data[1]) & UVC_STREAM_FID) ||
      DW_TO_INT(next + 2) == DW_TO_INT(data + 2)) return SIZE_MAX;
  return offset;
}

static size_t _uvc_bulk_frame_boundary(uvc_stream_handle_t *strmh,
                                      const uint8_t *data, size_t length) {
  return strmh->frame_format == UVC_FRAME_FORMAT_MJPEG
      ? _uvc_mjpeg_bulk_boundary(strmh, data, length)
      : _uvc_raw_bulk_boundary(strmh, data, length);
}

static void _uvc_note_bulk_repair(uvc_stream_handle_t *strmh, size_t length, size_t split) {
  strmh->diagnostic_bulk_repairs++;
  if (strmh->frame_format != UVC_FRAME_FORMAT_MJPEG) strmh->diagnostic_bulk_raw_repairs++;
#ifdef __ANDROID__
  uint64_t count = strmh->diagnostic_bulk_repairs;
  if (count <= 3 || count % 100 == 0)
    __android_log_print(ANDROID_LOG_INFO, "UVCLiveStreamingUsb",
        "UVC bulk boundary repaired: count=%llu bytes=%zu splitAt=%zu packetSize=%zu seq=%u "
        "format=%d fixedFrameBytes=%zu",
        (unsigned long long)count, length, split, strmh->bulk_packet_size, strmh->seq,
        strmh->frame_format, strmh->bulk_fixed_frame_size);
#else
  (void)length; (void)split;
#endif
}

void _uvc_process_bulk_payload(uvc_stream_handle_t *strmh, uint8_t *payload,
                               size_t length, int short_transfer) {
  _uvc_record_payload(strmh, payload, length); /* Record actual returns, not reconstructed packets. */
  size_t maximum = strmh->cur_ctrl.dwMaxPayloadTransferSize;
  if (!strmh->bulk_realign_active) {
    size_t split = _uvc_bulk_frame_boundary(strmh, payload, length);
    if (split == SIZE_MAX) {
      _uvc_process_payload_data(strmh, payload, length);
      return;
    }
    /* Allocation is needed only once an actual coalesced boundary is found.
     * Bound the additional storage independently of the frame buffer. */
    if (!maximum || maximum > 1024 * 1024) {
      _uvc_process_payload_data(strmh, payload, length);
      return;
    }
    if (strmh->bulk_pending_capacity < maximum) {
      uint8_t *buf = realloc(strmh->bulk_pending_buf, maximum);
      if (!buf) {
        _uvc_trace_anomaly(strmh, "bulk-repair-buffer-unavailable");
        _uvc_process_payload_data(strmh, payload, length);
        return;
      }
      strmh->bulk_pending_buf = buf;
      strmh->bulk_pending_capacity = maximum;
    }
    _uvc_note_bulk_repair(strmh, length, split);
    _uvc_process_payload_data(strmh, payload, split);
    strmh->bulk_realign_active = 1;
    strmh->bulk_pending_bytes = 0;
    payload += split;
    length -= split;
  }

  /* Following returns begin in the middle of a payload. Preserve every byte
   * until a complete negotiated-size payload or a short/ZLP end is available. */
  while (length) {
    size_t take = maximum - strmh->bulk_pending_bytes;
    if (take > length) take = length;
    memcpy(strmh->bulk_pending_buf + strmh->bulk_pending_bytes, payload, take);
    strmh->bulk_pending_bytes += take;
    payload += take;
    length -= take;
    if (strmh->bulk_pending_bytes == maximum) {
      size_t split = _uvc_bulk_frame_boundary(strmh, strmh->bulk_pending_buf, maximum);
      size_t consume = split == SIZE_MAX ? maximum : split;
      if (split != SIZE_MAX) _uvc_note_bulk_repair(strmh, maximum, split);
      _uvc_process_payload_data(strmh, strmh->bulk_pending_buf, consume);
      strmh->bulk_pending_bytes -= consume;
      if (strmh->bulk_pending_bytes)
        memmove(strmh->bulk_pending_buf, strmh->bulk_pending_buf + consume,
                strmh->bulk_pending_bytes);
    }
  }
  if (short_transfer) {
    /* A short packet (including a separate zero-length return) restores
     * alignment. A small next frame may itself be coalesced into this tail. */
    size_t offset = 0;
    while (offset < strmh->bulk_pending_bytes) {
      size_t left = strmh->bulk_pending_bytes - offset;
      size_t split = _uvc_bulk_frame_boundary(strmh, strmh->bulk_pending_buf + offset, left);
      size_t consume = split == SIZE_MAX ? left : split;
      if (split != SIZE_MAX) _uvc_note_bulk_repair(strmh, left, split);
      _uvc_process_payload_data(strmh, strmh->bulk_pending_buf + offset, consume);
      offset += consume;
    }
    strmh->bulk_pending_bytes = 0;
    strmh->bulk_realign_active = 0;
  }
}

/** @internal
 * @brief Stream transfer callback
 *
 * Processes stream, places frames into buffer, signals listeners
 * (such as user callback thread and any polling thread) on new frame
 *
 * @param transfer Active transfer
 */
static void _uvc_log_receive_diagnostics(uvc_stream_handle_t *strmh) {
#ifdef __ANDROID__
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  int64_t now = (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
  if (!strmh->diagnostic_last_log_ns) {
    strmh->diagnostic_last_log_ns = now;
  } else if (now - strmh->diagnostic_last_log_ns >= 5000000000LL) {
    __android_log_print(ANDROID_LOG_INFO, "UVCLiveStreamingUsb",
        "UVC assembly totals: frames=%u payloads=%llu badHeaders=%llu errorPayloads=%llu "
        "fidWithoutEOF=%llu sizeWithoutEOF=%llu transferErrors=%llu "
        "missingEOH=%llu reservedFlags=%llu frameNoSOI=%llu submitErrors=%llu "
        "emptyTransfers=%llu shortTransfers=%llu fullTransfers=%llu callbackMaxUs=%lld "
        "fullEOF=%llu embeddedFrameHeaders=%llu bulkRepairs=%llu bulkPending=%zu bulkRawRepairs=%llu",
        strmh->seq - 1, (unsigned long long)strmh->diagnostic_payloads,
        (unsigned long long)strmh->diagnostic_bad_headers,
        (unsigned long long)strmh->diagnostic_error_payloads,
        (unsigned long long)strmh->diagnostic_fid_boundaries,
        (unsigned long long)strmh->diagnostic_size_boundaries,
        (unsigned long long)strmh->diagnostic_transfer_errors,
        (unsigned long long)strmh->diagnostic_missing_eoh,
        (unsigned long long)strmh->diagnostic_reserved_flags,
        (unsigned long long)strmh->diagnostic_frames_without_soi,
        (unsigned long long)strmh->diagnostic_submit_errors,
        (unsigned long long)strmh->diagnostic_empty_transfers,
        (unsigned long long)strmh->diagnostic_short_transfers,
        (unsigned long long)strmh->diagnostic_full_transfers,
        (long long)(strmh->diagnostic_callback_max_ns / 1000),
        (unsigned long long)strmh->diagnostic_full_eof_transfers,
        (unsigned long long)strmh->diagnostic_embedded_frame_headers,
        (unsigned long long)strmh->diagnostic_bulk_repairs, strmh->bulk_pending_bytes,
        (unsigned long long)strmh->diagnostic_bulk_raw_repairs);
    __android_log_print(ANDROID_LOG_INFO, "UVCLiveStreamingUsb",
        "UVC receive timing: rawShort=%llu rawLong=%llu truncatedBytes=%llu "
        "frameSamples=%llu frameMs[min/avg/max]=%.3f/%.3f/%.3f longFrames=%llu "
        "ptsSamples=%llu ptsTicks[min/avg/max]=%u/%.3f/%u clockHz=%u "
        "ptsMissing=%llu ptsRepeated=%llu ptsBackward=%llu "
        "inFrameGapMaxUs=%lld inFrameGaps1ms=%llu inFrameGaps3ms=%llu swapWaitMaxUs=%lld receiveMBps=%.3f",
        (unsigned long long)strmh->diagnostic_raw_short_frames,
        (unsigned long long)strmh->diagnostic_raw_long_frames,
        (unsigned long long)strmh->diagnostic_truncated_bytes,
        (unsigned long long)strmh->diagnostic_frame_intervals,
        strmh->diagnostic_frame_min_ns / 1000000.0,
        strmh->diagnostic_frame_intervals ? strmh->diagnostic_frame_ns / 1000000.0 /
            strmh->diagnostic_frame_intervals : 0.0,
        strmh->diagnostic_frame_max_ns / 1000000.0,
        (unsigned long long)strmh->diagnostic_long_frame_intervals,
        (unsigned long long)strmh->diagnostic_pts_samples, strmh->diagnostic_pts_min,
        strmh->diagnostic_pts_samples ? (double)strmh->diagnostic_pts_ticks /
            strmh->diagnostic_pts_samples : 0.0, strmh->diagnostic_pts_max,
        strmh->cur_ctrl.dwClockFrequency,
        (unsigned long long)strmh->diagnostic_pts_missing,
        (unsigned long long)strmh->diagnostic_pts_repeated,
        (unsigned long long)strmh->diagnostic_pts_backward,
        (long long)(strmh->diagnostic_in_frame_gap_max_ns / 1000),
        (unsigned long long)strmh->diagnostic_in_frame_gaps_1ms,
        (unsigned long long)strmh->diagnostic_in_frame_gaps_3ms,
        (long long)(strmh->diagnostic_swap_wait_max_ns / 1000),
        strmh->diagnostic_transfer_bytes * 1000.0 / (now - strmh->diagnostic_last_log_ns));
    strmh->diagnostic_frame_intervals = strmh->diagnostic_long_frame_intervals = 0;
    strmh->diagnostic_frame_ns = strmh->diagnostic_frame_min_ns = strmh->diagnostic_frame_max_ns = 0;
    strmh->diagnostic_pts_samples = strmh->diagnostic_pts_ticks = 0;
    strmh->diagnostic_pts_min = strmh->diagnostic_pts_max = 0;
    strmh->diagnostic_in_frame_gap_max_ns = strmh->diagnostic_swap_wait_max_ns = 0;
    strmh->diagnostic_in_frame_gaps_1ms = strmh->diagnostic_in_frame_gaps_3ms = 0;
    strmh->diagnostic_transfer_bytes = 0;
    strmh->diagnostic_callback_max_ns = 0;
    strmh->diagnostic_last_log_ns = now;
  }
#else
  (void)strmh;
#endif
}

void LIBUSB_CALL _uvc_stream_callback(struct libusb_transfer *transfer) {
  uvc_stream_handle_t *strmh = transfer->user_data;
  int64_t callback_start = _uvc_diagnostic_now();
  strmh->diagnostic_transfer_callbacks++;
#ifdef __ANDROID__
  if (strmh->diagnostic_transfer_callbacks == 1 ||
      (transfer->status != LIBUSB_TRANSFER_COMPLETED &&
       transfer->status != LIBUSB_TRANSFER_CANCELLED &&
       strmh->diagnostic_transfer_errors < 4)) {
    __android_log_print(ANDROID_LOG_INFO, "UVCLiveStreamingUsb",
        "UVC transfer: interface=%u endpoint=0x%02x status=%d actual=%d requested=%d running=%u",
        strmh->stream_if->bInterfaceNumber, transfer->endpoint, transfer->status,
        transfer->actual_length, transfer->length, strmh->running);
  }
#endif
  /* Restrict gap measurements to an unfinished frame: time between frames
   * can simply be the source cadence. Gaps still include USB/device waits,
   * so a large gap alone must not be described as host queue starvation. */
  if (strmh->got_bytes && strmh->diagnostic_previous_callback_end_ns) {
    int64_t gap = callback_start - strmh->diagnostic_previous_callback_end_ns;
    if (gap > strmh->diagnostic_in_frame_gap_max_ns) strmh->diagnostic_in_frame_gap_max_ns = gap;
    if (gap > 1000000) strmh->diagnostic_in_frame_gaps_1ms++;
    if (gap > 3000000) strmh->diagnostic_in_frame_gaps_3ms++;
  }
  if (transfer->status != LIBUSB_TRANSFER_COMPLETED && transfer->status != LIBUSB_TRANSFER_CANCELLED)
    strmh->diagnostic_transfer_errors++;
  if (transfer->status != LIBUSB_TRANSFER_COMPLETED) {
    strmh->bulk_pending_bytes = 0;
    strmh->bulk_realign_active = 0;
  }
  _uvc_log_receive_diagnostics(strmh);

  int resubmit = 1;

  switch (transfer->status) {
  case LIBUSB_TRANSFER_COMPLETED: {
    uint64_t received = 0;
    if (transfer->type == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS) {
      /* actual_length is not defined for ISO transfers; use packet lengths. */
      for (int i = 0; i < transfer->num_iso_packets; ++i)
        received += transfer->iso_packet_desc[i].actual_length;
    } else {
      received = transfer->actual_length;
    }
    strmh->diagnostic_transfer_bytes += received;
    __atomic_fetch_add(&strmh->received_video_bytes, received, __ATOMIC_RELAXED);
    if (transfer->type != LIBUSB_TRANSFER_TYPE_ISOCHRONOUS) {
      if (!transfer->actual_length) strmh->diagnostic_empty_transfers++;
      else if (transfer->actual_length < transfer->length) strmh->diagnostic_short_transfers++;
      else strmh->diagnostic_full_transfers++;
      _uvc_process_bulk_payload(strmh, transfer->buffer, transfer->actual_length,
          transfer->actual_length < transfer->length);
    } else {
      /* This is an isochronous mode transfer, so each packet has a payload transfer */
      int packet_id;

      for (packet_id = 0; packet_id < transfer->num_iso_packets; ++packet_id) {
        uint8_t *pktbuf;
        struct libusb_iso_packet_descriptor *pkt;

        pkt = transfer->iso_packet_desc + packet_id;

        if (pkt->status != 0) {
          UVC_DEBUG("bad packet (isochronous transfer); pkt_id=%d status: %s(%d), actual_length=%d", packet_id, libusb_error_name(pkt->status), pkt->status, pkt->actual_length);
          continue;
        }

        pktbuf = libusb_get_iso_packet_buffer_simple(transfer, packet_id);

        _uvc_process_payload(strmh, pktbuf, pkt->actual_length);
      }
    }
    break;
  }
  case LIBUSB_TRANSFER_CANCELLED:
  case LIBUSB_TRANSFER_ERROR:
  case LIBUSB_TRANSFER_NO_DEVICE: {
    int i;
    UVC_DEBUG("not retrying transfer, status = %d", transfer->status);
    pthread_mutex_lock(&strmh->cb_mutex);

    /* Mark transfer as deleted. */
    for(i=0; i < LIBUVC_NUM_TRANSFER_BUFS; i++) {
      if(strmh->transfers[i] == transfer) {
        UVC_DEBUG("Freeing transfer %d (%p)", i, transfer);
        free(transfer->buffer);
        libusb_free_transfer(transfer);
        strmh->transfers[i] = NULL;
        strmh->transfer_bufs[i] = NULL;
        break;
      }
    }
    if(i == LIBUVC_NUM_TRANSFER_BUFS ) {
      UVC_DEBUG("transfer %p not found; not freeing!", transfer);
    }

    resubmit = 0;

    pthread_cond_broadcast(&strmh->cb_cond);
    pthread_mutex_unlock(&strmh->cb_mutex);

    break;
  }
  case LIBUSB_TRANSFER_TIMED_OUT:
  case LIBUSB_TRANSFER_STALL:
  case LIBUSB_TRANSFER_OVERFLOW:
    UVC_DEBUG("retrying transfer, status = %d", transfer->status);
    break;
  }
  
  if ( resubmit ) {
    if ( strmh->running ) {
      int libusbRet = libusb_submit_transfer(transfer);
      if (libusbRet < 0)
      {
        strmh->diagnostic_submit_errors++;
        _uvc_trace_anomaly(strmh, "resubmit-failed");
        int i;
        pthread_mutex_lock(&strmh->cb_mutex);

        /* Mark transfer as deleted. */
        for (i = 0; i < LIBUVC_NUM_TRANSFER_BUFS; i++) {
          if (strmh->transfers[i] == transfer) {
            UVC_DEBUG("Freeing failed transfer %d (%p)", i, transfer);
            free(transfer->buffer);
            libusb_free_transfer(transfer);
            strmh->transfers[i] = NULL;
            strmh->transfer_bufs[i] = NULL;
            break;
          }
        }
        if (i == LIBUVC_NUM_TRANSFER_BUFS) {
          UVC_DEBUG("failed transfer %p not found; not freeing!", transfer);
        }

        pthread_cond_broadcast(&strmh->cb_cond);
        pthread_mutex_unlock(&strmh->cb_mutex);
      }
    } else {
      int i;
      pthread_mutex_lock(&strmh->cb_mutex);

      /* Mark transfer as deleted. */
      for(i=0; i < LIBUVC_NUM_TRANSFER_BUFS; i++) {
        if(strmh->transfers[i] == transfer) {
          UVC_DEBUG("Freeing orphan transfer %d (%p)", i, transfer);
          free(transfer->buffer);
          libusb_free_transfer(transfer);
          strmh->transfers[i] = NULL;
          strmh->transfer_bufs[i] = NULL;
          break;
        }
      }
      if(i == LIBUVC_NUM_TRANSFER_BUFS ) {
        UVC_DEBUG("orphan transfer %p not found; not freeing!", transfer);
      }

      pthread_cond_broadcast(&strmh->cb_cond);
      pthread_mutex_unlock(&strmh->cb_mutex);
    }
  }
  strmh->diagnostic_previous_callback_end_ns = _uvc_diagnostic_now();
  int64_t callback_duration = strmh->diagnostic_previous_callback_end_ns - callback_start;
  if (callback_duration > strmh->diagnostic_callback_max_ns)
    strmh->diagnostic_callback_max_ns = callback_duration;
}

uint64_t uvc_get_received_video_bytes(uvc_device_handle_t *devh) {
  uint64_t bytes = 0;
  uvc_stream_handle_t *strmh;
  DL_FOREACH(devh->streams, strmh) {
    bytes += __atomic_load_n(&strmh->received_video_bytes, __ATOMIC_RELAXED);
  }
  return bytes;
}

/** Begin streaming video from the camera into the callback function.
 * @ingroup streaming
 *
 * @param devh UVC device
 * @param ctrl Control block, processed using {uvc_probe_stream_ctrl} or
 *             {uvc_get_stream_ctrl_format_size}
 * @param cb   User callback function. See {uvc_frame_callback_t} for restrictions.
 * @param flags Stream setup flags, currently undefined. Set this to zero. The lower bit
 * is reserved for backward compatibility.
 */
uvc_error_t uvc_start_streaming(
    uvc_device_handle_t *devh,
    uvc_stream_ctrl_t *ctrl,
    uvc_frame_callback_t *cb,
    void *user_ptr,
    uint8_t flags
) {
  uvc_error_t ret;
  uvc_stream_handle_t *strmh;

  ret = uvc_stream_open_ctrl(devh, &strmh, ctrl);
  if (ret != UVC_SUCCESS)
    return ret;

  ret = uvc_stream_start(strmh, cb, user_ptr, flags);
  if (ret != UVC_SUCCESS) {
    uvc_stream_close(strmh);
    return ret;
  }

  return UVC_SUCCESS;
}

/** Begin streaming video from the camera into the callback function.
 * @ingroup streaming
 *
 * @deprecated The stream type (bulk vs. isochronous) will be determined by the
 * type of interface associated with the uvc_stream_ctrl_t parameter, regardless
 * of whether the caller requests isochronous streaming. Please switch to
 * uvc_start_streaming().
 *
 * @param devh UVC device
 * @param ctrl Control block, processed using {uvc_probe_stream_ctrl} or
 *             {uvc_get_stream_ctrl_format_size}
 * @param cb   User callback function. See {uvc_frame_callback_t} for restrictions.
 */
uvc_error_t uvc_start_iso_streaming(
    uvc_device_handle_t *devh,
    uvc_stream_ctrl_t *ctrl,
    uvc_frame_callback_t *cb,
    void *user_ptr
) {
  return uvc_start_streaming(devh, ctrl, cb, user_ptr, 0);
}

static uvc_stream_handle_t *_uvc_get_stream_by_interface(uvc_device_handle_t *devh, int interface_idx) {
  uvc_stream_handle_t *strmh;

  DL_FOREACH(devh->streams, strmh) {
    if (strmh->stream_if->bInterfaceNumber == interface_idx)
      return strmh;
  }

  return NULL;
}

static uvc_streaming_interface_t *_uvc_get_stream_if(uvc_device_handle_t *devh, int interface_idx) {
  uvc_streaming_interface_t *stream_if;

  DL_FOREACH(devh->info->stream_ifs, stream_if) {
    if (stream_if->bInterfaceNumber == interface_idx)
      return stream_if;
  }
  
  return NULL;
}

/** Open a new video stream.
 * @ingroup streaming
 *
 * @param devh UVC device
 * @param ctrl Control block, processed using {uvc_probe_stream_ctrl} or
 *             {uvc_get_stream_ctrl_format_size}
 */
uvc_error_t uvc_stream_open_ctrl(uvc_device_handle_t *devh, uvc_stream_handle_t **strmhp, uvc_stream_ctrl_t *ctrl) {
  /* Chosen frame and format descriptors */
  uvc_stream_handle_t *strmh = NULL;
  uvc_streaming_interface_t *stream_if;
  uvc_error_t ret;

  UVC_ENTER();

  if (_uvc_get_stream_by_interface(devh, ctrl->bInterfaceNumber) != NULL) {
    ret = UVC_ERROR_BUSY; /* Stream is already opened */
    goto fail;
  }

  stream_if = _uvc_get_stream_if(devh, ctrl->bInterfaceNumber);
  if (!stream_if) {
    ret = UVC_ERROR_INVALID_PARAM;
    goto fail;
  }

  strmh = calloc(1, sizeof(*strmh));
  if (!strmh) {
    ret = UVC_ERROR_NO_MEM;
    goto fail;
  }
  strmh->devh = devh;
  strmh->stream_if = stream_if;
  strmh->frame.library_owns_data = 1;

  ret = uvc_claim_if(strmh->devh, strmh->stream_if->bInterfaceNumber);
  if (ret != UVC_SUCCESS)
    goto fail;

  /* Reinitialize the single Bulk alternate setting on each open, matching
   * teardown's explicit SET_INTERFACE. Do this before COMMIT, since
   * SET_INTERFACE may stop the device's current video stream. Isochronous
   * settings are selected later.
   */
  const struct libusb_interface *stream_interface =
      &devh->info->config->interface[stream_if->bInterfaceNumber];
  if (stream_interface->num_altsetting == 1) {
    ret = libusb_set_interface_alt_setting(devh->usb_devh,
        stream_if->bInterfaceNumber,
        stream_interface->altsetting[0].bAlternateSetting);
#ifdef __ANDROID__
    __android_log_print(ret == UVC_SUCCESS ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
        "UVCLiveStreamingUsb", "UVC bulk prepare: interface=%u alt=%u result=%d",
        stream_if->bInterfaceNumber,
        stream_interface->altsetting[0].bAlternateSetting, ret);
#endif
    if (ret != UVC_SUCCESS)
      goto release_fail;
    /* Clear both the device halt/toggle state and the host controller's
     * Bulk endpoint state left by cancellation of the previous stream.
     * Selecting the same alternate setting alone is insufficient on some
     * SuperSpeed hosts. No transfers are submitted at this point.
     */
    ret = libusb_clear_halt(devh->usb_devh, stream_if->bEndpointAddress);
#ifdef __ANDROID__
    __android_log_print(ret == UVC_SUCCESS ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
        "UVCLiveStreamingUsb", "UVC bulk clear halt: endpoint=0x%02x result=%d",
        stream_if->bEndpointAddress, ret);
#endif
    if (ret != UVC_SUCCESS)
      goto release_fail;
  }

  ret = uvc_stream_ctrl(strmh, ctrl);
  if (ret != UVC_SUCCESS)
    goto release_fail;

  // Set up the streaming status and data space
  strmh->running = 0;

  strmh->outbuf = malloc( ctrl->dwMaxVideoFrameSize );
  strmh->holdbuf = malloc( ctrl->dwMaxVideoFrameSize );

  strmh->meta_outbuf = malloc( LIBUVC_XFER_META_BUF_SIZE );
  strmh->meta_holdbuf = malloc( LIBUVC_XFER_META_BUF_SIZE );
   
  pthread_mutex_init(&strmh->cb_mutex, NULL);
  pthread_cond_init(&strmh->cb_cond, NULL);

  DL_APPEND(devh->streams, strmh);

  *strmhp = strmh;

  UVC_EXIT(0);
  return UVC_SUCCESS;

release_fail:
  uvc_release_if(devh, stream_if->bInterfaceNumber);
fail:
  if(strmh)
    free(strmh);
  UVC_EXIT(ret);
  return ret;
}

/** Begin streaming video from the stream into the callback function.
 * @ingroup streaming
 *
 * @param strmh UVC stream
 * @param cb   User callback function. See {uvc_frame_callback_t} for restrictions.
 * @param flags Stream setup flags, currently undefined. Set this to zero. The lower bit
 * is reserved for backward compatibility.
 */
uvc_error_t uvc_stream_start(
    uvc_stream_handle_t *strmh,
    uvc_frame_callback_t *cb,
    void *user_ptr,
    uint8_t flags
) {
  /* USB interface we'll be using */
  const struct libusb_interface *interface;
  int interface_id;
  char isochronous;
  uvc_frame_desc_t *frame_desc;
  uvc_format_desc_t *format_desc;
  uvc_stream_ctrl_t *ctrl;
  uvc_error_t ret;
  /* Total amount of data per transfer */
  size_t total_transfer_size = 0;
  struct libusb_transfer *transfer;
  int transfer_id;
  int num_transfers = strmh->receive_transfer_count ? strmh->receive_transfer_count :
      (LIBUVC_NUM_TRANSFER_BUFS < 64 ? LIBUVC_NUM_TRANSFER_BUFS : 64);

  ctrl = &strmh->cur_ctrl;

  UVC_ENTER();

  if (strmh->running) {
    UVC_EXIT(UVC_ERROR_BUSY);
    return UVC_ERROR_BUSY;
  }

  strmh->running = 1;
  strmh->seq = 1;
  strmh->fid = 0;
  strmh->pts = 0;
  strmh->last_scr = 0;

  strmh->bulk_pending_bytes = 0;
  strmh->bulk_realign_active = 0;
  strmh->bulk_packet_size = 0;

  frame_desc = uvc_find_frame_desc_stream(strmh, ctrl->bFormatIndex, ctrl->bFrameIndex);
  if (!frame_desc) {
    ret = UVC_ERROR_INVALID_PARAM;
    goto fail;
  }
  format_desc = frame_desc->parent;

  strmh->frame_format = uvc_frame_format_for_guid(format_desc->guidFormat);
  if (strmh->frame_format == UVC_FRAME_FORMAT_UNKNOWN) {
    ret = UVC_ERROR_NOT_SUPPORTED;
    goto fail;
  }
  strmh->bulk_fixed_frame_size = format_desc->bVariableSize ? 0 :
      _uvc_bulk_fixed_frame_size(strmh->frame_format, frame_desc->wWidth, frame_desc->wHeight,
          frame_desc->dwBytesPerLine, ctrl->dwMaxVideoFrameSize);

  // Get the interface that provides the chosen format and frame configuration
  interface_id = strmh->stream_if->bInterfaceNumber;
  interface = &strmh->devh->info->config->interface[interface_id];

  /* A VS interface uses isochronous transfers iff it has multiple altsettings.
   * (UVC 1.5: 2.4.3. VideoStreaming Interface) */
  isochronous = interface->num_altsetting > 1;

  if (isochronous) {
    UVC_DEBUG("isochronous transfer mode:  num_altsetting=%d", interface->num_altsetting);
    /* For isochronous streaming, we choose an appropriate altsetting for the endpoint
     * and set up several transfers */
    const struct libusb_interface_descriptor *altsetting = 0;
    const struct libusb_endpoint_descriptor *endpoint = 0;
    /* The greatest number of bytes that the device might provide, per packet, in this
     * configuration */
    size_t config_bytes_per_packet;
    /* Number of packets per transfer */
    size_t packets_per_transfer = 0;
    /* Size of packet transferable from the chosen endpoint */
    size_t endpoint_bytes_per_packet = 0;
    /* Index of the altsetting */
    int alt_idx, ep_idx;
    
    config_bytes_per_packet = strmh->cur_ctrl.dwMaxPayloadTransferSize;

    /* Go through the altsettings and find one whose packets are at least
     * as big as our format's maximum per-packet usage. Assume that the
     * packet sizes are increasing. */
    for (alt_idx = 0; alt_idx < interface->num_altsetting; alt_idx++) {
      altsetting = interface->altsetting + alt_idx;
      endpoint_bytes_per_packet = 0;

      /* Find the endpoint with the number specified in the VS header */
      for (ep_idx = 0; ep_idx < altsetting->bNumEndpoints; ep_idx++) {
        endpoint = altsetting->endpoint + ep_idx;

        struct libusb_ss_endpoint_companion_descriptor *ep_comp = 0;
        libusb_get_ss_endpoint_companion_descriptor(NULL, endpoint, &ep_comp);
        if (ep_comp)
        {
          endpoint_bytes_per_packet = ep_comp->wBytesPerInterval;
          libusb_free_ss_endpoint_companion_descriptor(ep_comp);
          break;
        }
        else
        {
          if (endpoint->bEndpointAddress == format_desc->parent->bEndpointAddress) {
              endpoint_bytes_per_packet = endpoint->wMaxPacketSize;
            // wMaxPacketSize: [unused:2 (multiplier-1):3 size:11]
            endpoint_bytes_per_packet = (endpoint_bytes_per_packet & 0x07ff) *
              (((endpoint_bytes_per_packet >> 11) & 3) + 1);
            break;
          }
        }
      }

      if (endpoint_bytes_per_packet >= config_bytes_per_packet) {
        /* Transfers will be at most one frame long: Divide the maximum frame size
         * by the size of the endpoint and round up */
        packets_per_transfer = (ctrl->dwMaxVideoFrameSize +
                                endpoint_bytes_per_packet - 1) / endpoint_bytes_per_packet;

        /* But keep a reasonable limit: Otherwise we start dropping data */
        if (packets_per_transfer > LIBUVC_PACKETS_PER_TRANSFER_MAX)
          packets_per_transfer = LIBUVC_PACKETS_PER_TRANSFER_MAX;
        
        total_transfer_size = packets_per_transfer * endpoint_bytes_per_packet;
        break;
      }
    }

    /* If we searched through all the altsettings and found nothing usable */
    if (alt_idx == interface->num_altsetting) {
      ret = UVC_ERROR_INVALID_MODE;
      goto fail;
    }

    /* Select the altsetting */
    ret = libusb_set_interface_alt_setting(strmh->devh->usb_devh,
                                           altsetting->bInterfaceNumber,
                                           altsetting->bAlternateSetting);
    if (ret != UVC_SUCCESS) {
      UVC_DEBUG("libusb_set_interface_alt_setting failed");
      goto fail;
    }

  /* Set up the transfers */
  for (transfer_id = 0; transfer_id < num_transfers; ++transfer_id) {
      transfer = libusb_alloc_transfer(packets_per_transfer);
      strmh->transfers[transfer_id] = transfer;      
      strmh->transfer_bufs[transfer_id] = malloc(total_transfer_size);
      if (!transfer || !strmh->transfer_bufs[transfer_id]) {
        ret = UVC_ERROR_NO_MEM;
        goto fail;
      }

      libusb_fill_iso_transfer(
        transfer, strmh->devh->usb_devh, format_desc->parent->bEndpointAddress,
        strmh->transfer_bufs[transfer_id],
        total_transfer_size, packets_per_transfer, _uvc_stream_callback, (void*) strmh, 5000);

      libusb_set_iso_packet_lengths(transfer, endpoint_bytes_per_packet);
    }
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "UVCLiveStreamingUsb",
        "UVC iso receive setup: requests=%d packetsPerRequest=%zu packetBytes=%zu requestBytes=%zu queuedBytes=%llu",
        num_transfers, packets_per_transfer, endpoint_bytes_per_packet, total_transfer_size,
        (unsigned long long)num_transfers * total_transfer_size);
#endif
  } else {
    const struct libusb_interface_descriptor *altsetting = interface->altsetting;
    for (int ep_idx = 0; ep_idx < altsetting->bNumEndpoints; ++ep_idx) {
      const struct libusb_endpoint_descriptor *endpoint = altsetting->endpoint + ep_idx;
      if (endpoint->bEndpointAddress == format_desc->parent->bEndpointAddress) {
        strmh->bulk_packet_size = endpoint->wMaxPacketSize & 0x07ff;
        break;
      }
    }
    for (transfer_id = 0; transfer_id < num_transfers;
        ++transfer_id) {
      transfer = libusb_alloc_transfer(0);
      strmh->transfers[transfer_id] = transfer;
      strmh->transfer_bufs[transfer_id] = malloc (
          strmh->cur_ctrl.dwMaxPayloadTransferSize );
      if (!transfer || !strmh->transfer_bufs[transfer_id]) {
        ret = UVC_ERROR_NO_MEM;
        goto fail;
      }
      libusb_fill_bulk_transfer ( transfer, strmh->devh->usb_devh,
          format_desc->parent->bEndpointAddress,
          strmh->transfer_bufs[transfer_id],
          strmh->cur_ctrl.dwMaxPayloadTransferSize, _uvc_stream_callback,
          ( void* ) strmh, 5000 );
    }
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "UVCLiveStreamingUsb",
        "UVC bulk receive setup: speedEnum=%d packetBytes=%zu requests=%d requestBytes=%u queuedBytes=%llu fixedFrameBytes=%zu",
        libusb_get_device_speed(libusb_get_device(strmh->devh->usb_devh)),
        strmh->bulk_packet_size, num_transfers,
        strmh->cur_ctrl.dwMaxPayloadTransferSize,
        (unsigned long long)num_transfers * strmh->cur_ctrl.dwMaxPayloadTransferSize,
        strmh->bulk_fixed_frame_size);
#endif
  }

  strmh->user_cb = cb;
  strmh->user_ptr = user_ptr;

  /* If the user wants it, set up a thread that calls the user's function
   * with the contents of each frame.
   */
  if (cb) {
    if (pthread_create(&strmh->cb_thread, NULL, _uvc_user_caller, (void*) strmh) != 0) {
      strmh->user_cb = NULL;
      ret = UVC_ERROR_NO_MEM;
      goto fail;
    }
  }

  for (transfer_id = 0; transfer_id < num_transfers;
      transfer_id++) {
    ret = libusb_submit_transfer(strmh->transfers[transfer_id]);
    if (ret != UVC_SUCCESS) {
      UVC_DEBUG("libusb_submit_transfer failed: %d",ret);
      break;
    }
  }

  if ( ret != UVC_SUCCESS && transfer_id >= 0 ) {
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_ERROR, "UVCLiveStreamingUsb",
        "UVC receive queue submit failed: submitted=%d requested=%d error=%d",
        transfer_id, num_transfers, ret);
#endif

    for ( ; transfer_id < num_transfers; transfer_id++) {
      free ( strmh->transfers[transfer_id]->buffer );
      libusb_free_transfer ( strmh->transfers[transfer_id]);
      strmh->transfers[transfer_id] = 0;
      strmh->transfer_bufs[transfer_id] = NULL;
    }

    /* Do not silently run with a smaller queue than the selected count. */
    uvc_stream_stop(strmh);
    UVC_EXIT(ret);
    return ret;
  }

  UVC_EXIT(ret);
  return ret;
fail:
  /* No requests have been submitted on this path. */
  for (transfer_id = 0; transfer_id < num_transfers; ++transfer_id) {
    free(strmh->transfer_bufs[transfer_id]);
    strmh->transfer_bufs[transfer_id] = NULL;
    if (strmh->transfers[transfer_id]) {
      libusb_free_transfer(strmh->transfers[transfer_id]);
      strmh->transfers[transfer_id] = NULL;
    }
  }
  strmh->running = 0;
  UVC_EXIT(ret);
  return ret;
}

uvc_error_t uvc_stream_set_receive_transfer_count(uvc_stream_handle_t *strmh, int count) {
  if (!strmh || count < 8 || count > LIBUVC_NUM_TRANSFER_BUFS)
    return UVC_ERROR_INVALID_PARAM;
  if (strmh->running)
    return UVC_ERROR_BUSY;
  strmh->receive_transfer_count = count;
  return UVC_SUCCESS;
}

/** Begin streaming video from the stream into the callback function.
 * @ingroup streaming
 *
 * @deprecated The stream type (bulk vs. isochronous) will be determined by the
 * type of interface associated with the uvc_stream_ctrl_t parameter, regardless
 * of whether the caller requests isochronous streaming. Please switch to
 * uvc_stream_start().
 *
 * @param strmh UVC stream
 * @param cb   User callback function. See {uvc_frame_callback_t} for restrictions.
 */
uvc_error_t uvc_stream_start_iso(
    uvc_stream_handle_t *strmh,
    uvc_frame_callback_t *cb,
    void *user_ptr
) {
  return uvc_stream_start(strmh, cb, user_ptr, 0);
}

/** @internal
 * @brief User callback runner thread
 * @note There should be at most one of these per currently streaming device
 * @param arg Device handle
 */
void *_uvc_user_caller(void *arg) {
  uvc_stream_handle_t *strmh = (uvc_stream_handle_t *) arg;

  uint32_t last_seq = 0;

  do {
    pthread_mutex_lock(&strmh->cb_mutex);

    while (strmh->running && last_seq == strmh->hold_seq) {
      pthread_cond_wait(&strmh->cb_cond, &strmh->cb_mutex);
    }

    if (!strmh->running) {
      pthread_mutex_unlock(&strmh->cb_mutex);
      break;
    }
    
    last_seq = strmh->hold_seq;
    _uvc_populate_frame(strmh);
    
    pthread_mutex_unlock(&strmh->cb_mutex);
    
    strmh->user_cb(&strmh->frame, strmh->user_ptr);
  } while(1);

  return NULL; // return value ignored
}

/** @internal
 * @brief Populate the fields of a frame to be handed to user code
 * must be called with stream cb lock held!
 */
void _uvc_populate_frame(uvc_stream_handle_t *strmh) {
  uvc_frame_t *frame = &strmh->frame;
  uvc_frame_desc_t *frame_desc;

  /** @todo this stuff that hits the main config cache should really happen
   * in start() so that only one thread hits these data. all of this stuff
   * is going to be reopen_on_change anyway
   */

  frame_desc = uvc_find_frame_desc_stream(strmh, strmh->cur_ctrl.bFormatIndex,
				   strmh->cur_ctrl.bFrameIndex);

  frame->frame_format = strmh->frame_format;
  
  frame->width = frame_desc->wWidth;
  frame->height = frame_desc->wHeight;
  
  switch (frame->frame_format) {
  case UVC_FRAME_FORMAT_BGR:
    frame->step = frame->width * 3;
    break;
  case UVC_FRAME_FORMAT_YUYV:
    frame->step = frame->width * 2;
    break;
  case UVC_FRAME_FORMAT_NV12:
  case UVC_FRAME_FORMAT_NV21:
  case UVC_FRAME_FORMAT_I420:
    frame->step = frame->width;
    break;
  case UVC_FRAME_FORMAT_P010:
    frame->step = frame->width * 2;
        break;
  case UVC_FRAME_FORMAT_MJPEG:
    frame->step = 0;
    break;
  case UVC_FRAME_FORMAT_H264:
    frame->step = 0;
    break;
  default:
    frame->step = 0;
    break;
  }

  frame->sequence = strmh->hold_seq;
  frame->capture_time_finished = strmh->capture_time_finished;

  /* copy the image data from the hold buffer to the frame (unnecessary extra buf?) */
  if (frame->data_bytes < strmh->hold_bytes) {
    frame->data = realloc(frame->data, strmh->hold_bytes);
  }
  frame->data_bytes = strmh->hold_bytes;
  memcpy(frame->data, strmh->holdbuf, frame->data_bytes);

  if (strmh->meta_hold_bytes > 0)
  {
      if (frame->metadata_bytes < strmh->meta_hold_bytes)
      {
          frame->metadata = realloc(frame->metadata, strmh->meta_hold_bytes);
      }
      frame->metadata_bytes = strmh->meta_hold_bytes;
      memcpy(frame->metadata, strmh->meta_holdbuf, frame->metadata_bytes);
  }
}

/** Poll for a frame
 * @ingroup streaming
 *
 * @param devh UVC device
 * @param[out] frame Location to store pointer to captured frame (NULL on error)
 * @param timeout_us >0: Wait at most N microseconds; 0: Wait indefinitely; -1: return immediately
 */
uvc_error_t uvc_stream_get_frame(uvc_stream_handle_t *strmh,
			  uvc_frame_t **frame,
			  int32_t timeout_us) {
  time_t add_secs;
  time_t add_nsecs;
  struct timespec ts;

  if (!strmh->running)
    return UVC_ERROR_INVALID_PARAM;

  if (strmh->user_cb)
    return UVC_ERROR_CALLBACK_EXISTS;

  pthread_mutex_lock(&strmh->cb_mutex);

  if (strmh->last_polled_seq < strmh->hold_seq) {
    _uvc_populate_frame(strmh);
    *frame = &strmh->frame;
    strmh->last_polled_seq = strmh->hold_seq;
  } else if (timeout_us != -1) {
    if (timeout_us == 0) {
      pthread_cond_wait(&strmh->cb_cond, &strmh->cb_mutex);
    } else {
      add_secs = timeout_us / 1000000;
      add_nsecs = (timeout_us % 1000000) * 1000;
      ts.tv_sec = 0;
      ts.tv_nsec = 0;

#if _POSIX_TIMERS > 0
      clock_gettime(CLOCK_REALTIME, &ts);
#else
      struct timeval tv;
      gettimeofday(&tv, NULL);
      ts.tv_sec = tv.tv_sec;
      ts.tv_nsec = tv.tv_usec * 1000;
#endif

      ts.tv_sec += add_secs;
      ts.tv_nsec += add_nsecs;

      /* pthread_cond_timedwait FAILS with EINVAL if ts.tv_nsec > 1000000000 (1 billion)
       * Since we are just adding values to the timespec, we have to increment the seconds if nanoseconds is greater than 1 billion,
       * and then re-adjust the nanoseconds in the correct range.
       * */
      ts.tv_sec += ts.tv_nsec / 1000000000;
      ts.tv_nsec = ts.tv_nsec % 1000000000;

      int err = pthread_cond_timedwait(&strmh->cb_cond, &strmh->cb_mutex, &ts);

      //TODO: How should we handle EINVAL?
      if (err) {
        *frame = NULL;
        pthread_mutex_unlock(&strmh->cb_mutex);
        return err == ETIMEDOUT ? UVC_ERROR_TIMEOUT : UVC_ERROR_OTHER;
      }
    }
    
    if (strmh->last_polled_seq < strmh->hold_seq) {
      _uvc_populate_frame(strmh);
      *frame = &strmh->frame;
      strmh->last_polled_seq = strmh->hold_seq;
    } else {
      *frame = NULL;
    }
  } else {
    *frame = NULL;
  }

  pthread_mutex_unlock(&strmh->cb_mutex);

  return UVC_SUCCESS;
}

/** @brief Stop streaming video
 * @ingroup streaming
 *
 * Closes all streams, ends threads and cancels pollers
 *
 * @param devh UVC device
 */
void uvc_stop_streaming(uvc_device_handle_t *devh) {
  uvc_stream_handle_t *strmh, *strmh_tmp;

  DL_FOREACH_SAFE(devh->streams, strmh, strmh_tmp) {
    uvc_stream_close(strmh);
  }
}

/** @brief Stop stream.
 * @ingroup streaming
 *
 * Stops stream, ends threads and cancels pollers
 *
 * @param devh UVC device
 */
uvc_error_t uvc_stream_stop(uvc_stream_handle_t *strmh) {
  int i;

  if (!strmh->running)
    return UVC_ERROR_INVALID_PARAM;

  strmh->running = 0;

  pthread_mutex_lock(&strmh->cb_mutex);

  /* Attempt to cancel any running transfers, we can't free them just yet because they aren't
   *   necessarily completed but they will be free'd in _uvc_stream_callback().
   */
  for(i=0; i < LIBUVC_NUM_TRANSFER_BUFS; i++) {
    if(strmh->transfers[i] != NULL)
      libusb_cancel_transfer(strmh->transfers[i]);
  }

  /* Wait for transfers to complete/cancel */
  do {
    for(i=0; i < LIBUVC_NUM_TRANSFER_BUFS; i++) {
      if(strmh->transfers[i] != NULL)
        break;
    }
    if(i == LIBUVC_NUM_TRANSFER_BUFS )
      break;
    pthread_cond_wait(&strmh->cb_cond, &strmh->cb_mutex);
  } while(1);
  // Kick the user thread awake
  pthread_cond_broadcast(&strmh->cb_cond);
  pthread_mutex_unlock(&strmh->cb_mutex);

  /** @todo stop the actual stream, camera side? */

  if (strmh->user_cb) {
    /* wait for the thread to stop (triggered by
     * LIBUSB_TRANSFER_CANCELLED transfer) */
    pthread_join(strmh->cb_thread, NULL);
  }

  return UVC_SUCCESS;
}

/** @brief Close stream.
 * @ingroup streaming
 *
 * Closes stream, frees handle and all streaming resources.
 *
 * @param strmh UVC stream handle
 */
void uvc_stream_close(uvc_stream_handle_t *strmh) {
  if (strmh->running)
    uvc_stream_stop(strmh);

  int release_result = uvc_release_if(strmh->devh, strmh->stream_if->bInterfaceNumber);
#ifdef __ANDROID__
  __android_log_print(ANDROID_LOG_INFO, "UVCLiveStreamingUsb",
      "UVC closed: interface=%u callbacks=%llu receivedBytes=%llu frames=%u transferErrors=%llu submitErrors=%llu releaseResult=%d",
      strmh->stream_if->bInterfaceNumber,
      (unsigned long long)strmh->diagnostic_transfer_callbacks,
      (unsigned long long)strmh->received_video_bytes, strmh->seq ? strmh->seq - 1 : 0,
      (unsigned long long)strmh->diagnostic_transfer_errors,
      (unsigned long long)strmh->diagnostic_submit_errors, release_result);
#else
  (void)release_result;
#endif

  if (strmh->frame.data)
    free(strmh->frame.data);

  if (strmh->frame.metadata)
    free(strmh->frame.metadata);

  free(strmh->outbuf);
  free(strmh->holdbuf);
  free(strmh->bulk_pending_buf);

  free(strmh->meta_outbuf);
  free(strmh->meta_holdbuf);

  pthread_cond_destroy(&strmh->cb_cond);
  pthread_mutex_destroy(&strmh->cb_mutex);

  DL_DELETE(strmh->devh->streams, strmh);
  free(strmh);
}
