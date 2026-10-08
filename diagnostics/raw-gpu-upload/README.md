# Raw capture-buffer GPU upload (2026-10-08)

The live preview and recording/streaming paths now retain the captured native
direct buffer until `render()` returns. They no longer call the native raw
repacker, allocate a second pixel buffer, or copy RGB/BGR/I420 frames before GL
upload. Invalid inputs and shutdown release the capture lease; successful input
ownership transfers to `ConvertedFrame`. Closing the converter does not release
an outstanding frame prematurely. The ByteArray compatibility path and native
repacker remain available for existing callers and reference tests.

Texture layouts:

- YUYV/UYVY: one RGBA8 texture, width/2 by height, holding each original four-byte
  pixel pair. The shader reconstructs the two luminance samples independently,
  retaining bilinear luma and chroma filtering, including scaled previews.
- NV12: original Y as LUMINANCE and interleaved UV as LUMINANCE_ALPHA.
- P010: original Y words as LUMINANCE_ALPHA and UV word pairs as RGBA. Both
  little-endian bytes contribute to ten-bit reconstruction and interpolation.
- I420: original contiguous Y/U/V planes. RGB/BGR: original RGB texture data.

The existing matrix, TV/full range, LUT, timestamp, and preview-rate behavior
are preserved. This removes application-side CPU repacking/copying, not the GL
driver's upload into texture storage; it is not a zero-copy GPU import.

## Verification

- ARM64 Debug APK built successfully. JVM: 164 tests, 162 passed, two skipped,
  no failures. New cases verify original layouts, shared storage, limit/position
  handling, zero conversion-pool allocations, and exactly-once capture release.
- Eleven GPU/encoding regression methods passed on vivo V2338A (SM8550).
  All seven raw formats match the former planar output within 2/255 per RGB
  channel, at native, enlarged, and reduced sizes. Cases include odd I420/RGB
  dimensions, all four YUV matrices, TV/full range, and repeated layout changes.
- P010 low bits, native BGR, orientation, LUT bypass, test-card switching, and
  five-fps preview without dropping encoder frames passed existing regressions.
- Actual `c2.qti.avc.encoder` and `c2.qti.hevc.encoder` each encoded 21 direct raw
  frames at supported 256x256 geometry, covering all seven layouts, with every
  frame and capture PTS preserved. This is not a 4K encoding throughput test.
- The repository's existing `UsbUacCaptureDeviceTest.kt` still uses the old
  ByteArray callback and prevents an unfiltered AndroidTest build. The diagnostic
  build excludes only that file via an ignored Gradle init script; no unrelated
  test source was changed. An initial selection of the existing 64x64 codec test
  failed codec configuration on this device; the new raw encoder test uses the
  supported 256x256 size instead.
- APKs/tests run in a separate `app_process`. No APK was installed, no physical
  USB device was opened, and the running application's PID stayed 16630.

## Speed methodology

Same Debug APK/native library for both algorithms, one same-resolution RGBA
ImageReader output surface, default BT.601/AUTO, no LUT or second preview draw.
The baseline invokes the former JNI raw-to-planar conversion (or native copy
for I420/RGB/BGR) into a reusable buffer. Direct mode builds frame metadata around
the original reusable input buffer. Both then use the production renderer.

Each format runs baseline/direct/direct/baseline (ABBA). Each block warms up
20 frames and measures 60: 120 samples per mode per invocation. Timing starts
before conversion/frame construction and ends after `render()` plus `glFinish()`;
it includes CPU preparation, texture upload, EGL draw/swap and GPU completion.
Input generation, allocation, and ImageReader image acquisition/release are
outside timing. Each image is consumed before submitting the next frame.

These are synchronized per-frame processing latencies, not end-to-end capture
FPS. USB reception, JPEG decoding, MediaCodec, network output, and a second
preview draw are excluded. Short-run CPU/GPU frequency, cache and current phone
load can affect results. Thermal status was zero before and after testing.

### 1080p and 4K

First 1080p invocation and the 4K invocation, milliseconds per frame:

| Input | Former mean | Direct mean | Direct P95 | Direct >16.67 ms /120 |
| --- | ---: | ---: | ---: | ---: |
| 1920x1080 YUYV | 8.334 | 4.637 | 5.162 | 0 |
| 1920x1080 UYVY | 9.769 | 4.372 | 5.256 | 0 |
| 1920x1080 NV12 | 9.366 | 5.835 | 7.013 | 0 |
| 1920x1080 I420 | 5.990 | 7.516 | 8.367 | 0 |
| 1920x1080 P010 | 9.833 | 5.807 | 7.611 | 0 |
| 1920x1080 RGB | 6.993 | 4.610 | 5.662 | 0 |
| 1920x1080 BGR | 6.541 | 4.919 | 6.408 | 0 |
| 3840x2160 YUYV | 21.470 | 11.474 | 13.616 | 0 |
| 3840x2160 UYVY | 21.661 | 10.574 | 12.056 | 0 |
| 3840x2160 NV12 | 31.530 | 14.219 | 15.591 | 2 |
| 3840x2160 I420 | 18.616 | 14.632 | 15.506 | 2 |
| 3840x2160 P010 | 34.020 | 13.024 | 14.732 | 0 |
| 3840x2160 RGB | 21.312 | 13.149 | 15.359 | 4 |
| 3840x2160 BGR | 19.577 | 13.294 | 15.167 | 1 |

