# HTTP MPEG-TS 延迟转发服务

C++17 实现，支持 Linux 和 Windows，无第三方网络库。上传和播放使用同一个 HTTP
监听端口。服务端原样发送 TS 字节，不检查关键帧，不修改 PTS/PCR、连续计数器或
会话边界。播放器如何处理跨会话的时间戳回退、编码参数变化，由播放器决定。

## 多服务器自动分流

手机支持 1–8 个上传地址，输入多个地址后自动启用分流；单个地址可连接原来的
`relay` 模式，也可连接 `store` 模式。`merge` 模式支持 1–8 个上游。手机按服务器预计完成时间分配分块，各服务器独立传输；接收端从多台服务器
并行下载，校验、去重、按序拼接后输出一个普通 HTTP TS 播放地址。

**两台服务器各 3 Mbps、总码率约 5.5 Mbps 的配置示例：**

1. 两台服务器分别运行存储模式（两台使用相同的流名称）：

   ```sh
   http-ts-relay --mode store --port 8080 --stream live --retention 120 --buffer-mb 256
   ```

   无需配置服务器带宽。接收端根据实际分块下载速度向存储端反馈，手机读取反馈自动调整分配。
   尚未取得测量值时使用初始估计，取得测量值后自动更新；带宽变慢时快速下调，恢复后逐步提高。

2. 手机勾选“HTTP 远程分块上传”，上传地址每行填写一个，分块时长固定为 1 秒：

   ```text
   http://服务器A:8080/upload/live
   http://服务器B:8080/upload/live
   ```

   地址也可用逗号分隔，所有地址必须使用相同 `/upload/<流名称>`，最多 8 个不同地址。
   手机支持 HTTP/HTTPS 上传；多地址上传要求目标返回 `X-Relay-Mode: store`，避免误用普通转发模式。
   手机显示各服务器的出口速度估计、上传/重试状态及用于转投的已确认缓存。
   尚未取得有效带宽反馈的服务器显示“待评估”，收到反馈后才显示 Mbps 数值。

   仅使用一台存储服务器时，手机只填写一个上传地址，接收端只传一个 `--upstream`，例如：

   ```sh
   http-ts-relay --mode merge --bind 127.0.0.1 --port 8081 --stream live --upstream http://服务器A:8080 --delay 10
   ```

   单上游同样按序下载并输出 `http://127.0.0.1:8081/live/live.ts`；上游使用 `store` 模式。

3. **在接收端电脑上**运行聚合模式，然后让播放器打开本机地址：

   ```sh
   http-ts-relay --mode merge --bind 127.0.0.1 --port 8081 --stream live \
     --upstream http://服务器A:8080 --upstream http://服务器B:8080 \
     --delay 10 --max-pending-seconds 20 --gap-timeout 10
   ```

   Windows 使用 `http-ts-relay.exe`，将上面的参数写在同一行。
   播放地址为 `http://127.0.0.1:8081/live/live.ts`。
   接收端总下载能力需要覆盖两台服务器的合计流量；若放到另一台服务器上运行，该服务器出口
   需要能承载完整串流。将聚合程序放到一台只有 3 Mbps 出口的云服务器上，再远程播放，仍受 3 Mbps 限制。
   `--upstream` 当前支持 HTTP、主机名/IPv4和可选代理路径前缀；HTTPS 上游需使用 HTTP 到 HTTPS 的代理。

### 慢服务器与缓存

手机使用各服务器独立连接及统一有界队列，按出口速度为每台服务器安排发送间隔。
上传超时后原块转投其他可用服务器，失败服务器逐步延长重试间隔。
已确认的块仍在手机缓存中保留，接收端遇到阻塞顺序的慢下载时，通过反馈请求手机把该块
补到另一台服务器；接收端采用先完成的有效副本。健康链路不固定重复上传全部数据。
补救请求可通过任意可访问的 store 转达，即使该 store 本地没有目标分块。手机依据已确认上传的
会话和序号缓存定位原分块，并将副本补到转达请求的 store；merge 只配置一台上游，或另一台上游完全
不可访问时也支持。手机每个分块的补救间隔至少 5 秒；缓存过期后无法补传。首个可见分块之前的未知
历史不会主动追补，直播起点仍按现有目录选择。
分块以会话 ID 和序号去重，重复确认/副本不增加手机的累计唯一确认字节数。

