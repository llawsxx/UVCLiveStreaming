# U4 4K60 audio diagnosis — 2026-10-03

Device: vivo V2338A, wireless adb transport 1. Capture card: `0bda:0102`, `/dev/bus/usb/001/002`.

## Cause and fix

Android enumerated an ALSA input and microphone permission was granted. The application skipped audio with `USB device exposes no UAC capture route`.

The raw UAC1 descriptors describe input terminal 1 (`0x0201`) -> selector unit 4 -> feature unit 3 -> USB streaming output terminal 2 (`0x0101`). libuac did not parse selector units, so the topology ended at feature unit 3. Added bounded UAC1/UAC2 selector parsing and source traversal, and corrected branch attachment for units with multiple sources. Debug builds now include libuac logs.

AudioStreaming interface 3, alternate setting 1: endpoint `0x83`, isochronous IN, packet limit 208 bytes, interval 4. PCM descriptor: 48000 Hz, 2 channels, 16 bits, 2-byte subframes.

## Device validation

`gradlew.bat :app:assembleDebug --offline --no-daemon -Pandroid.injected.build.abi=arm64-v8a` passed. Installed the actual Gradle listing output, `app/build/intermediates/apk/debug/app-debug.apk`, using `adb install -r -t`. Also copied the final APK to `app/build/outputs/apk/debug/app-debug.apk`, which previously contained an older build.

At 21:58:52, process 8669 reported 25,045 nonempty PCM packets, 4,808,892 bytes and 2,449 Java callback batches. Data increased by approximately 960,000 bytes every 5 seconds, consistent with 48000 * 2 * 2 bytes/sec. The observed cumulative sample peak was 156/32768; the UI remained at its -60 dBFS floor. This confirms PCM reception and JNI delivery, but does not establish normal audible HDMI program audio. The current preview callback measures level; it has no AudioTrack speaker playback.

The initial empty packet did not prevent subsequent PCM reception. A temporary mute query before interface claim caused `LIBUSB_ERROR_BUSY` and was removed from the final code. No mute state conclusion was drawn from that query.

Evidence: `descriptors.txt`, `before.log`, `route-fixed.log`, `pcm-received.log`, `ui-after.xml`. Native diagnostics report received bytes, sample peak and Java batches every 5 seconds and at stream stop.