1080p repeated invocation, former/direct mean ms: YUYV 9.677/3.984,
UYVY 9.298/5.256, NV12 9.416/4.894, I420 6.445/5.916,
P010 9.372/6.805, RGB 7.062/4.900, BGR 7.043/4.978.
The I420 result does not establish a repeatable overall speedup at 1080p even
though the application-side copy is removed.

At 4K, mean CPU preparation drops from 10.775 to 0.049 ms for YUYV,
10.945 to 0.049 for UYVY, 21.389 to 0.057 for NV12, and 21.557 to
0.050 for P010. The four YUV formats improve total mean latency by approximately
47%, 51%, 55%, and 62% respectively. All direct means and P95s are below the
16.67 ms 60-fps period, but NV12/I420/RGB/BGR have isolated over-budget frames;
this does not establish sustained 4K60 USB capture plus encoding/streaming.

### 720p

First invocation, mean ms per frame:

| Input | Former | Direct |
| --- | ---: | ---: |
| YUYV | 4.662 | 2.760 |
| UYVY | 4.988 | 2.669 |
| NV12 | 5.304 | 2.638 |
| I420 | 3.244 | 2.733 |
| P010 | 7.607 | 2.944 |
| RGB | 5.115 | 6.125 |
| BGR | 4.930 | 6.080 |

The repeat also found slower overall RGB/BGR direct uploads at 720p despite
lower CPU preparation time (RGB 4.891/5.989, BGR 3.250/5.899 ms). Removing a
copy alone therefore does not guarantee faster complete upload/render latency
for every format and size. The principal consistent gain is eliminating YUV
deinterleaving; GL driver upload cost still matters.

## Reproduction and evidence

`RawGpuUploadTest` contains the functional, encoder, 720p/1080p, and separate
4K benchmarks. `RawGpuSmoke.java` invokes these tests without an instrumentation
installation: no argument selects regressions, `speed` selects 720p/1080p,
and `speed4k` selects 3840x2160.

Build APP/AndroidTest with `:app:assembleDebug :app:assembleDebugAndroidTest`
and `-Pandroid.injected.build.abi=arm64-v8a`. For the current unrelated UAC
compile failure, a local Gradle init script may use:

```groovy
gradle.projectsEvaluated {
    gradle.rootProject.project(':app').android.sourceSets.androidTest.kotlin.exclude('**/UsbUacCaptureDeviceTest.kt')
}
```

Pass that ignored file with `-I <init-script>`. Current AGP APKs are under
`app/build/intermediates/apk/debug/` and
`app/build/intermediates/apk/androidTest/debug/`. Compile the Java launcher to
Java 8 bytecode and dex it with D8 using JDK 11 or newer. Push the dex, APKs,
and extracted `lib/arm64-v8a/*.so` into a diagnostic directory under
`/data/local/tmp`. Set `CLASSPATH` to the launcher dex plus both APKs and
`LD_LIBRARY_PATH`/`-Djava.library.path` to the native library directory when
starting `app_process /system/bin RawGpuSmoke [speed|speed4k]`.

Ignored local evidence: `build/perf-diagnostics/raw-gpu-upload/results-final.log`,
`speed.log`, `speed-repeat.log`, `speed-4k.log`, and device/thermal snapshots.

## Isolated production-pipeline pilot (2026-10-08)

`SyntheticRawPipelineDeviceTest` and `run_pipeline.py` exercise the production
raw callback, captured-buffer ownership, bounded queue, shader upload/render,
display preview, MediaCodec, MP4 output, native MPEG-TS muxer and HTTP output.
Only the isolated `.rawpipeline` application is installed/stopped by the runner.
Synthetic input makes one pooled CPU buffer copy per frame to approximate the
capture payload assembly; it does not emulate USB packet reception.

The first runs did not produce usable full-pipeline measurements. A minimal
foreground smoke test and JDWP stack inspection localized the later hang to
`Instrumentation.startActivitySync`, before any source generation or video
processing. Direct shell Activity launch succeeded. The harness now uses shell
launch with a bounded resumed-Activity check instead of that synchronous wait.
The startup smoke then passed in 2.079 seconds; the pipeline test passed in
11.448 seconds, including warmup, six-second measurement and postroll.

Successful pilot: vivo V2338A, 1920x1080 YUYV, AVC, 60 fps, full-rate preview,
12 Mbps configured bitrate, video-only, six-second measured window:

| Measurement | Result |
| --- | ---: |
| Requested / injected / decoded measured frames | 360 / 360 / 360 |
| Measured decoded throughput | 60.00 fps |
| Source skips / raw queue drops / timestamp skips | 0 / 0 / 0 |
| Source copy and marker preparation, mean / P95 | 0.897 / 1.740 ms |
| Capture timestamp to buffer release, mean / P95 | 5.457 / 8.346 ms |
| MP4 / HTTP decoded measured frames missing | 0 / 0 |
| Torn / duplicated / reordered decoded frames | 0 / 0 / 0 |
| Maximum decoded color / gray error, 8-bit units | 3 / 2 |

Both MP4 and HTTP TS fully decoded without decoder warnings. The display
screenshot also passed color checks and was visually inspected. The MP4 has
450 frames including postroll; HTTP has 386. Its final 64 postroll frames are
held by the production mux queue at shutdown; **none of the 360 measured frames
are missing**. The live encoded counter was sampled before encoder tail drain
and reads 358; use the decoded measured IDs (360), not that provisional count.
Buffer-release timing is not screen latency or remote playback latency.

