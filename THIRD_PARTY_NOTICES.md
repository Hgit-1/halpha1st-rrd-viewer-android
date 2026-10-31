# 第三方软件与修改说明

本仓库包含、修改并再分发了 Rerun 的 Web Viewer 构建产物，以及若干第三方组件。本文档列出各组件的来源、许可证和本项目所做的修改，并说明如何取得许可证原文。

本仓库并非 Rerun 官方 Android 客户端，也不代表 Rerun Technologies AB 对本项目的认可或背书。项目名称、应用图标与上游品牌的区别见根目录 [README](README.md)。

## 1. Rerun 0.20.3（Web Viewer、WASM 与 Rust 源码补丁）

- 上游项目：https://github.com/rerun-io/rerun
- 版本：0.20.3
- 许可证：MIT OR Apache-2.0，由接收方选择其一
- 版权声明：`Copyright (c) 2022 Rerun Technologies AB <opensource@rerun.io>`
- 许可证原文：[Rerun-MIT.txt](Rerun-MIT.txt)、[Apache-2.0.txt](Apache-2.0.txt)

### 随 APK 分发的构建产物

`assets/rerun/` 下的 `index.html`、`re_viewer.js`、`re_viewer_bg.wasm`、`re_viewer.d.ts` 与 `re_viewer_bg.wasm.d.ts` 来自 Rerun 0.20.3 的 Web Viewer 构建，并由本项目重新编译或修改：

- **WASM 与胶水代码**：使用 `build-wasm-v100.sh` 从打过补丁的 Rerun 0.20.3 源码树构建，特征为 `--target no-modules-base --no-default-features --features grpc,map_view`。构建时应用了仓库内的补丁（见下节），并替换了 `vendor/egui-wgpu`。仓库中的二进制与这些源码并不逐字节对应，上游原始构建产物也可能因工具链不同而不同。
- **`index.html`**：基于上游 `index-original.html`。移除了外部字体、favicon 与统计脚本引用，改为加载本地 `mobile.js`、`re_viewer.js`、`rrd_filter.js` 和 `source_loader.js`，并加入移动端启动、内存参数与错误上报逻辑。仍保留 `<title>Rerun Viewer</title>` 与 Rerun 的界面样式。
- **`mobile.js`、`source_loader.js`、`source_worker.js`、`rrd_filter.js`**：本项目新增的脚本，不属于上游产物。`rrd_filter.js` 是打包后的文件，内含下文列出的第三方 JavaScript 库。
- 未包含 `/favicon.ico`、`/favicon.svg` 等上游资源。本地 HTTP 服务只提供固定的资源白名单，不会读取其他文件。

### Rust 源码补丁

以下补丁修改 Rerun 0.20.3 源码，仅用于自行重新构建 Web Viewer：

| 补丁 | 修改的上游文件 | 用途 |
| --- | --- | --- |
| `web_upload_memory.patch` | `crates/viewer/re_renderer/src/context.rs`、`crates/utils/re_memory/src/{accounting_allocator.rs,lib.rs}`、`crates/viewer/re_viewer/src/web.rs` | 限制 WASM 上传缓冲的峰值写入与内存统计 |
| `v67-source.patch` | `crates/viewer/re_renderer/src/{lib.rs,context.rs,allocator/cpu_write_gpu_read_belt.rs,resource_managers/image_data_to_texture.rs}`、`crates/viewer/re_viewer_context/src/{cache/image_decode_cache.rs,gpu_bridge/image_to_gpu.rs}`、`crates/viewer/re_data_ui/src/video.rs`、`crates/utils/re_memory/src/{accounting_allocator.rs,lib.rs}`、`crates/viewer/re_viewer/src/web.rs` | 在 GPU 上传缓冲中逐行扩展 RGB8，限制派生图像缓存 |
| `textlog_direct_timelines.patch` | `crates/store/re_chunk/src/iter.rs`、`crates/viewer/re_space_view_text_log/src/{visualizer_system.rs,space_view_class.rs}` | 直接从 Chunk 时间列生成 TextLog 时间线 |
| `textlog_compact_sort.patch` | `crates/viewer/re_space_view_text_log/src/{lib.rs,visualizer_system.rs}`，并新增 `compact_sort.rs` | 为 TextLog 增加紧凑排序记录，减少大块连续分配 |
| `diagnostic_logging.patch` | 说明性文档，不含代码改动 | 记录需要补充的诊断日志位置 |