手机输出页显示累计「转投尝试／已确认」及最近 5 次记录，包括分段序号、来源／目标服务器、
原因（上传失败或接收端下载慢）与上传中／已确认／失败／已淘汰等结果。首次正常分配和同服务器重试不算转投；
实际开始向不同服务器发送时计一次尝试，收到目标服务器 ACK 后计一次已确认；同服务器重试保留该转投记录，
不会增加尝试数，最终收到 ACK 时仍计为已确认；多次转投同一分段按尝试次数计数。
统计从本次 HTTP 上传启动开始，停止后重新启动会重置。
每台服务器另显示「累计上传（已确认）」的 TS 数据量（MiB）及分块数，单地址上传也显示。
普通上传和补传／转投在收到目标服务器的有效 ACK 后计入该服务器，失败或未确认的请求不计入，
不包含 HTTP 头和状态查询。转投副本在目标服务器另计一次，因此各服务器累计量之和可能大于
全局去重后的累计确认数据量；确认丢失后重试收到 ACK，同一上传任务只计一次。

服务端输出 `[redirect]` 日志：`phase=requested` 为 merge 请求慢块补救（同一会话／序号的连续轮询只计一次），
`phase=stored` 为目标 store 已存储转投分段（重复 POST 单独标注 duplicate，累计数不增加），
`phase=received` 为 merge 已校验并采用该转投分段。日志附带会话、序号、来源／目标、原因及累计数；
补救请求可能因手机缓存过期等原因无法完成，请求数不代表成功转投数。手机、store、merge 都更新后可看到完整链路。

手机待上传块和已确认副本共用 256 MiB 预算；已确认副本优先淘汰，超过所选缓存时长也会过期。
正在组块的缓冲及网络临时缓冲不包含在此预算中。停止上传会清空队列/副本并关闭在途上传。
存储端按 `--buffer-mb` 和 `--retention` 限制已接收分块，按接收时间淘汰。
聚合端把 `--buffer-mb` 分成两半，分别用于下载排序和播放缓存，并为前面的缺块保留下载空间。

`--gap-timeout` 控制缺块最多等待多久，默认 10 秒；等待期限从上一个连续块交付或选定接入起点开始计算。
超时且已有后续块时跳过缺块，日志记录跳过序号。迟到块不会重新插回播放位置。
被跳过的数据可能影响解码，直到后续关键帧恢复；如果希望更长时间等待补传，可以提高该参数和播放延迟。
启动时先汇总各上游目录，最多等其他上游 2 秒，再从保留下来的非零序号接入，不等待已经过期的序号 0。
目录中的媒体时间跨度超过 `--max-pending-seconds` 时，起点移到最新分段前约 `--delay` 秒，
避免从整段历史缓存追读即将过期的旧块。新会话使用手机提供的会话启动标识选择，不比较服务器墙上时钟。

6 Mbps 总带宽承载 5.5 Mbps 只有少量余量；总码率还应计入音频、TS 和网络开销。
缓存能吸收短暂变慢，长期总可用带宽低于串流码率时仍会耗尽。

### 上游连接与超时日志

