# USB Live Studio

Android USB camera recorder and streamer.

- UVC video and UAC microphone capture through libusb/libuvc/libuac.
- MJPG, YUYV/UYVY, RGB/BGR, NV12, I420 and P010 conversion; H.264 USB modes are exposed for MediaCodec decode integration.
- MediaCodec H.264/H.265 and AAC encoding, MP4 or MPEG-TS recording.
- Multi-client HTTP MPEG-TS server and RTMP publishing.
- Recording, HTTP streaming and RTMP publishing can be started/stopped independently during one capture session; they share the same H.264/HEVC + AAC encoder output. Newly added outputs begin at a keyframe with their own timestamp origin.
- RTMP H.264/HEVC (`hvc1`) + AAC publishing with reconnect.
- RTMP reconnect discards queued and disconnected media, requests a fresh video
  keyframe after publishing is ready, and restarts audio/video timestamps together
  from that frame without replaying the outage backlog.
- RTMP send queue capacity is selectable in the Output tab from 0.1 to 30 seconds
  of encoding bitrate (default 5 seconds). The setting is saved and takes effect
  when RTMP starts, including when it is added to an ongoing capture session.
- On RTMP queue overflow, old video is discarded through the next video keyframe
  while earlier audio remains queued. If no keyframe is available, audio continues
  while a fresh video keyframe is requested; audio can resume ahead of the picture.
  Audio-only backlog is still bounded by the configured queue capacity.
- RTMP transport handles negotiated chunks, compressed headers, extended timestamps, transaction replies, server stream IDs, acknowledgements and ping replies; it is a plain RTMP publisher rather than a complete FFmpeg protocol port.
- Preview has separate start/stop controls; USB audio RMS is shown in dBFS.
- Optional 5 fps preview can be toggled during preview, recording or streaming; capture and encoder frame rates are unaffected.
- Timestamp smoothing can calculate nominal 60/30 fps video using the exact NTSC rates 60000/1001 and 30000/1001; audio continues to use its actual sample rate.
- Video and AAC audio bitrates are entered in kbps. Audio defaults to 192 kbps,
  accepts 16-512 kbps, and is saved for subsequent sessions. Recording and
  streaming use the same audio encoder settings.
- Selectable BT.601/BT.709/BT.2020 NCL/SMPTE 240M YUV-to-RGB conversion and automatic/TV/full source range, shared by preview and encoding.
- Optional encoded H.264/HEVC SPS/VUI color rewriting with independent Range,
  Primaries, Transfer and Matrix selections; each field can preserve its original
  value. Codec configuration and in-band SPS are rewritten once before all
  recording/streaming outputs, without changing video pixels or timestamps.

MJPG currently uses libjpeg-turbo software decoding. Android does not guarantee a portable `video/mjpeg` hardware decoder through MediaCodec, so software decoding is the reliable path.

Build with `gradlew.bat :app:assembleDebug`.
