# MS2130 raw frame rate and tearing (2026-10-05)

Xiaomi 24031PN0DC, MACROSILICON MS2130 (`345f:2130`), SuperSpeed video
Bulk endpoint `0x83`, 1024-byte packets, 15360-byte receive requests.

## Baseline

The user reported approximately 59 fps at YUYV 720p60/1080p60 and 29 fps
at 1080p30, with visible tearing. Stable log windows before the change:

| Mode | Sample | JNI frames / elapsed | FPS |
| --- | --- | --- | --- |
| 720p60 | 65.110 s | 3843 | 59.0232 |
| 1080p30 | 175.574 s | 5093 | 29.0077 |

The deficit was already present at UVC assembly, before rendering/encoding.
No short/long raw frames, truncated bytes, transfer/submission errors, or
callback sequence gaps appeared in these windows. 1080p30 received about
120 MB/s, below its 124.416 MB/s pure-image requirement. This was a live
preview test, without a RecordingService running.

An eight-second sched/freq/idle trace showed the USB event thread using
2642 ms CPU (about 33% of one core). Wake-to-run delay median/P95/P99 were
0.009/0.017/0.065 ms, but the maximum was 6.819 ms; 71 waits exceeded 1 ms
and ten exceeded 3 ms. These measurements have trace overhead and are not
direct measurements of empty USB queues.

The old eight-request queue contained only 122880 bytes: approximately
0.49 ms of YUYV 1080p60 image traffic or 0.99 ms at 1080p30. It offered little
tolerance for occasional scheduling or receive processing pauses.

## Change and validation

Bulk requests increased from 8 to 64 (983040 queued bytes, 960 KiB for this
device). ISO uses a separate eight-request limit. Allocation, submission and
partial submission cleanup use the actual selected count; stop/close scan the
bounded maximum-sized array. No artificial frames or timestamps are added.

The user manually installed the diagnostic APK and confirmed no obvious tearing
at YUYV 1080p30, 1080p60 and 720p60. Device logs show:

- 1080p30: approximately 30 fps and 124.4 MB/s, zero transfer/submission errors
  and zero raw size/truncation errors.
- 1080p60: approximately 60 fps and 248 MB/s in the initial stable windows,
  again without transfer/submission or raw size errors. Shorter subsequent
  windows fluctuated (including around 61 fps); this change improves reception
  and does not enforce a fixed output cadence or prove exact 60.000 fps.
  A later 70.126-second interval contained 4222 frames (60.2059 fps).
- 720p60: 301 frames over 5.014 s (60.0319 fps) in one stable window;
  a subsequent session yielded 59.6687 fps over 15.033 seconds. Short-window
  variation remains and this test does not establish a strict constant 60 fps.

Temporary raw header tracing verified the 1080p30 boundary: the last image
payload brought the image to exactly 4147200 bytes, followed by a separate
12-byte header-only EOF with the same PTS/FID, then a new frame with changed
FID/PTS. No mixed-frame boundary was found in that sample. Publishing at
fixed size before the header-only EOF explains `sizeWithoutEOF=frames`; that
counter alone does not prove malformed input. The temporary trace hook was
removed after inspection; normal diagnostic counters remain.

The queue change restored the tested frame rates and eliminated user-observed
tearing during these checks. Reception backpressure is supported by the
before/after comparison; a particular capture-card internal buffering or
overwrite mechanism was not directly observed and should not be asserted.

ARM64 debug build passed. The existing native UVC payload test suite was built
with the new 64-slot structure size and run on the Xiaomi in a separate native
process; it passed all MJPEG/raw coalescing, header-only EOF, frame-size and
timestamp counter cases. It did not claim/open the physical card.

Local evidence (ignored): `build/perf-diagnostics/ms2130-raw/`, including
baseline logs, the eight-second trace, before/after rate analysis, and the
queue64 device logs. Installation and mode switching were performed by the
user; no stream was interrupted by ADB.

## Configurable receive queue

The Device tab now offers `USB 接收队列`: 8, 16, 32, 64 (default), 128,
256 or 512 requests. Stop capture before changing it; the next preview, recording
or streaming capture uses the saved setting. Both UI and recording preferences
persist it. This is the USB request queue, independent of `视频缓存` (complete
frames awaiting processing); ISO video still uses eight requests and UAC is
unaffected.

For this device's 15360-byte requests, 64/128/256/512 correspond to
960 KiB / 1.875 MiB / 3.75 MiB / 7.5 MiB, approximately 3.95/7.90/15.80/31.60 ms of raw
1080p60 image traffic. Larger queues tolerate longer host processing pauses,
but do not increase bus bandwidth or guarantee a constant source frame rate.
Actual request size remains device-negotiated; no frame batching delay is
introduced by changing the request count.

The compiled slot limit is 512; actual allocation/submission uses the selected
count. A failed allocation, callback thread creation, or submission fails start
and cleans up, rather than silently running with fewer requests. Cancelled
requests clear both transfer and buffer pointers.

`uvc_receive_queue_test.c` uses a fake USB transport with the real libuvc
start/stop code. On the Xiaomi, it passed every selectable count and the
64-request default, verified ISO remains at eight for each setting, rejected
out-of-range/live changes, and checked resource cleanup on first/mid-queue
allocation and submission failures. The existing MJPEG/raw payload regression
suite also passed with the 256-slot structure. These standalone tests do not
claim the physical device. In a subsequent physical-device test, the user
reported YUYV 1080p60 no longer dropped frames at 256, while 128 remained
insufficient. The 512 option extends the maximum receive budget; its real-device
performance has not yet been verified.

After increasing the compiled limit to 512, both native suites passed again
on the Xiaomi in independent processes. Queue tests additionally exercised
512-request start/stop and first/last-request allocation/submission failures;
ISO remained at eight requests. The ARM64 debug APK build passed.