聚合端的 TCP 连接等待独立限时 5 秒，连接成功后才开始计算响应限时。
每个上游的目录和分块下载复用一条 HTTP 连接，反馈复用另一条独立连接，减少反复建连。
手机端每台服务器保留一条上传连接，多服务器模式另用一条独立连接获取状态反馈。
单服务器上传也复用连接，`relay` 和 `store` 都支持；HTTPS 上传在同一 TLS 连接内发送多个分段。
确认和状态响应体会完整读取，避免残留数据干扰下一次请求；断线、协议错误或超时会关闭旧连接并重试。
停止上传会关闭上传和反馈的全部连接，包括闲置连接；块过期会取消对应的在途请求。
建议手机、`store` 和 `merge` 都更新到此版本；旧版服务端或关闭长连接的代理仍可访问，但会退回短连接。
分块目录和反馈响应限时 5 秒；分块下载按大小及估算速度设置 3–15 秒响应限时。
无新分块时目录轮询间隔 250 毫秒；连续失败时重试间隔从 250 毫秒增加到最多 2 秒，成功后恢复。
各上游独立工作，某一台连接失败不会阻塞其他上游。

失败日志包含 `upstream`（地址及请求路径）、`phase`（resolve/connect/request/headers/body）、
`elapsed`（本次耗时），下载阶段还包含已收到/期望字节数，连接错误尽可能打印系统 socket 错误码。
`phase=connect` 表示连接未建立；`phase=headers/body` 的 response timeout 表示连接已建立但响应未按时完成。
这些限时独立于 `--delay`（播放缓存目标）和 `--gap-timeout`（缺块等待期限），提高播放缓存不会延长连接等待。

### 分块接口

存储模式的上传额外要求 `X-Session-Started-Ms` 和 `X-Start-Us`。
会话启动标识由手机统一生成；分块起始时间和时长使用连续整数微秒边界，避免每块独立取整累积误差。
同一序号的内容、会话标识、起始时间、时长、结束标记不同会返回 409。

| 接口 | 用途 |
| --- | --- |
| `POST /upload/<stream>` | 保存分块并返回确认、模式及出口速度反馈 |
| `GET /index/<stream>` | 返回版本化分块目录，包含会话、序号、起始时间、时长、长度和 FNV-1a 校验值 |
| `GET /chunks/<stream>/<session>/<sequence>` | 下载指定分块，过期或不存在返回 404 |
| `GET /feedback/<stream>` | 接收端提交出口速度和慢块转投请求，独立于数据连接 |
| `GET /status/<stream>` | 手机读取 `X-Download-Rate-Bps` 和 `X-Rescue-Session/Sequence` |

聚合端不修改 TS 内容和 PTS/PCR；分块元数据只用于排序、校验和传输调度。
每个流使用一个手机发送端和一个聚合接收实例；多个播放器可连接同一个聚合实例。

## 构建和运行

Linux：

```sh
cmake -S server/http-ts-relay -B server/http-ts-relay/build
cmake --build server/http-ts-relay/build --config Release
server/http-ts-relay/build/http-ts-relay --port 8080 --stream live --delay 10 --max-pending-seconds 20
```

Windows（Visual Studio C++ 或 MinGW，使用对应 CMake generator）：

```powershell
cmake -S server/http-ts-relay -B server/http-ts-relay/build
cmake --build server/http-ts-relay/build --config Release
.\server\http-ts-relay\build\Release\http-ts-relay.exe --port 8080 --stream live --delay 10 --max-pending-seconds 20
```

MinGW 的 CMake 构建：

```powershell
cmake -S server/http-ts-relay -B server/http-ts-relay/build/mingw -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=Release -DCMAKE_EXE_LINKER_FLAGS=-static
cmake --build server/http-ts-relay/build/mingw
.\server\http-ts-relay\build\mingw\http-ts-relay.exe --port 8080 --stream live --delay 10 --max-pending-seconds 20
```

MinGW 也可以直接构建：

```powershell
g++ -std=c++17 -O2 -pthread -static server/http-ts-relay/main.cpp -lws2_32 -o http-ts-relay.exe
```

参数：

