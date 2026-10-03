# smart_rtmpd immediate-disconnect fix (2026-10-03)

The previous `RtmpStreamSink` followed part of FFmpeg's publishing sequence but
was not a complete port of `D:/develop/FFmpeg/libavformat/rtmpproto.c` and
`rtmppkt.c`. Its protocol implementation contained these concrete defects:

- Outgoing messages used 4096-byte chunks before Set Chunk Size was sent.
  RTMP starts with a 128-byte chunk size; even the connect command could exceed it.
- Incoming messages required fmt=0 and read the entire message body directly,
  ignoring continuation chunks, compressed headers, per-CSID state and the
  server's Set Chunk Size. Server replies could therefore be misparsed.
- Extended timestamps were omitted on outgoing messages.
- AMF replies were searched as strings; stream IDs were inferred by scanning
  arbitrary bytes for numbers and then media messages always used stream ID 1.
- No releaseStream/FCPublish, no publish acceptance check, and tcUrl included
  the stream key instead of identifying the application.
- Publishing stopped reading the server, so ping requests, acknowledgement
  windows, server errors and EOF went unhandled.
- A socket that failed during opening could leak; stopping did not close a
  blocking socket until after joining the worker.

`RtmpProtocol.kt` now assembles inbound chunks and serializes outbound messages.
`RtmpConnection.kt` handles commands and a continuous control-message receiver.
`RtmpStreamSink.kt` retains the media packaging and fixed-bitrate bounded queue.

Validation:

- Gradle unit tests and arm64 debug APK build passed.
- 7 RTMP packet/AMF tests and 3 simulated-server connection tests passed.
- The opt-in real-server test also passed against smart_rtmpd on localhost:1935,
  receiving `NetStream.Publish.Start` for a separate `codex-rtmp-probe` key.
- Installed the APK on vivo V2338A via adb.
- Phone published to the configured `live/stream` address; adb logs show
  `NetStream.Publish.Start`. Server logs show successful H.264 and AAC setup.
- ffprobe read 3 seconds of the actual stream: H.264 1280x720 (90 packets),
  AAC 48000 Hz stereo (141 packets).
- ffmpeg decoded 3 seconds of both streams to the null output with exit code 0
  and no error messages. This is a short functional check, not a long-term
  stability test or a validation of HEVC compatibility with this server.

The implementation covers the app's plain RTMP publishing path. FFmpeg's full
protocol family, RTMPS/RTMPE, Adobe authentication and complex digest handshake
are not ported. The server's “recv had been close by remote” message alone does
not identify which protocol error caused an earlier connection to close.

The optional server test can be rerun in PowerShell:

```powershell
$env:RTMP_DIAGNOSTIC_URL = 'rtmp://127.0.0.1:1935/live/codex-rtmp-probe'
.\gradlew.bat :app:testDebugUnitTest --tests '*RtmpConnectionTest*' --offline --no-daemon
```
