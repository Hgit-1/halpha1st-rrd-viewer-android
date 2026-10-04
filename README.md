# Rerun Android wrapper

这是一个轻量 Android 原生 Activity 外壳，启动后在系统 WebView 中加载 Rerun 0.20.3 官方 Web Viewer：

`https://app.rerun.io/version/0.20.3/`

上传的 `rerun-cli-0.20.3-aarch64-unknown-linux-gnu` 是 Linux/glibc ARM64 ELF，不能直接放进 Android APK 执行；本项目没有把它误当作 Android 原生库使用。

## 构建

在本设备 Termux 工作区执行：

```sh
sh build.sh
```

产物：`dist/RerunViewer.apk`；交付副本：`/sdcard/Download/RerunViewer.apk`

## v66 当前行为

v66 保留 v65 的直接 Chunk 时间线提取，并进一步降低 WebGL 上传峰值：Web
staging belt 最小块为 1MiB；egui 网格索引和顶点上传在 WASM 上按最多 256KiB
写入，同时保留所有字节和绘制偏移。新增实际 WASM 分配量、失败分配大小和对齐
诊断。详见 `RELEASE_v66.md`。APK 仍需真机完成安装、渲染和完整回放验证。

## v65 当前行为

v65 将 TextLog 的时间线提取改为直接从 Chunk 时间列生成紧凑共享数组，跳过逐行 `TimePoint/BTreeMap` 构造；保留所有文本、时间线和其他实体数据。详见 `RELEASE_v65.md`。

## v57 当前行为（优先于下方历史说明）

恢复 Rerun 原生主页布局：不再启动时强制隐藏左/右面板或展开底部时间线，顶部由 Rerun 原生提供左/下/右三个面板按钮。移除自定义“精简侧栏”入口。详见 `RELEASE_v57.md`。

## v56 历史行为（原生DPR已保留）

按要求移除 v55 自定义底部状态提示与控制器，恢复全高画布及设备原生DPR/分辨率，不再强制低分辨率渲染。
保留原生 Rerun 控件、打开按钮与长按分段菜单；安全分段策略不变。未进行真机清晰度/播放验证，详情见 `RELEASE_v56.md`。

## v55 历史行为（控制器与分辨率限制已撤回）

修复 v54 DPR与ResizeObserver物理画布不一致导致的小UI/触点错配；新增移动大按钮：播放/暂停、时间线单步、重播、上下段、所选区域±缩放、面板。时间线默认展开。
先点图像再用±缩放；片段播完后点下段。仅有界预览，不是整份视频无缝播放。未真机验证首帧停留已解决。详见 `RELEASE_v55.md`。

## v54 加载策略（v55 保留）

撤回 v53 默认完整加载：默认按设备 2/3/4MiB 安全分段，优先选择含图像标识的片段。
长按可定位检测框标识、选择分段、停止、切换精简侧栏；完整模式仅支持已知长度 <=8MiB。
保留底栏移除和按钮左移。96MiB Viewer 软GC、DPR上限1、流式WASM初始化、日志去重与异常销毁降低压力。
这是独立片段预览，不是无缝整文件播放；并非已证实修复OOM或所有GL错误。详细验证及限制见 `RELEASE_v54.md`。
测试额外需要 Node.js，命令 `sh test.sh /sdcard/Download/977.rrd`。

## v53 历史行为（已被 v54 替代）

默认完整流读取，避免首段缺少图像/检测实体。移除原生底部状态栏；打开按钮左移 16dp。
长按打开按钮可选完整加载、分段加载、分段编号、停止或直播。分段模式只显示当前段的数据，切换会重建 Viewer。
完整加载可能提高内存压力；图像与检测框的真机显示仍需验证。分析与验证边界见 `RELEASE_v53.md`。

测试：`sh test.sh /sdcard/Download/977.rrd`。
发布：更新包版本、Git 提交后运行 `sh release.sh /sdcard/Download/977.rrd`。

Viewer 由 APK 内置的 HTTP 服务通过真实本机 TCP 连接加载，服务只绑定 `127.0.0.1` 的临时端口，不对局域网开放。启动时应用会执行一次 `GET /health` 的 TCP 自检；若本地连接失败，会显示启动错误而非无限卡在加载页。HTML、JS、WASM 由本地服务提供，已知长度的 RRD 由 WebView 请求拦截器流式读取。WASM 以未压缩资源打包，初始化超过 90 秒会显示错误。直播模式由本地代理读取远程流，仍要求直播源可访问。
支持：

- 从 Rerun 页面文件按钮打开 `.rrd` / `.rbl` 文件；Android 原生层直接流式读取 `content://` URI，不复制整份大文件到缓存
- 可识别的 RRF2 `.rrd` 会扫描帧边界并按约 32 MiB 分段；默认保留每个完整数据帧，不再任意丢弃帧。Arrow/MsgPack 消息可能跨压缩帧依赖，任意删除完整帧会导致 `Arrow error: Operation overflow the backing container`。当前通过完整帧分段和每段独立 WebView 重建控制单次负载；每次只打开一个段，分段传输完成不会伪造播放进度或自动切换，点击底部状态栏可选择其他分段。
- 仅支持确认过的 RRF2 长度帧分段；无法识别的文件回退为完整文件流，不按固定字节数截断。单个压缩帧大于 256 MiB 时拒绝分段扫描
- 长按右上角原生文件夹按钮输入可信的 Rerun HTTP(S) 直播 URL。应用由独立接收线程保留所有完整 RRF2 数据帧写入磁盘 DVR，不做不安全的任意帧丢弃；再由本机 chunked HTTP 尾流提供给 Viewer。Java 内存不保存早期直播内容。DVR 每段约 16 MiB、每路最多保留 12 段（约 192 MiB），段轮换后旧段删除
- 直播 Viewer 使用 `follow_if_http=true` 跟随最新数据；接收和 WebView 消费解耦，WebView 暂时卡顿时上游仍可继续写盘。DVR 写入使用复用的 64 KiB 缓冲和按帧 flush，减少 GC/IO 抖动。低负载采样已关闭以保证 Arrow 消息连续性；当前仍不会给 WASM 内部历史数据设置绝对内存上限；长时间直播仍可能因 Viewer 内存增长卡住。已完成的 DVR 段不会自动变成同一时间轴的可拖动历史；停止直播后需要将段作为离线记录逐段查看
- Rerun 0.20.3 源码层已确认内置 `ChunkStore::gc` 和内存超限淘汰机制；当前 APK 使用原版 WASM，未宣称 eviction 已生效。默认使用完整 RRF2 帧分段路径；实验性的 `/frames` 窗口端点已修正但不自动启用，因为 Viewer 没有可靠播放位置 API。分段读取完成不会自动伪造播放进度或切换；点击底部状态栏可选择下一段，切换时重建 Viewer。
- 右上角原生文件夹按钮提供较大触控范围；WebView 禁止边缘滚动抢事件、关闭滚动条和系统缩放，保留 Rerun 画布的双指/拖动事件
- 从文件管理器或分享菜单传入文件，支持单文件和多文件分享；未知长度提供者才使用缓存回退
- Android 6-12 首次访问共享文件时自动申请读取权限；Android 13+ 使用系统文件选择器权限
- 本地 `content://` 文件经 WebView 请求拦截直接流式读取；大文件分段和直播 HTTP 代理是两条独立路径。直播不会走离线分段，也不会影响普通静态 `.rrd` 的 `Playing` 模式。