This is a correctness and basic throughput pilot, not yet a realistic camera
stress benchmark: the color bars are simple to encode, reception is over ADB
forwarding rather than Wi-Fi, and decoding happens offline on the host. USB,
audio, RTMP, complex motion, long-run stability and full-pipeline 4K / other
raw formats are not covered by this successful pilot. Earlier 4K numbers in
this document remain isolated upload/render measurements.

Local pilot evidence: `build/perf-diagnostics/raw-pipeline/`
`1920x1080-f2-H264-60fps-p60-6s/validated.json`, `phases.log`,
`instrumentation.log`, MP4, TS and preview/decoded PNG files. The latest harness
omits unused ImageReader preview counters; the pilot's zero preview timing
fields are unmeasured, not zero-latency results.

To reproduce, build/install the isolated diagnostic app and AndroidTest APK
using an ignored Gradle init script overriding `defaultConfig.applicationId`
to `com.llawsxx.uvclivestreaming.rawpipeline` and excluding the unrelated UAC
test above. Then run:

```powershell
python -u diagnostics/raw-gpu-upload/run_pipeline.py --case 1920,1080,2,H264,60,6,p60
```

Restore the normal app build without the diagnostic application-ID override
after collecting evidence. Never install the diagnostic APK over the main app.

## CPU repacking vs direct upload: production-pipeline comparison

Four additional six-second YUYV cases were run on the same device with full-rate
preview, MP4 recording and HTTP TS output. 1080p uses AVC at a configured 12 Mbps;
4K uses HEVC at a configured 35 Mbps. Input is paced at 60 fps, so these tests
measure ability to sustain that rate, not maximum possible throughput.

| Resolution | CPU repack mean / P95 | Direct mean / P95 | Mean reduction | Measured CPU / direct FPS |
| --- | ---: | ---: | ---: | ---: |
| 1920x1080 | 6.594 / 10.179 ms | 5.231 / 8.174 ms | 20.7% | 60.00 / 60.00 |
| 3840x2160 | 10.772 / 12.811 ms | 7.371 / 10.332 ms | 31.6% | 60.00 / 60.00 |

Timing is **capture timestamp to captured-buffer release after render returns**.
It includes source preparation, queue/poll waiting, CPU repacking in the baseline,
GL submission and preview handling. It is not isolated CPU conversion time,
GPU completion time, codec/mux completion latency or screen/network latency.
Unlike the earlier serialized `glFinish` microbenchmark, the live pipeline
overlaps GPU/codec work; its numbers must not be compared as the same metric.

All four cases injected and decoded all 360 measured frames, with zero source
skips, raw queue drops or timestamp skips. MP4 and HTTP had no missing measured
IDs, torn marker strips, duplicate or reordered frames. First-frame decoded
color/gray samples had maximum errors of 3/2 in 8-bit units; display screenshot
checks passed. The 4K CPU decoded snapshot was also visually inspected. The local
FFmpeg emits HEVC DPB/output counters even at error log level; zero-error summaries
are now classified separately while preserving the raw logs. Unknown messages,
nonzero out-of-order/orphan counts or unequal DPB/output counts remain warnings.

`cpu-repack.gradle` is a diagnostic-only Gradle init script. It generates a
replacement converter under `app/build/`, excluding the normal converter only
for that build, and invokes the original direct-buffer native repacker on the
real render thread. It does not introduce an extra ByteArray copy or change
production source. Both modes retain the captured lease until render returns,
so their release-time measurements share an ownership boundary. A test probe
asserts whether a conversion-pool allocation occurred; requesting the wrong
build fails rather than silently mislabelling direct results as CPU repacking.

Build the isolated CPU diagnostic APP/AndroidTest with the same Gradle tasks and
ABI flag above, replacing the init script with:

```powershell
-I diagnostics/raw-gpu-upload/cpu-repack.gradle
```

After installing both isolated APKs, run:

```powershell
python -u diagnostics/raw-gpu-upload/run_pipeline.py --cpu-repack --case 1920,1080,2,H264,60,6,p60 --case 3840,2160,2,H265,60,6,p60
```

Rebuild/reinstall the direct diagnostic with only the application-ID/UAC-exclusion
init script, omit `--cpu-repack`, and repeat those same cases. Evidence is in
`build/perf-diagnostics/raw-pipeline/comparison-cpu-direct.json` and the matching
case folders (`-cpu` suffix for the baseline). The normal main APK build is
restored afterward; the formal app on the device is not stopped or overwritten.

This remains a short, simple-color-bar test without real USB, audio, Wi-Fi/RTMP
or live receiver decoding. It demonstrates basic 4K60 pipeline operation for
YUYV, not sustained worst-case camera performance or other formats.

## Frontend only: no video GPU or codec startup

`SyntheticRawFrontendDeviceTest` does not call `UsbRecorderEngine.start()` or
create a video GL/EGL renderer, encoder or encoder Surface. Test-only reflection
enables the engine's actual raw callback and exposes its actual two-frame queue.
A consumer reproduces the production empty MJPEG `poll(5)` followed by raw queue
`poll(5)`, then performs conversion and immediately releases the frame without
upload/render. Direct mode uses the production `RawVideoConverter`; CPU mode
uses the original native direct-buffer repacker and a warmed output buffer pool.
No CPU-baseline replacement APK is needed for this benchmark.

