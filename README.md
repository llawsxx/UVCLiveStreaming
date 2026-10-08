# USB Live Studio

Android USB camera recorder and streamer.

- UVC video and UAC microphone capture through libusb/libuvc/libuac.
- MJPG, YUYV/UYVY, RGB/BGR, NV12, I420 and P010 conversion; H.264 USB modes are exposed for MediaCodec decode integration.
- Live raw video uploads the captured direct buffer in its native layout; the GPU shader reads packed YUYV/UYVY and semiplanar NV12/P010 without CPU repacking. See `diagnostics/raw-gpu-upload/README.md` for validation and 720p/1080p/4K speed measurements.
- Preview and recording wait only on the current input format's queue; raw frames no longer wait for an empty MJPEG queue. Empty input still blocks rather than busy-spinning.
- MediaCodec H.264/H.265 and AAC encoding, MP4 or MPEG-TS recording.
- Experimental saved "YUV 直接输入编码器（实验）" option sends YUV frames to writable MediaCodec YUV420 input planes without an RGB intermediate or encoder Surface. Preview still uses the GPU; unsupported encoders, HDR, test cards and active recording grading fall back to the Surface path. See `diagnostics/raw-gpu-upload/README.md` for paired speed measurements.
- Multi-client HTTP MPEG-TS server and RTMP publishing.
- Optional HTTP TS chunk publishing with memory-buffered retry and a C++17 delay relay.
  Enable "HTTP 远程分块上传" in the USB Output tab; the HTTP button then uploads to
  `http://server:8080/upload/live`. Players open `http://server:8080/live/live.ts`.
  Default chunks are 1 second, pending cache is 60 seconds (256 MiB maximum), and
  server playback delay is 10 seconds. Session changes append to the existing queue;
  the relay forwards TS bytes unchanged, without timestamp or keyframe adjustment.
  Live publishing retires old/expired blocks when its cache reaches a limit; missing
  sequences are accepted. The relay uses `--max-pending-seconds` (default 20) to
  jump all viewers forward to the 10-second delay target when pending reaches that maximum.
  It refills to the delay target after its playback buffer empties. Cache bytes are
  bounded by `--buffer-mb`; played/skipped history expires according to `--retention`.
  Acknowledged blocks are removed from memory. Stopping cancels the active POST,
  ends retrying, and clears pending blocks and the partial chunk. Restarting creates
  a fresh publishing session. No cache files are written, and pending media is lost
  when the sender process exits.
  The Output tab shows the session ID, generated/in-flight/acknowledged sequences,
  pending upload/retry counts, cache bytes/duration and chunk assembly progress.
  The relay logs each upload and reports throughput/cache status every second.
  Build, protocol and limitations: [HTTP TS relay](server/http-ts-relay/README.md).
- Recording, HTTP streaming and RTMP publishing can be started/stopped independently during one capture session; they share the same H.264/HEVC + AAC encoder output. Newly added outputs begin at a keyframe with their own timestamp origin.
- Stop buttons for recording, HTTP and RTMP ask for confirmation by default.
  The saved "Confirm before stopping" setting in the Output tab can disable it.
- RTMP H.264/HEVC (`hvc1`) + AAC publishing with reconnect.
- The main status and Output tab show RTMP reconnect attempts for the current
  publishing session. The first connection is excluded; restarting RTMP resets
  the count, and failed retries are included.
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
- RTMP send timeout is selectable in the Output tab (3/5/10/15/30 seconds, default
  10). Each blocked socket write is watched independently; a timeout closes the
  socket to unblock sending and reconnect with fresh media. The saved setting
  takes effect when RTMP starts and covers handshake and control writes as well.
- RTMP transport handles negotiated chunks, compressed headers, extended timestamps, transaction replies, server stream IDs, acknowledgements and ping replies; it is a plain RTMP publisher rather than a complete FFmpeg protocol port.
- Preview has separate start/stop controls; USB audio RMS is shown in dBFS.
- Optional 5 fps preview can be toggled during preview, recording or streaming; capture and encoder frame rates are unaffected.
- Timestamp smoothing can calculate nominal 60/30 fps video using the exact NTSC rates 60000/1001 and 30000/1001; audio continues to use its actual sample rate.
- Video and AAC audio bitrates are entered in kbps. Audio defaults to 192 kbps,
  accepts 16-512 kbps, and is saved for subsequent sessions. Recording and
  streaming use the same audio encoder settings.
- The Device tab has a saved audio delay from -500 to +500 milliseconds (default
  0), applied to PCM timestamps after optional PCM smoothing, before AAC encoding.
  Negative values advance audio; positive values delay it. PCM samples are unchanged,
  and the original monotonic capture clock remains in use with smoothing enabled or disabled.
- The Output tab has a saved muxing reorder queue size (0-1024 packets, default 64).
  Each recording, HTTP or RTMP output retains this many combined audio/video packets,
  and interleaves by final encoder timestamps after optional AAC smoothing. Larger
  queues give late packets more opportunity to be reordered and increase output latency;
  0 writes immediately. Reordering is best effort within the retained window: packets
  arriving behind already written media are still written with their original timestamps;
  the reorder queue neither drops media nor guarantees globally increasing timestamps.
  Audio and video each keep their original encoder order, even when PTS decreases.
  Only their queue heads are compared by final PTS to choose the next interleaved packet;
  equal timestamps retain arrival order. This also preserves video decode order for B frames.
  Stopping a recording flushes its remaining packets. Both settings
  take effect at the next capture start. This is a continuous reorder window, not a
  direct implementation of FFmpeg's startup-only max_muxing_queue_size limit.
- Selectable BT.601/BT.709/BT.2020 NCL/SMPTE 240M source matrix and automatic/TV/full source range, shared by preview and encoding. Surface encoding converts through RGB; direct YUV encoding fuses source/output matrix and range conversion without an RGB frame.
- Optional encoded H.264/HEVC SPS/VUI color rewriting with independent Range,
  Primaries, Transfer and Matrix selections; each field can preserve its original
  value. Codec configuration and in-band SPS are rewritten once before all
  recording/streaming outputs, without changing video pixels or timestamps.

MJPG currently uses libjpeg-turbo software decoding. Android does not guarantee a portable `video/mjpeg` hardware decoder through MediaCodec, so software decoding is the reliable path.

Build with `gradlew.bat :app:assembleDebug`.
