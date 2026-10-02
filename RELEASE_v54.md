# v54：有界预览与移动渲染减负

## 用户日志结论

上传日志 `rerun-viewer-debug_0-1417264007.log` 为 v53：
- 完整加载 289658395 bytes 文件后约 10.5 秒出现 `Uncaught RuntimeError: unreachable`。
- 加载文件之前已有大量 `WebGL: INVALID_ENUM: framebufferTexture2D: invalid attachment`。
- 明确报告录制 0.19.0 与 Viewer 0.20.3 的兼容警告。
- 没有 OOM 或 Android renderer-kill 证据，不能将 WASM trap 直接断言为内存耗尽。

v53 默认完整加载并不稳妥，本版撤回。

## 修改范围

1. **数据输入**：默认 2/3/4MiB（设备分级）独立分段，未压缩预算为对应的 4 倍；单段硬拒绝超过 8MiB 编码或 16MiB 声明解压长度的输入。扫描失败、未知长度不再静默完整加载。完整模式只接受已知长度 <=8MiB，仍不承诺压缩数据解码内存有界。
2. **首屏数据选择**：新增纯 Java LZ4 原始块解码器，用于寻找 `EncodedImageIndicator` / `Boxes2DIndicator`。单块编码和解压各 <=8MiB、累计探测 <=64MiB；找不到则首段/手动选择。仅做标识查找，不解析 Arrow、不保证时间对齐、不修改源数据。正常设备上 977.rrd 默认选第2段。长按菜单可定位检测框或选择任意段。
3. **Viewer 数据回收**：软 GC 参数 512MiB → 96MiB；不是整个 WASM/GPU/进程硬限制。分段切换重建 WebView；禁止多文件叠加；新导入清理前一个接收器。
4. **画布/面板**：WASM JS bridge 的像素比上限为1，减少高DPI framebuffer像素及填充负担（画面更软）；blueprint、selection、time 默认 collapsed，可在长按菜单切回。折叠面板不是实体数据过滤，不会自动移除图像、地图或检测数据。
5. **WebGL窄范围保护**：对于 COLOR_ATTACHMENT0..31 中超出设备 MAX_COLOR_ATTACHMENTS 的 null-texture解绑跳过；实际纹理绑定、depth/stencil和其他调用保持原样。新日志记录命中次数。这只是针对一种可能无效解绑的兼容防护，尚未证明是用户日志中的全部GL错误根因。
6. **初始化/IO**：WASM Response交给bindgen的流式编译路径，避免HTML显式arrayBuffer副本；兼容回退仍由bindgen处理。扫描skip按需分配缓冲，避免每帧分配64KiB。新增mobile.js在HTTP资源白名单中。
7. **故障处理**：重复warning每10秒合并一次，2MiB日志轮转；JS error/unhandled rejection/Rust panic记录fatal并销毁异常WebView，不自动重放文件；renderer退出只重建空Viewer，不强制软件合成。旧WebView bridge回调不影响新实例。
8. **诊断**：每10秒记录WASM线性内存容量、canvas大小、兼容保护次数，以及宿主Java堆/PSS。宿主PSS不等于独立WebView renderer进程总内存；线性内存容量不等于活跃对象大小。

## 验证

- 原有41项RRF2结构回归通过。
- 新增15项LZ4/标识定位/坏块拒绝测试通过；以64MiB Java heap运行。
- 原始977.rrd全部1383块Java解压结果拼接SHA256与设备liblz4一致：
  `374eaa7aef8e98d5afb9ce451c1f763bbc8c59bc5a9223f8cc0e4546258e037c`
  解压消息总长度470561973字节；这不是播放后图像纹理总内存。
- 原始文件72分段的长度/哈希对照通过；图像/框标识定位均为第2段（4MiB预算）。
- Node测试验证DPR、GL空解绑边界、不屏蔽实际错误绑定、JS语法和资产路由/加载保护。
- APK构建与v2/v3签名验证通过。

## 尚未验证/不承诺

没有安装或真机播放验证；未解决或排除0.19/0.20兼容问题，未验证Arrow数据跨段依赖及视频/YOLO叠加。
这是有界分段预览，不是完整录制的无缝自动连续播放。片段可能缺失先前静态场景/标定等上下文。
HTTP直播仍是实验功能，未进行本次内存验证。降低像素比、折叠面板及软GC无法保证设备不再卡死。

交付：固定 `/sdcard/Download/RerunViewer.apk`，versionCode=54，versionName=0.20.34-bounded-preview。