Each source frame makes one pooled full-payload copy of a prebuilt raw template.
There is no frame-ID marking in this test. Timer boundaries separate that copy,
the production callback, waiting before conversion, conversion and total time
through buffer release. The queue interval starts at callback entry, so it
includes callback admission and overlaps the separately reported callback time;
**do not sum callback and queue as independent intervals**.

All seven formats were tested at 1080p and 4K in both paths. Each paced case uses
30 warmup frames then a two-second, 120-slot, 60-fps input window. Missed source
slots are skipped rather than delivered in an artificial catch-up burst. Loss
counts are reported, not used to suppress slow cases. Each burst case uses 30
warmup plus 120 measured frames with a two-frame in-flight limit to measure
bounded-queue throughput without deliberately overflowing the queue.

The 56-case instrumentation run passed in 115.143 seconds. Source skips were
zero in all cases. All direct paced cases processed 120/120 frames at approximately
60 fps with no queue drops. CPU paced cases had the losses listed below; burst
cases had no queue drops because of the explicit producer backpressure.

### Conversion stage only

Mean milliseconds in paced-60 tests, including conversion-pool acquisition and
frame descriptor construction. I420/RGB/BGR baseline work is a copy, not color
conversion; direct work only constructs views/ownership metadata.

| Input | 1080p CPU | 1080p direct | 4K CPU | 4K direct |
| --- | ---: | ---: | ---: | ---: |
| YUYV | 3.149 | 0.252 | 12.491 | 0.165 |
| UYVY | 4.494 | 0.244 | 12.986 | 0.203 |
| NV12 | 11.601 | 0.219 | 16.044 | 0.181 |
| I420 | 1.301 | 0.172 | 3.640 | 0.205 |
| P010 | 11.354 | 0.182 | 15.795 | 0.182 |
| RGB | 2.580 | 0.171 | 5.979 | 0.146 |
| BGR | 2.818 | 0.105 | 5.411 | 0.157 |

### Complete frontend latency

Mean milliseconds from start of synthetic payload assembly to capture-buffer
release after conversion, including queue waiting, but **no GPU or encoding**.
Latency statistics include only processed frames; drops are reported separately.

| Input | 1080p CPU | 1080p direct | 4K CPU | 4K direct |
| --- | ---: | ---: | ---: | ---: |
| YUYV | 5.424 | 4.345 | 35.135 | 7.031 |
| UYVY | 7.898 | 4.080 | 40.765 | 7.765 |
| NV12 | 30.486 | 2.413 | 43.126 | 6.041 |
| I420 | 4.910 | 2.249 | 9.360 | 6.547 |
| P010 | 28.513 | 3.557 | 43.046 | 9.485 |
| RGB | 7.205 | 3.679 | 13.940 | 9.570 |
| BGR | 7.444 | 3.080 | 13.093 | 9.599 |

CPU paced losses / observed drained throughput: 1080p NV12 5/120, 57.03 fps;
1080p P010 5/120, 57.47 fps; 4K YUYV 6/120, 56.14 fps; 4K UYVY 10/120,
54.17 fps; 4K NV12 25/120, 46.79 fps; 4K P010 24/120, 47.12 fps. Other
CPU paced cases processed all 120 frames at 60 fps. Throughput uses the full
input window plus any final queue drain, not just the native conversion interval.

### Bounded burst throughput

Frames per second for the complete producer/callback/queue/conversion pipeline,
not the reciprocal of isolated conversion latency and not camera or encoded FPS.

| Input | 1080p CPU | 1080p direct | 4K CPU | 4K direct |
| --- | ---: | ---: | ---: | ---: |
| YUYV | 118.29 | 168.52 | 56.27 | 172.91 |
| UYVY | 116.92 | 169.55 | 55.36 | 167.23 |
| NV12 | 70.42 | 174.54 | 48.62 | 172.66 |
| I420 | 165.05 | 172.48 | 128.38 | 172.71 |
| P010 | 64.32 | 171.11 | 47.17 | 171.48 |
| RGB | 145.01 | 166.98 | 116.64 | 175.45 |
| BGR | 143.46 | 167.36 | 111.20 | 172.81 |

The unchanged empty-MJPEG polling contributes a roughly 5-ms wait each consumer
iteration and limits raw frontend burst throughput. CPU conversion plus that
wait can exceed the 16.67-ms budget even without a GPU. This benchmark deliberately
preserves it rather than bypassing a production scheduling cost.

Do not subtract these measurements from the earlier GPU/codec pipeline numbers.
Removing the video GPU/encoder changes workload and scheduling, and CPU/memory
frequencies are not fixed. Short paced vs burst tests also show substantially
different copy/conversion costs. Test bookkeeping is present. These results
describe this isolated setup, not a universal inability of CPU repacking to
reach 4K60 under the previous full-pipeline workload.

Physical USB reception, kernel/libusb packet handling and the capture-side JNI
handoff are not measured without hardware; the one-copy synthetic payload
assembly approximates only the application-side assembly copy. No image/codec
validation is claimed by this frontend-only test.

Run against the direct isolated diagnostic APP/AndroidTest:

```powershell
python -u diagnostics/raw-gpu-upload/run_frontend.py --formats 2,3,5,6,7,4,9 --seconds 2 --burst-frames 120
```

