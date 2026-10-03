# Encoded H.26x color metadata rewriting

The native rewriter is adapted from
`D:/AndroidProject/llawsxxSafeCamera/app/src/main/cpp/h26x_vui_rewriter.c`.
It supports raw SPS, Annex B, 1-4 byte NAL lengths, avcC and hvcC.
The port validates complete length-prefixed buffers before looking for Annex B
delimiters, avoiding misclassification of lengths such as `00 00 01 00`.
It also avoids an undefined shift when parsing malformed Exp-Golomb data.

The USB screen exposes independent Range, Primaries, Transfer and Matrix choices.
Rewriting is off by default; its initial selected values are TV/BT.709.
Each field can preserve the original SPS value. Settings are stored for later
sessions and locked while the shared encoder runs.

`UsbRecorderEngine` rewrites CSD before caching the video format and rewrites
every encoded video sample before the output router. All outputs share that
same rewritten sample. Non-SPS NALs and timestamps are preserved.
When rewriting is enabled, inaccurate Android format color tags are removed,
so the rewritten SPS supplies color information without conflicting container
tags. Android's color-standard and SDR-transfer enums cannot describe every
independent ISO primaries/matrix/transfer combination.

Validation on Windows:

- Native tests cover H.264/HEVC without VUI, replacement of existing VUI,
  partial field preservation, Annex B, NAL lengths 1-4, avcC/hvcC, unchanged
  video NALs, and malformed SPS rejection.
- Real one-second H.264 and HEVC streams were rewritten from TV/BT.709 to
  Full/BT.2020 primaries/PQ transfer/BT.2020 non-constant matrix. FFprobe read
  `pc`, `bt2020`, `smpte2084`, and `bt2020nc` for both codecs.
- FFmpeg decoded both original and rewritten streams. All 30 frame hashes
  matched for each codec, confirming that rewriting changed metadata only.
- Android build and host Kotlin unit tests passed. Phone installation and
  device execution were not performed for this change.

Run the native test from the repository root:

```powershell
New-Item -ItemType Directory -Path build/vui-native -Force | Out-Null
gcc -std=c11 -Wall -Wextra -Werror -I app/src/main/cpp app/src/main/cpp/h26x_vui_rewriter.c app/src/test/native/h26x_vui_rewriter_test.c -o build/vui-native/h26x_vui_rewriter_test.exe
./build/vui-native/h26x_vui_rewriter_test.exe
```