| 参数 | 默认 | 含义 |
| --- | --- | --- |
| `--bind` | `0.0.0.0` | 监听的 IPv4 地址 |
| `--port` | `8080` | HTTP 端口 |
| `--stream` | `live` | 固定流名称，1–64 个字母、数字、下划线或短横线 |
| `--delay` | `10` | 首次起播与缓存耗尽后的缓冲目标，秒，支持小数，0–300 |
| `--max-pending-seconds` | `20` | pending 的追赶阈值，达到此值时跳回 delay；不限制 cached 总时长；0.001–3600，必须不小于 delay |
| `--retention` | `120` | 已播放或跳过的历史数据最多再保留多少秒，1–3600 |
| `--buffer-mb` | `256` | TS 缓存容量，MiB，16–4096 |

每个进程接收一个固定流，最多 64 个 HTTP 连接。上传地址为
`http://服务器:8080/upload/live`，播放地址为 `http://服务器:8080/live/live.ts`，
健康检查为 `/health`。普通 MPEG-TS HTTP 播放器可以访问播放地址。
如需 HTTPS，可以通过反向代理转发，播放路径关闭响应缓冲和短时读取超时；
上传路径允许 16 MiB 请求体。

## Android 操作

USB 页 → **输出** → 勾选 **HTTP 远程分块上传**，填写上述上传地址。
点击 **HTTP 上传** 开始；也可以先录像，再点击 HTTP 上传添加输出。
上传模式和参数在启动采集时确定，已有采集会话内不切换模式。

分块默认 1 秒，可选 1/2/3/5 秒；每块最多 16 MiB，达到大小限制时提前分块。
分块不等待关键帧，使用单调时钟记录块的持续时长，不编辑已有 TS 字节。
待补传缓存默认 60 秒，可选 30–300 秒，内存容量上限 256 MiB，不生成缓存文件。
缓存即将超过数据时长、容量或 16,384 块限制时，淘汰最旧块继续上传，HTTP 输出不停止。
每块从开始组块起最多保留配置的缓存秒数，即使没有新数据也会过期；被淘汰的在途块
会取消 POST，不再重试。单块本身超过预算时直接丢弃该块。重试次数不限，但只重试仍有效的块。
TCP 连接超时 5 秒，读响应超时 10 秒，单次 POST 总超时 15 秒（关闭底层 Socket 解除发送阻塞）。
正在串流时，断线保留未确认的块并继续重试，收到服务器确认后移除对应数据块。
点击停止串流会关闭正在进行的 POST、退出上传线程，并清空所有待上传块和未成块的数据，
不发送尾块、不在后台补传。重新启动使用独立队列和新的会话 ID。
已经进入服务端播放缓存的数据仍按原队列播放，停止推流不会删除服务端已有数据。
应用进程退出时，全部待补传数据随内存释放，
重新启动后使用新会话 ID；服务器保留已有播放缓存并接入新会话。

输出页每秒刷新当前会话 ID、最新生成/正在上传/已确认序号、待上传与待补传块数、
缓存数据大小和时长、缓存占用、正在组块的数据、累计确认上传量及累计淘汰块数。序号从 0 开始，
还没有对应块时显示“—”。待上传是正常排队的未确认块；上传失败时已有队列以及中断
期间产生的块归为待补传，恢复后按序确认并减少计数。两个计数都包含在途未确认块，
正在组块的数据单独显示。缓存数据大小为有效 TS 字节数；缓存占用额外计入组块缓冲区
预分配空间，不包含对象、网络缓冲和瞬时复制开销，因此不是应用的全部内存占用。
时间/256 MiB 上限针对已组块队列；正在组块缓冲额外最多容纳 16 MiB 的有效数据。

## 服务端状态日志

`relay` / `merge` 使用播放队列统计。每次完整收到上传请求后打印 `[upload]`，每秒打印 `[status]`，断线或无客户端时也刷新。
例如：

```text
[upload] session=abc seq=12 status=200 result=accepted received=512.00 KiB speed=4096.00 KiB/s cache=6656.00 KiB cached=13.00 s pending=10.00 s blocks=13 state=playing dropped=0 skipped=0 dropped_bytes=0.00 KiB
[status] session=abc seq=12 speed=512.00 KiB/s cache=6656.00 KiB cached=13.00 s pending=9.00 s blocks=13 state=playing dropped=0 skipped=0 dropped_bytes=0.00 KiB
```

