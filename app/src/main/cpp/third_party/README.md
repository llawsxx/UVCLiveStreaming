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

The local libuvc streaming control code matches nominal integer fps using the
same nearest-integer rule as the mode list (e.g. 59.94 advertises 60). Continuous
intervals support selecting the default with fps=0 and a zero step without a
division/modulo by zero. Probe SET/GET failures are propagated to the caller.
Control transfers shorter than the minimum 26-byte probe block are rejected.
The local `uvc_get_frame_desc_for_ctrl` API resolves a frame using the selected
VideoStreaming interface as well as its format/frame indices.
The USB bridge can explicitly probe a custom interval after identifying a real
format/frame descriptor; it rejects a device response that changes the requested
interval, apart from one 100 ns tick of quantization.