Evidence: `build/perf-diagnostics/raw-pipeline/frontend-f2-3-5-6-7-4-9/`
`results.json` contains all mean/P95 stage times, throughput and loss counters;
`instrumentation.log` contains the passing test. `pilot-partial.json` preserves
the earlier trial that aborted on queue loss before loss became a reported
benchmark metric. The production queue/polling implementation was not changed.

## Active-format waiting optimization (historical baseline)

The event-driven change in the next section supersedes this implementation.
These measurements remain here as a comparison baseline.

After the preceding baseline, preview and recording were changed to share
`VideoFrameWaitPolicy`: RAW uses MJPEG `poll(0)` and raw `poll(5)`; MJPEG uses
MJPEG `poll(5)` and raw `poll(0)`. The capture callback publishes the latest
format's policy through a volatile field. Each consumer iteration snapshots
one immutable policy, so format switching cannot produce two nonblocking waits.
Queued completions from the previous format can still be drained. Switching
during an already-blocking call may retain that one bounded 5-ms wait.

The **active** queue continues to block for up to 5 ms when empty, waking early
when its input/result becomes available. This removes waiting on the inactive
format, not all waiting; it is not a dual-queue nonblocking spin loop. Shutdown
still clears the running flag and interrupts/joins the consumer as before.

### Idle resource check

On-device empty-input checks use the actual MJPEG pool and raw blocking queue,
with the same shared policy as production. Two seconds per policy:

| Policy | Iterations | Consumer thread CPU time | Single-core CPU fraction |
| --- | ---: | ---: | ---: |
| RAW | 393 | 16.994 ms | 0.85% |
| MJPEG | 361 | 84.657 ms | 4.23% |

These are CPU-time/wall-time ratios for the consumer thread, not whole-app or
whole-device CPU percentages. There is periodic-wakeup overhead, not zero
resource use, but no busy spin. The test also bounds idle iterations and CPU
time; every policy is unit-tested to retain exactly one positive wait timeout.

### Same-condition frontend retest

The previous seven-format, two-resolution, paced/burst matrix was repeated with
the same two-second windows, 120 measured frames and 30 warmup frames. All 56
cases processed all 120 measured frames with zero source skips and queue drops.
Video GPU and codecs were not started. Direct-path mean total frontend latency:

| Input | 1080p before / after | 4K before / after |
| --- | ---: | ---: |
| YUYV | 4.345 / 2.348 ms | 7.031 / 5.628 ms |
| UYVY | 4.080 / 2.105 ms | 7.765 / 5.575 ms |
| NV12 | 2.413 / 1.572 ms | 6.041 / 3.090 ms |
| I420 | 2.249 / 1.742 ms | 6.547 / 3.450 ms |
| P010 | 3.557 / 2.609 ms | 9.485 / 4.828 ms |
| RGB | 3.679 / 2.794 ms | 9.570 / 9.009 ms |
| BGR | 3.080 / 2.635 ms | 9.599 / 7.599 ms |

YUYV callback-entry-to-conversion waiting decreases from 2.321 to 0.428 ms at
1080p and from 1.752 to 0.354 ms at 4K. CPU repacking cases that lost paced frames
in the earlier baseline also delivered all 120 frames after this change.
That is a short-test result, not a long-running or fixed-frequency guarantee.

The raw frontend burst limit is no longer approximately 170 fps: repeated-buffer
direct cases observe approximately 2513-5017 fps at 1080p and 701-1583 fps at 4K.
Those are synthetic CPU/queue throughput measurements with hot, reused payloads,
**not USB, rendering or encoding FPS**. Removing idle waits also changes CPU
utilization/frequency; do not attribute every change in copy/conversion time
solely to the nominal 5-ms timeout.

### Full-pipeline regression

The real preview/GPU/codec/MP4/HTTP YUYV cases were also repeated for six seconds
at 1080p AVC and 4K HEVC. Both delivered all 360 measured frames at 60 fps, with
zero source skips, queue drops or timestamp skips. MP4 and HTTP decoded IDs,
color samples and display screenshot checks passed. Capture-to-buffer-release
mean/P95 is 3.296/4.668 ms at 1080p and 5.031/5.592 ms at 4K; these timings include
render submission but are not codec-completion or remote-playback latency.

Four new waiting-policy unit tests and eleven existing MJPEG pool tests passed.
The standalone preview uses the same policy, but only the recorder path was
device-benchmarked. No capture hardware or complex-motion validation was added.

Evidence stays separate from the baseline:
`build/perf-diagnostics/raw-pipeline/frontend-f2-3-5-6-7-4-9-active-wait/`
contains `results.json`, `idle.json` and `instrumentation.log`;
`build/perf-diagnostics/raw-pipeline/active-wait-pipeline/` contains the full
pipeline regression artifacts. Reproduce the frontend/idle matrix with:

```powershell
python -u diagnostics/raw-gpu-upload/run_frontend.py --formats 2,3,5,6,7,4,9 --seconds 2 --burst-frames 120 --tag active-wait
```

## Event-driven queue waiting (2026-10-08)

Preview and recording now share a `QueueWakeSignal` instead of a format-dependent
5-ms timeout. The consumer snapshots its revision **before** checking the decoded
MJPEG and raw queues with nonblocking polls. If both queues are empty, it waits
indefinitely with `Object.wait()`. Raw admission and MJPEG completion signal with
`notifyAll()`; a revision counter prevents a signal between the empty check and
the wait from being lost. The wait uses a predicate loop to tolerate spurious
wakeups. Stop closes the signal immediately, independently of later native/codec
cleanup. Consumers never hold the wake monitor while accessing the decode pool,
so the completion callback does not introduce a reverse lock ordering.