`session/seq` 为上传请求的会话与序号；状态行显示当前会话及最后确认的序号。
`status/result` 区分接受、重复/迟到确认、已结束会话和非法块。
上传行的 `speed` 是本次请求体大小除以接收请求头和请求体的耗时；状态行的 `speed`
是最近一个统计周期新入队数据的速度，重复块不重复计入，停止上传后归零。
`cache` 是当前队列的 TS 数据量，`cached` 是队列中各块时长之和（包括已播放或跳过但仍保留的历史），
`pending` 是按服务端公共时间线尚未播放的有效数据时长（不包括首次等待延迟，
也不代表每个客户端自己的剩余时长），`blocks` 是缓存块数。
KiB 为 1024 字节，速度单位为 KiB/s；时长来自上传的块时长，不解析 TS 时间戳。
`state=buffering/playing` 表示等待攒满或正在播放，`dropped` 是服务端跳过或淘汰的完整待播块数，
`skipped` 是上传序号跳过的块数（与推流端淘汰数可能不完全相同，ACK 丢失的块可能已入队）。
`dropped_bytes` 是跳过/淘汰的待播 TS 字节量，包含块内跳过的部分，单位 KiB。
过期历史会自动释放，重复确认不会增加缓存量。

`[status]` 和 `[upload]` 还显示以下累计次数，进程重启时重置，推流会话切换时继续累积：

| 字段 | 含义 |
| --- | --- |
| `pending_empty` | pending 从大于 0 变为 0 的次数；启动／持续空闲不计，正常播放耗尽、内存淘汰及 delay=0 的追赶耗尽均计入；`[playback] event=pending-empty` 标注具体原因 |
| `catch_up` | pending 达到 `--max-pending-seconds` 后实际跳到 `--delay` 位置的次数；每次跳转只计一次，保留历史缓存的清理不计 |
| `redirect_requests` | merge 请求补救的不同会话／分段数 |
| `redirect_received` | merge 已下载、校验并采用的转投分段数；重复副本不计 |

pending／播放统计用于 `relay` 和 `merge` 的播放队列。
追赶时另输出 `[playback] event=catch-up`，包含跳转前后的 pending 秒数及累计次数。

`store` 每秒输出实际分块存储和传输统计，例如：

```text
[status] mode=store session=abc seq=12 upload=512.00 KiB/s download=480.00 KiB/s cache=6656.00 KiB cached=13.00 s blocks=13 uploaded=6656.00 KiB downloaded=5760.00 KiB accepted=13 duplicates=0 expired=0 evicted=0 feedback=480.00 KiB/s redirect_requests=1 redirect_received=1 rescue=abc:10
```

`session/seq` 是最新会话已接收的最高序号；迟到的旧会话和旧序号补传不会让它回退。
`upload` 是最近一个统计周期完整接收并通过校验的上传请求体速度，包含重复上传；
`download` 是同期分块下载交给 TCP 的 TS 字节速度，包含重复下载和断线前已发送的部分。
两项都排除 HTTP 头、目录和反馈消息，空闲时归零；`uploaded/downloaded` 是对应的累计字节量。
`cache/blocks` 来自 store 当前实际目录缓存，`cached` 是所有缓存块的时长之和，包含不同会话和
不连续序号，不代表连续可播放时长。过期块每秒自动清理。
`accepted` 是新入库块数，`duplicates` 是内容相同的重复上传次数；冲突返回 409，不计入上传统计。
`expired` 是按 retention 到期释放的块数，`evicted` 是容量或块数上限淘汰的块数。
`feedback` 是 merge 反馈的下载速度估计，尚未测量或反馈超过 10 秒未刷新时为 `unknown`。
`redirect_requests` 是连续反馈中目标会话／序号发生变化时累计的补救请求数，重复轮询同一请求不增加；
`redirect_received` 是新入库的转投块数，重复 POST 不增加。`rescue` 显示当前有效请求的会话及序号，
没有请求或超过 3 秒未刷新时为 `-`。转投详情继续输出 `[redirect] phase=stored`。
这些累计数在进程重启时重置，跨推流会话保留；store 不显示 pending、播放状态或追赶次数。
HTTP `/status/<stream>` 仍用于向手机传递速度反馈和补传请求。

