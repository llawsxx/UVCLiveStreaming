# 自定义 USB 采集模式

设备 Tab 的采集模式菜单新增“自定义分辨率／帧率／格式…”。支持 1～3840 的宽度、1～2160 的高度、1～240 的帧率（含 59.94、29.97 等小数），以及当前 RGB 渲染链路支持的 MJPG、YUYV、UYVY、RGB、BGR、NV12、I420、P010。YUYV／UYVY／NV12／P010 需要偶数宽高。自定义参数和是否选择该模式会保存；扫描设备模式列表不会把自定义选项清除。

UVC 通过 format/frame 描述符索引选择格式和分辨率，Probe 中只有帧间隔，没有可任意填写的宽高。自定义模式必须找到设备实际暴露的格式／分辨率描述符；不能凭用户输入创造隐藏的格式或分辨率。连续帧率模式通常只在菜单列出默认帧率，因此手动输入其他帧率有实际作用。

自定义协商可寻找各个 VideoStreaming 接口中的匹配模式，并通过新增的 `uvc_get_frame_desc_for_ctrl` 按接口号、格式索引、帧索引获取确切描述符，避免不同接口使用相同索引时取错模式。现有列表枚举仍只读取第一个 VideoStreaming 接口。

自定义协商先获得匹配描述符的控制块，然后把帧率换算成 100 ns 单位的间隔，再执行 Probe SET/GET。整数 60／30 优先使用描述符中对应的 NTSC 间隔；指定小数时保留该帧率。未声明的间隔仅在用户选择自定义模式时尝试。设备返回的间隔与请求相差超过一单位，或 USB 控制传输失败／返回不足 26 字节，均报错，不把设备返回的其他帧率当作成功。采集和时间戳平滑保留 Double 帧率；编码器帧率资源提示采用四舍五入的整数。

普通菜单的整数 FPS 匹配规则已改为与列表一样的四舍五入，修复描述符为 59.94 fps、菜单显示 60、原先匹配时向下取整为 59 的不一致。连续帧率选择默认（fps=0）和步长为 0 不再发生除零。

`interval_test.c` 覆盖整数 NTSC、指定小数、连续／步进帧率、显式尝试未列出的间隔和无效参数，可独立执行，不打开摄像头：

```powershell
& 'D:/AndroidSDK/ndk/30.0.15729638/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe' --target=aarch64-linux-android24 -O2 -Wall -Wextra -Werror '-Iapp/src/main/cpp' diagnostics/usb-custom-mode/interval_test.c -lm -o build/perf-diagnostics/usb-custom-mode/interval_test
adb push build/perf-diagnostics/usb-custom-mode/interval_test /data/local/tmp/uvclive_interval_test
adb shell chmod 755 /data/local/tmp/uvclive_interval_test
adb shell /data/local/tmp/uvclive_interval_test
```

vivo V2338A 上的独立间隔计算测试通过。JVM 参数校验测试验证小数保留、范围和原始格式对齐限制。设备是否接受某个未声明的间隔，仍需用该组合实际启动采集验证；这些计算测试不能证明采集卡支持任意输入值。
