# USB capture dependencies

- `libusb` 1.0.30 and `libuac` were copied from
  `D:\AndroidProject\LiveAudioProcess\app\src\main\cpp\third_party`.
  The copied `libuac` includes UAC2 parsing and streaming support.
- `libuvc` was imported from `libuvc/libuvc` at commit
  `d07de4fa23905ee8cac06f5d24b2a03d42a3b363`.
- `libjpeg-turbo` was imported from `libjpeg-turbo/libjpeg-turbo` at commit
  `03c9a9d795c08ab06d625e5fc3bf0ae8b26f9652`.

The original license files are included with each library. `libjpeg-turbo`
decodes MJPEG frames, including devices that omit Huffman tables. The USB
capture bridge opens a `UsbDeviceConnection` file descriptor and uses libusb
through libuvc and libuac; it does not scan device nodes directly.