## 上传协议

每块使用 `POST /upload/<stream>`，请求体是 188 字节对齐的原始 TS 数据。
必须有 `Content-Length`，不接受 chunked 请求体。

| 请求头 | 含义 |
| --- | --- |
| `Content-Type: video/mp2t` | TS 数据 |
| `X-Session-ID` | 编码/输出会话 ID，网络重连保持不变 |
| `X-Sequence` | 会话内从 0 递增的块序号 |
| `X-Duration-Us` | 本块持续时间，微秒，与 TS 内的时间戳独立 |
| `X-Final: 0/1` | 是否为本会话最后一块 |
| `X-Redirect-From` / `X-Redirect-Reason` | 多服务器转投时附带原上传地址及 `upload-failed` / `download-slow`；两项同时提供，普通上传不需要；store 在分段下载响应中保留，用于 merge 显示转投来源 |

最后一块可以含 TS 数据；空会话可以发空尾块，时长为 0。
普通非空块的时长为 1–300,000,000 微秒。

服务端完整处理一个块后返回 `200` 和 `X-Ack-Sequence`；缓存预算可能导致淘汰旧块或超大的新块。
上传端收到对应确认后才移除内存中的块。响应丢失时重发同一块，服务端不重复播放。
允许序号向前跳过：缺失块视作直播丢包，继续接入后续数据。新会话也允许非零序号开始，
因为序号 0 可能已在发送端过期或淘汰。低于当前序号的迟到块只确认、不插回队列。
同一最新序号的重复请求内容不一致、已结束会话的额外新块仍返回 `409`。
服务端内存满时先释放最旧历史，再淘汰最旧待播整块，为新数据让出空间；不会因缓存满返回 `503`。

每次启动串流创建一个独立发送队列。停止时取消旧队列，重新启动后发送新会话。
新会话追加到原播放时间线，只要仍有待播数据就不重新缓冲；缓存已经耗尽则等待攒够 delay 秒。
如果停止串流或进程退出导致旧会话未发送 `X-Final: 1`，新会话会自动结束旧会话；
旧会话已经缓存的字节继续播放，尚未上传的数据无法恢复，旧会话迟到的新块不再接收。
重复确认信息保留最近 128 个已结束会话。
一个流同一时刻由一个推流端使用，多个推流端不并发写入同一流。

## 延迟和补传行为

首次起播与缓存耗尽后的恢复都以数据量为准：待播数据达到 delay 秒就开始输出，
不再额外等待首块到达后的 delay 秒。补传很快时，攒满即可开播。
所有客户端共用一个单调播放时钟；待播数据耗尽后暂停，直到重新累计够 delay 秒才恢复。
delay 为 0 时，有数据即可输出。
每块内部也分批按字节比例发送，避免把缓存一次性倾倒给播放器。
新的客户端从当前延迟播放位置所在块接入，不额外等待 delay 秒，也不寻找关键帧。
尚在首次或耗尽后的缓冲阶段时，客户端共用同一等待状态。
实际采集到播放的延迟还包括上传分块时间、封装窗口和播放器自己的缓存。