Both queues are checked on every event, so changing the input format cannot
strand a consumer waiting on the previous format. MJPEG decode failure and input
overflow also publish completion events; sequence ordering, bounded caches and
capture/output lease ownership are unchanged. The old `VideoFrameWaitPolicy`
and its tests were removed, not left as unused production code.

### Queue audit

| Queue / worker | Previous empty-input wait | Current behavior |
| --- | --- | --- |
| Recorder and standalone preview video | Active queue timed poll, 5 ms | Shared revision signal; indefinite wait |
| Four MJPEG input workers | Timed poll, 100 ms per worker | `input.take()`; close interrupts workers |
| USB audio DSP | Timed poll, 10 ms | Revision signal; offer and close wake worker |
| USB audio monitor | Timed poll, 20 ms | `queue.take()`; toggles and close interrupt worker |
| HTTP upload, empty queue | Monitor timeout, 1 s | Indefinite monitor wait; enqueue/close notify |
| HTTP live ring client, no new chunks | Condition timeout, 1 s | Indefinite condition wait; append/close signal |
| AAC input/output worker | PCM poll, 10 ms, plus codec dequeues | Retained: drains pending codec output and feeds EOS without PCM |
| RTMP | Condition timeout, 250 ms | Retained: reconnect deadlines and detection of receiver failure |
| HTTP failed-upload retry | Monitor timeout, 1 s | Retained: network retry/backoff, not empty-queue polling |
| Color LUT worker / native UVC callback | Already `take()` / `pthread_cond_wait()` | Already event-driven; unchanged |
| Native libusb event loop | Event wait, up to 200 ms | Retained: I/O event handling and checking shutdown |

Audio DSP close still drains accepted PCM and flushes delayed DSP samples. Offer
and close share a short admission lock so packets cannot be accepted after close.
When a close wake races with an accepted packet, the worker rechecks the queue
rather than treating the closed signal as permission to discard queued PCM.
Tests cover immediate offer/close 100 times, delayed tails, playback toggles,
MJPEG failure/drop wakeups, and signal/close/interrupt behavior. The complete JVM
suite has 172 tests: 170 passed, two existing skips, zero failures.

### Idle consumer CPU retest

Ten seconds of empty input per label, using the actual decode pool (four workers)
and a raw queue. Only the controlling test thread sleeps for ten seconds; the
consumer has no periodic timer. The measured CPU fraction is consumer thread
CPU time divided by wall time, **not whole-app CPU**.

| Label | Previous single-core CPU | Current single-core CPU | Current iterations / CPU time |
| --- | ---: | ---: | ---: |
| RAW | 0.8475% | 0.003883% | 1 / 0.388333 ms |
| MJPEG | 4.2263% | 0.001878% | 1 / 0.187813 ms |

The one iteration checks the empty queues and blocks until test shutdown. These
figures include initial checks and the shutdown wake, not zero-work promises.
There is no JPEG data in this **idle** test; real JPEG input is tested separately.

### Raw frontend regression

The same seven formats, two resolutions, CPU/direct paths and paced/burst modes
were repeated: all 56 cases processed 120 measured frames, with zero source skips
and queue drops. There are 30 warmup frames per case. Direct paced-path mean
frontend latency (synthetic capture-buffer copy, callback, queue and descriptor
conversion; **no video GPU or encoder**) is:

| Input | 1080p | 4K |
| --- | ---: | ---: |
| YUYV | 2.351 ms | 5.734 ms |
| UYVY | 2.199 ms | 6.103 ms |
| NV12 | 1.643 ms | 5.625 ms |
| I420 | 2.050 ms | 4.179 ms |
| P010 | 3.316 ms | 7.711 ms |
| RGB | 2.962 ms | 8.431 ms |
| BGR | 2.848 ms | 9.622 ms |

Direct paced callback-entry-to-conversion time is approximately 0.23-0.55 ms;
this is scheduler/queue latency, not the execution cost of `queue.offer()`.
YUYV total latency is approximately unchanged relative to active-format waiting.
Some other cases are slower, especially in the synthetic buffer-copy stage;
CPU frequency, cache state and thermal state are not locked. The reliable result
is removal of idle timed wakeups without queue loss, **not an across-the-board
throughput or latency improvement**. The 4K CPU BGR paced case finishes at
59.88 fps over its measured window while retaining all 120 frames.

Evidence: `build/perf-diagnostics/raw-pipeline/frontend-f2-3-5-6-7-4-9-event-wait/`
contains `results.json`, `idle.json` and `instrumentation.log`. Reproduce with:

```powershell
python -u diagnostics/raw-gpu-upload/run_frontend.py --formats 2,3,5,6,7,4,9 --seconds 2 --burst-frames 120 --tag event-wait
```

### Real JPEG input and native MJPEG decoding

`MjpegEventQueueDeviceTest` generates actual baseline JPEG color bars on-device,
using `Bitmap.compress(JPEG, 95)`, before the measured feed. It supplies native
direct capture buffers through the real recorder callback, uses the production
four-worker MJPEG pool/native decoder and consumes completions through the
actual event signal. This is no longer a fake-decoder or empty-pool test.

