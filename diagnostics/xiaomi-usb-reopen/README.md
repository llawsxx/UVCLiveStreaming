# Xiaomi / MS2130 reopen investigation (2026-10-05)

Phone: Xiaomi 24031PN0DC (`aurora`), wireless ADB transport 78.
Capture card: MACROSILICON USB3.0 MS2130, VID:PID `345f:2130`.
Video: interface 1, Bulk IN endpoint `0x83`, packet size 1024.
Audio: interface 3, ISO IN endpoint `0x82`, 48 kHz stereo PCM16.
The link reports SuperSpeed (kernel USB speed enum 5).

## Reproduction on the installed release build

- USB permissions remain granted and the device remains enumerated after stop.
- In the failed state, video negotiation and eight 15360-byte receive request
  submissions report success. Audio PCM continues at approximately 192000 B/s.
  No UVC receive/assembly or video callback logs appear.
- Disabling audio and reopening does not restore the video.
- With audio disabled, the user physically reconnects the card. The first
  preview works, but stopping and reopening it produces a black screen again.
- The successful preview logs approximately 60 fps and 15 MB/s, with zero
  transfer/submission errors and zero missing-SOI/failed MJPEG decodes.
  At 18:59:16 the second open negotiates the same 1920x1080 MJPG 60 mode and
  submits receive requests, but stops producing video receive logs.

This localizes the issue to video restart, independently of UAC or rendering
configuration. The old logs do not distinguish endpoint timeout from other
completion errors; they do not prove the exact device/host failure mechanism.
Kernel logs, sysfs interface driver state, and process fd inspection are denied
to ADB shell on this phone. Input injection is also denied; the user performs
the preview steps manually.

## Correction and diagnostics

Teardown explicitly selects alternate setting 0. Previously the next open
only selected an alternate setting for ISO video; Bulk video skipped this.
An initial candidate explicitly initialized the single Bulk alternate setting
before VS COMMIT. The user installed it and repeated the test: the first open
received 73 frames, but subsequent opens completed all eight receive requests
with `LIBUSB_TRANSFER_ERROR`, zero bytes and zero frames. Interface preparation
and release both returned success. SET_INTERFACE alone therefore did not fix it.

The final change also clears the Bulk endpoint halt/toggle and host endpoint
state with `libusb_clear_halt`, before COMMIT and before submitting requests.
Errors abort the open and release the claimed interface. Low-level bus errors
are logged with the original kernel URB status, rather than only the generic
libusb transfer result.

Added warning-level libusb logging, the first completion and first four
abnormal completions, and a stop summary (callbacks, received bytes, frames,
transfer/submission errors, release result). Logs remain bounded during normal
streaming. No USB device reset or automatic reconnect is introduced.

## Device validation

The user installed the second build and confirmed three consecutive previews
without unplugging. At 19:09:15, 19:09:22, and 19:09:29, interface preparation
and endpoint clear both returned 0, and the first video completion returned
`LIBUSB_TRANSFER_COMPLETED`. The first two stops reported 87 and 153 frames,
with 1637523 and 2852733 received bytes respectively and no transfer/submit
errors. The third preview continued producing frames at approximately 30 fps
(the user's current setting), also with zero transfer/submit errors.

This verifies recovery on the tested Xiaomi/MS2130 combination. Clearing the
endpoint fixed the observed restart failure, but it does not distinguish a
device-side endpoint state problem from a host-controller state problem. No
kernel URB error code was captured before the working build; the exact bus
error subtype remains unknown.

The user then enabled audio and confirmed consecutive previews also worked.
Audio-enabled stops at 19:10:32 and 19:10:51 reported 1565 and 1017 video frames,
both with zero transfer/submit errors and successful release. The next startup
at 19:10:52 continued receiving both video and 48000 Hz stereo PCM16. Audio
bytes increased by approximately 960000 every five seconds; a nonzero PCM peak
was observed at 19:11:02. The audio-enabled test used an uncompressed 60 fps
mode, additionally exercising the same endpoint recovery outside MJPEG.

ARM64 debug build and `git diff --check` passed. No existing app was stopped or
installed by ADB; the user performed the installations and preview controls.

Raw logs are saved locally under ignored
`build/perf-diagnostics/xiaomi-usb-reopen/`.