如需吸收 20 秒网络中断，可设置 `--delay 30 --max-pending-seconds 60`，
前提是数据留在上传端，并在预定播放时刻前补齐。
补传不限于实时码率，网络恢复后按序尽快上传；需要足够的恢复带宽。
待播缓存到达 0 后，播放器等待重新攒满 delay 秒；剩余不足 delay 的尾段会继续等待后续数据。
pending 达到 max-pending-seconds 时，共用播放位置前移到只剩 delay 秒待播数据。
所有客户端随后从对应的新位置取数据，已进入 TCP 或播放器缓存的字节无法撤回。
pending 在 delay 与 max-pending-seconds 之间时继续正常播放，不主动追赶。
每个真实秒消耗约一秒待播数据；实时上传的块时长也约一秒时，pending 应基本稳定。
补传的媒体时长快于实时消耗速度时 pending 会增长，这与达到上限后的追赶规则配合使用。
默认 delay=10、max-pending-seconds=20，突发收到 20 秒数据后 cached=20、pending=10；
跳过的旧数据可继续作为历史缓存保留，所有客户端都从新的播放位置继续。
追赶位置落在一个块内部时，按块时长和字节比例定位，跳过完整的 188 字节 TS 包，
不修改保留数据的时间戳或其它内容。单包对齐可能使实际待发送量略少于日志中的时间目标。
cached 总时长不受 max-pending-seconds 限制；缓存容量由 buffer-mb 控制，
已播放或跳过的历史通过 retention 到期释放。容量不足时先清理历史，再淘汰最旧待播整块。
淘汰后仍有待播数据时继续输出；待播量归零时重新缓冲到 delay。
没有音视频数据的采集停机间隙不能凭空恢复；服务端只拼接已有字节，不填静音或画面。
客户端落后到已淘汰范围时，跳到当前直播位置继续输出；已在 TCP 中的字节无法撤回。
丢块可能造成播放器短暂花屏或静音，恢复取决于后续关键帧；服务端不解析或编辑 TS。

服务端缓存和序号状态在内存中，本版本覆盖网络断线和发送端会话衔接，
服务端重启会丢失已有缓存；上传端可从现有非零序号继续接入，但需要重新攒满 delay 秒。
Android 内存中的待补传块不会在单次重试失败时自动清空；停止串流时立即清空，进程退出后也无法恢复。

## 验证

```sh
python server/http-ts-relay/tests/integration.py --binary server/http-ts-relay/build/http-ts-relay
python server/http-ts-relay/tests/integration.py --binary server/http-ts-relay/build/http-ts-relay --outage
```

Windows 使用 `.exe` 路径。第二条约需一分钟，会在播放开始后制造真实的
20 秒上传空窗，并检查自定义 30 秒延迟下的连续输出。
测试覆盖重复确认、跳过序号、非零会话起始、迟到块忽略、两个客户端、会话切换、迟加入客户端、
缓存耗尽后的恢复及逐字节一致性。Android JVM 测试另覆盖分块、内存队列确认释放、
容量/时长淘汰、单块过期、淘汰在途块后继续上传、确认丢失后的同块重试、统计变化和跨会话上传顺序。
服务端联调另验证默认 delay=10/pending 上限=20、历史缓存可超过 pending 上限、内存满后继续接入、
pending 达到上限后回到 delay、自定义 30/60 设置下 60/120 秒突发数据保持 pending=30、块内 TS 包对齐的追赶、
攒满后立即开播、发送端淘汰序号后重新攒满并保持实时待播量、
日志所示 skipped=286 的 60 秒突发补传后继续实时上传且不再停留在 buffering、
缓存耗尽后两个客户端共同攒满再播、日志字段、重复块不增加统计、空闲速度归零及缓存过期释放。

可选 `--android-classes <classes.jar> --kotlin-stdlib <kotlin-stdlib.jar>`，调用实际生产
Kotlin 上传器，与 C++ 服务端进行逐字节联调；`--java`、`--javac` 可指定本机 JDK。
`tests/distributed.py --case unreachable` 配合相同的 Android/JDK 参数，验证分块已经上传到 B，
而 merge 仅配置 A，或第二个 upstream 完全不可连接时，经 A 请求手机补传并按序输出完整字节。
额外加 `--stall` 会故意让服务器不读取 13 MiB 的 POST 请求体，验证 15 秒整次请求
超时关闭底层 socket 后，原块保留并原样重传。