Both 1080p and 4K process 30 warmup plus 120 measured frames, with a nominal
60-fps paced feed and two in-flight permits. All decoded frames retain timestamp
order and pass Y/U/V samples at the centers of all eight bars (BT.601 full-range,
tolerance five). Both have zero decode failures, input drops and sequence skips.

| JPEG input | Payload size | Callback to decoded mean / P95 | Mean native decode, including warmup |
| --- | ---: | ---: | ---: |
| 1920x1080 | 15,056 bytes | 12.022 / 16.764 ms | 11.859 ms |
| 3840x2160 | 53,442 bytes | 27.630 / 36.952 ms | 26.653 ms |

These latencies include native decoding and event delivery, but **exclude GPU,
encoding, muxing and networking**. Each resolution repeats one pre-generated,
simple color-bar JPEG; it does not validate camera transport, complex-motion
JPEG sizes, missing JPEG headers, or MJPEG full-pipeline encoding. Multiple
workers can overlap frame decoding; per-frame decode latency is not frame period.
The decode average comes from pool diagnostics: its interval also includes JPEG
geometry parsing, output-buffer acquisition and captured-buffer release, rather
than timing only the native decoder call.

Evidence: `build/perf-diagnostics/raw-pipeline/event-wait-mjpeg/` contains
`results.json` and the passing instrumentation log. Run the isolated installed
test with:

```powershell
& 'D:\AndroidSDK\platform-tools\adb.exe' -t 1 shell am instrument -w -r -e class 'com.llawsxx.uvclivestreaming.recording.MjpegEventQueueDeviceTest#nativeDecodeAndEventWakePreserveRealJpegColors' com.llawsxx.uvclivestreaming.rawpipeline.test/androidx.test.runner.AndroidJUnitRunner
```

### Full-pipeline YUYV regression

The recorder was tested with the same synthetic YUYV capture callback through
GPU rendering, on-screen preview, hardware video codec, MP4 output and HTTP TS.
Both 1080p AVC and 4K HEVC delivered all 360 measured frames over six seconds
(60 fps), with zero source skips, raw queue drops or timestamp skips. Final host
decoded frame IDs are used for encoded-frame counts; a codec counter sampled
before drain can still lag one asynchronous output frame.

| Pipeline | Capture-buffer release mean / P95 | Measured encoded frames / FPS |
| --- | ---: | ---: |
| 1080p AVC | 3.423 / 5.141 ms | 360 / 60 |
| 4K HEVC | 5.032 / 6.273 ms | 360 / 60 |

Both display screenshots pass color samples. MP4 and HTTP decoded frames have
zero tearing, duplicate/reordered IDs or missing measured IDs; maximum color
error is three and gray error two. The live HTTP stream does not preserve all
postroll frames (64 trailing MP4 IDs are not in HTTP), but contains **all measured
frames**. This is live playback, not a reliable archival upload guarantee.

Capture-buffer release includes render submission; it is **not** hardware encoder
completion or remote-playback latency. Real camera/USB capture and audio encoding
are not exercised in these synthetic video-only regressions. Standalone preview
shares the implementation but is not separately device-benchmarked.

Evidence: `build/perf-diagnostics/raw-pipeline/event-wait-pipeline/` contains each
case's validated JSON, MP4, TS, preview PNG and instrumentation log. The normal
application-ID Debug APK build was restored after installing the isolated test
package; the formal app was neither overwritten nor stopped (PID 16630 unchanged).

## Direct YUV encoder input (2026-10-08)

The saved, default-off `YUV 直接输入编码器（实验）` setting selects a real
`COLOR_FormatYUV420Flexible` MediaCodec input image rather than an encoder
Surface. Native code writes the encoder's own Y/U/V planes using their reported
row/pixel strides; input images and captured/decoded leases are released after
submission. EOS uses an input-buffer flag, not Surface EOS.

The recording path is `capture/JPEG decode -> native YUV420 packing and fused
matrix/range conversion -> MediaCodec`. It never allocates an RGB intermediate
for a YUV source. Preview remains independent and still converts YUV to RGB for
display. Matching-matrix/range I420/NV12 use copy paths; matching YUYV/UYVY use
NEON deinterleaving and vertical chroma averaging on arm64. Planar 420 matrix/range
conversion also uses NEON, with scalar tails and fallback for other layouts.
MJPEG full-range BT.601 can therefore feed limited-range BT.709 without ignoring
either the input interpretation or the output color request.

YUYV/UYVY, NV12, I420, decoded JPEG YUV and P010/YUV10 are accepted; ten-bit input
currently converts to **8-bit SDR** encoder input. RGB/BGR cameras can use the
same input-plane writer, but naturally already have RGB source pixels. Test
cards, active recording grading, odd dimensions, HDR, BT.2020 output and
non-SDR transfer requests fall back with a notice. Unsupported codecs/input
images also fall back at startup. Enabling recording grading during an active
direct-YUV session is rejected with a notice rather than silently bypassed.

### Reproduction and validation

