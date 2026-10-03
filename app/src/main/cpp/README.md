# UVCLiveStreaming MPEG-TS native muxer

`mpegts_muxer.c` is a small, project-local extraction and adaptation of the
transport-stream mechanisms in FFmpeg's `libavformat/mpegtsenc.c`. It does not
link against or bundle FFmpeg.

The extracted implementation retains FFmpeg's LGPL-2.1-or-later license. The
license text is available in this repository at
`reference/FFmpeg/COPYING.LGPLv2.1`. The source copyright and license notice is
also kept at the top of the derived C files.

Supported elementary streams:

- H.264/AVC (PMT stream type `0x1b`)
- H.265/HEVC (PMT stream type `0x24`)
- AAC in ADTS (PMT stream type `0x0f`)

Audio-only programs use the AAC PID as the PCR PID.

The muxer writes PAT/PMT, PES, PTS/PCR, random-access indicators, continuity
counters and fixed 188-byte TS packets. It intentionally excludes the rest of
FFmpeg's format, metadata, subtitle, constant-mux-rate and DVB service support.