补丁内容本身也属于对 Rerun 的修改，按上游 MIT / Apache-2.0 条款分发，不额外附加限制。

### egui / egui-wgpu 0.29.1

- 上游项目：https://github.com/emilk/egui（`crates/egui-wgpu`，0.29.1）
- 许可证：MIT OR Apache-2.0
- 版权声明：`Copyright (c) 2018-2021 Emil Ernerfeldt <emil.ernerfeldt@gmail.com>`
- 许可证原文：[egui-MIT.txt](egui-MIT.txt)、[Apache-2.0.txt](Apache-2.0.txt)
- 位置：`vendor/egui-wgpu/`
- 修改：仅 `src/renderer.rs` 中 WebGL 网格上传改为按不超过 256 KiB 分块写入；其余文件来自 crates.io 发布包。详见 `vendor/egui-wgpu/PATCH.md`。该目录只用于 Rust 端重新构建，不会直接进入 APK。

## 2. 打包进 `assets/rerun/rrd_filter.js` 的 JavaScript 库

以下库经 esbuild 打包进 `rrd_filter.js`，随 APK 分发：

| 库 | 版本 | 许可证 | 版权声明 | 许可证原文 |
| --- | --- | --- | --- | --- |
| apache-arrow | 21.2.0 | Apache-2.0 | Apache Software Foundation | [Apache-2.0.txt](Apache-2.0.txt)、[apache-arrow-NOTICE.txt](apache-arrow-NOTICE.txt) |
| flatbuffers | 25.9.23 | Apache-2.0 | The FlatBuffers project | [Apache-2.0.txt](Apache-2.0.txt) |
| msgpackr | 2.1.0 | MIT | `Copyright (c) 2020 Kris Zyp` | [msgpackr-MIT.txt](msgpackr-MIT.txt) |
| tslib | 2.8.1 | 0BSD | `Copyright (c) Microsoft Corporation.` | [tslib-0BSD.txt](tslib-0BSD.txt) |

`flatbuffers` 是 `apache-arrow` 的依赖；`tslib` 是其 TypeScript 运行时辅助代码。`msgpackr` 的可选原生加速包 `msgpackr-extract` 未打包，也不随 APK 分发。版本号取自提交在仓库中的 `tools/rrd-filter/package-lock.json`。已发布的 `rrd_filter.js` 由本地 `.toolchain/recording-probe` 依赖目录打包而成（见文件内注释），该目录未纳入仓库，因此无法从当前仓库逐字节复现该文件的依赖解析结果。

Apache Arrow 的 NOTICE 文件按 Apache-2.0 第 4(d) 条保留在 `LICENSES/apache-arrow-NOTICE.txt`，内容为：

```text
Apache Arrow JavaScript
Copyright 2017-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

### 仅在构建或测试时使用

`tools/rrd-filter/package.json` 与 `package-lock.json` 还列有 `esbuild`（0.28.2，MIT）和 `@msgpack/msgpack`（3.1.3，ISC）。前者用于打包，后者当前未被 `filter.mjs` 引入，两者都不随 APK 分发。esbuild 的许可证原文见 [esbuild-MIT.txt](esbuild-MIT.txt)。

## 3. 不随本项目分发的部分

以下内容不属于本仓库，也未包含在 APK 中：

- Rerun 的原生桌面 / 服务器程序、Python、C++、Rust SDK 与 gRPC 服务端。
- Android 系统 WebView 及其内置的 Chromium 组件，受设备厂商与系统更新条款约束。
- 用户通过文件选择器或直播地址提供的 `.rrd` 记录。这些记录的版权归其作者所有。

## 4. 项目自身代码

除上述第三方组件及 `assets/rerun/` 中标注为上游产物的文件外，本仓库中由项目维护者编写的 Android 外壳、脚本、测试与文档采用 MIT 许可证，版权声明为 `Copyright (c) 2026 Hgit-1`，原文见根目录 [LICENSE](LICENSE)。

## 5. 无担保

所有第三方组件均按其原始许可证“按现状”提供，不附带任何明示或暗示的担保。本项目的测试状态与数据风险提示见 [README](README.md)。