Build isolated APKs without replacing the production application:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest '-Pandroid.injected.build.abi=arm64-v8a' -I diagnostics/raw-gpu-upload/pipeline.gradle
& 'D:\AndroidSDK\platform-tools\adb.exe' -t 1 install -r -t app/build/intermediates/apk/debug/app-debug.apk
& 'D:\AndroidSDK\platform-tools\adb.exe' -t 1 install -r -t app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk
python -u diagnostics/raw-gpu-upload/compare_yuv.py --rounds 2 --seconds 6 --tag yuv-direct-neon-final
```

The paired runner alternates Surface-first and YUV-first between rounds. The
defaults cover MJPEG, YUYV, NV12 and I420 at 1920x1080 AVC and 3840x2160 HEVC,
with a real displayed 60-fps preview, the same codec/bitrate/color settings,
warmup, six seconds of measured 60-fps input, MP4 and live HTTP MPEG-TS output.
Use `--formats` to select other inputs or `--low-preview` for 5-fps display.
`--start-round 2 --rounds 1` appends the reversed-order round to existing evidence.
The existing single-case runner also supports `--yuv-input` and explicitly fails
if the encoder silently falls back to Surface.

`encoder_delivery_mean_ms` measures monotonic source timestamp to codec output
dequeue, before MP4 timestamp rebasing, mux reordering or HTTP buffering. It
includes source scheduling/copy, JPEG decoding when applicable, render/input
queueing and hardware encoding; it is **not pure codec execution time** or
remote-playback latency. The old normalized-output-PTS `mux_mean_ms` measurement
is removed because output attachment/keyframe rebasing introduced a false offset.
Input wait, native conversion and queue submission are separately measured for
the direct path. Raw capture-buffer release includes preview/render submission;
MJPEG capture-buffer release ends at JPEG decoding and does **not** measure the
encoding path. Host-decoded measured frame IDs, rather than a counter read before
asynchronous drain, determine the final encoded frame rate.

The runner checks MP4 and HTTP frame IDs, top/bottom tearing, duplicates/order,
color bars, gray samples and decoder warnings. It records instrumented preview
checks, source skips, queue drops, timestamp skips and JPEG decode errors. A
pair is marked failed if either path loses any measured frame or fails these
checks. HTTP may omit trailing postroll packets because it is live output, not
reliable archival upload. This synthetic video-only test does not cover real
USB transport, camera-specific JPEGs, audio, complex motion or maximum unpaced
encoder throughput; 60 fps is a paced target, not a throughput ceiling discovery.

Focused tests are `YuvEncoderTransformTest` (matrix/range/10-bit/policy) and
`YuvEncoderConverterDeviceTest` (padded/strided planes, scalar-reference matching,
NV12/NV21 chroma order, NEON tails, packed 422 vertical averaging, undersized
buffers and full-height JPEG chroma). Neither test uses RGB conversion as a
substitute for the YUV encoding path.

### Paired device results

On device model V2338A, both paths used `c2.qti.avc.encoder` for 1080p and
`c2.qti.hevc.encoder` for 4K. Each case was repeated twice, with reversed path
order in the second round. Values below are arithmetic means of the two runs.
All **32 pipeline executions** preserved all **360 measured frames** per run
at 60 fps: 11,520 measured MP4 frames in total, with all measured IDs also present
in HTTP output. There were no source skips, raw queue drops, timestamp skips,
JPEG decode failures/drops/sequence skips, preview errors, tearing or reordered
IDs. Decoded color error was at most three and gray error at most two.

| Input | Encoder | RGB/Surface delivery mean | Direct YUV delivery mean | Delivery reduction | YUV conversion mean |
| --- | --- | ---: | ---: | ---: | ---: |
| MJPEG | 1080p AVC | 29.821 ms | 26.605 ms | 10.8% | 1.203 ms |
| YUYV | 1080p AVC | 28.710 ms | 24.938 ms | 13.1% | 0.766 ms |
| NV12 | 1080p AVC | 29.295 ms | 24.989 ms | 14.7% | 0.322 ms |
| I420 | 1080p AVC | 29.405 ms | 25.044 ms | 14.8% | 0.351 ms |
| MJPEG | 4K HEVC | 29.806 ms | 26.929 ms | 9.7% | 4.399 ms |
| YUYV | 4K HEVC | 23.510 ms | 18.777 ms | 20.1% | 2.650 ms |
| NV12 | 4K HEVC | 22.154 ms | 16.648 ms | 24.9% | 1.104 ms |
| I420 | 4K HEVC | 22.538 ms | 16.574 ms | 26.5% | 1.145 ms |

The benefit here is lower source-to-encoded-output latency, **not increased
measured FPS**: both pipelines meet the fixed 60-fps target. Nor is direct YUV
zero-copy or universally faster in every stage. Raw capture-buffer release gets
slower because CPU packing, input-buffer submission and preview still hold the
source lease. For example, 4K I420 release mean rises from 4.899 to 7.487 ms even
though encoded delivery improves from 22.538 to 16.574 ms. JPEG input leases end
at decoding, so their release timing is not an encoding-speed comparison.

CPU/GPU frequencies were not locked; the device thermal status moved from 0 at
the beginning to 1 at the end. These are observations for this device and simple
synthetic content, not a universal speed guarantee. Native converter device
tests pass all six tests; the complete JVM suite passes 177 tests across 29 suites.
The normal application-ID Debug APK is rebuilt after diagnostic installation;
the production app is not overwritten/stopped (PID 16630 remains unchanged).

Evidence: `build/perf-diagnostics/raw-pipeline/yuv-direct-neon-final/` contains
`comparison.json`, two rounds of per-case validated JSON, MP4, HTTP TS, preview
PNG and instrumentation logs, plus thermal/battery snapshots. Build/test logs
are `build/perf-diagnostics/raw-pipeline/yuv-formal-build.log` and
`build/perf-diagnostics/raw-pipeline/yuv-native-final.log`.
