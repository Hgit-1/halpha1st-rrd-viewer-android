# RerunViewer 接手审计（首轮）

## 已核对

- 两份上传版本总结 SHA-256 相同：`6eb495a15b42404a272e85f1c139ee766aa2022526b52eaae126c1893df1e014`。
- Termux 工程路径：`/data/data/com.termux/files/home/.aether/workspace/RerunViewer`。
- Manifest / 现有 APK：versionCode 49，versionName `0.20.29-tick-based`。
- 现有 APK 首次检查 SHA-256：`295a7e7af576e933ff432fd442199d80dbc877bb7e141b9dd41d9a5330bf5137`。
- 当前是 Java Activity + 本地 HTTP + WebView + Rerun JS/WASM；非 Gradle 工程，构建使用 `build.sh`。
- README、历史总结和当前源码互有矛盾，不作为已通过测试的证据。

## 源码发现（未修复）

1. **窗口端点未接线**：MainActivity.java 中 `serveFrameWindow` 只有定义，没有调用。HTTP `/local/` 一律转到 `serveFile`。WebView 拦截器 `openIncomingWebResource` 也没有 `/frames` 分支，此 URL 会落入完整文件流路径。窗口请求因而可能重复读入整份文件。
2. **首窗口 EOF**：RrdFrames.Index.frames 包含首个元数据帧；FrameWindowStream 先发送包含该帧的 preamble，再从 index.frames[0] 开始。此时 position 已等于 currentFrameEnd，直接返回 -1，后续帧不会发出。窗口 Content-Length 也可能与实际字节数不符。
3. **时间线 API 不存在于当前 JS wrapper**：`re_viewer.js` 中未找到 `get_viewer_state`、`get_current_time`、`current_frame`。MainActivity.setupTimelineMonitoring 在 API 缺失时执行 `lastFrame++`，这只是墙钟计数，不是播放进度。
4. **概念混用**：RRF2 容器帧序号不是播放 tick，也不保证等于可视化帧数。不能直接用时间线值除以 300 得到文件窗口。
5. **初始化遗漏**：loadInitialWindow 在 viewerReady=false 时保存 fileId 但不排队；ready() 只重放 receiver URL，不恢复该窗口。
6. **重复定时器和清理不完整**：loadInitialWindow 调用 checkAndLoadWindows 后再次 scheduleWindowCheck，而 checkAndLoadWindows 自己也调度下一次；stopImportAndReload 未清空 currentWindowId / loadedWindows 等窗口状态。
7. **窗口删除不等于窗口数据 GC**：本地 Rerun 0.20.3 `web.rs::remove_receiver` 同时移除 receiver 和按 URI 移除 recording。必须核对 StoreId/URI 所属关系，不能假设能只释放指定帧窗口。

## 构建和修改状态

- 第一次后台构建失败在 `cd RerunViewer`，尚未运行编译器：后台执行环境与 Termux bash 的 cwd 不一致。
- 绝对路径后台重试仍失败在 `cd`，该执行环境看不到 Termux 工程路径，未进入编译阶段。
- 随后通过当前 Termux 的 bash 工具执行 `sh build.sh` 成功；Java 编译仅有 bootstrap class path / deprecated API 提示。
- 新产物 `dist/RerunViewer.apk`：31,187,658 bytes，SHA-256 `394be12ed109967149fa886f41e8689bb2310f0dac73d09c6d03f6519f25e1a0`。aapt 核对仍为 v49，apksigner 的 v2/v3 签名验证通过。
- 构建成功只证明现有源码可打包，不代表动态窗口问题已修复。
- 尝试修改过 RrdFrames 的窗口索引，但随后精确撤回；目前不保留该修改。应先做回归测试、确认 API 语义，再整体修复索引、路由与长度计算，避免半套修复。
- 尚未完成 Android 运行验证、大文件验证，也没有安装新 APK。

## 建议下一步

1. 当前 Termux 直接构建已通过；后续先保存源码与产物基线，再构建（build.sh 会删除 build 和 dist）。后台执行环境的路径隔离问题另行处理，不应误报为源码编译错误。
2. 为 RRF2 扫描/窗口流添加合成输入测试，覆盖元数据、首/末窗口、短读、截断、终止帧和 Content-Length。
3. 成套修复请求路由、窗口索引和生命周期问题。
4. 明确选择：连续完整流 + 经验证的 Viewer GC，或独立段显式切换。不要将 HTTP EOF/读取速率当作播放进度。
5. 固定测试文件身份、大小、时间线名称/范围，再测试内存、暂停/跳转和恢复；成功构建不代表播放通过。

历史文档中的“无限帧”“永不崩溃”“固定 4–6 MB”“桌面无内存限制”等结论不应继承为保证。
