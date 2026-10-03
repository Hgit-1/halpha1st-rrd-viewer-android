# v63：逐帧提交与分配失败诊断（待真机验证）

## v62 日志确认的事实

`uploads/rerun-viewer-debug_9-1417264007.log` 在第 5/319 段提交后捕获到 `RuntimeError: unreachable`，栈经过 `alloc::handle_alloc_error`、`pad_rgb_to_rgba` 和 `EncodedImageVisualizer`。它定位了 RGB→RGBA 渲染过程中的分配失败，但没有给出失败分配的大小或当时可用内存。

失败前较早一次采样的 WASM 线性内存是 100401152 字节。该数值不是崩溃瞬间的内存量，也不是存活对象大小。`memory_limit_bytes=100663296` 仍然只是 Viewer 的回收目标，不是线性内存硬上限。累计缓存、分配峰值和其他内存问题仍需区分；不能仅根据栈断言 12 行图像同时解码就是根因。

## 变更

- 保留原有 `transformRrd` API，新增生成器 `transformRrdParts`。
- 继续按原时间窗过滤；每次 channel 提交只包含一个完整的 RRF2 消息帧，配独立头和结束标记。未修改的压缩块逐字节保留。
- 不做逐图像行拆 chunk：这种拆分若沿用 `rerun.id`，Rerun 会忽略重复 ID，丢失后续行。所有保留的 Arrow 行、chunk ID 和非图像实体均与原过滤结果一致。
- 在每次提交后等待 100ms，让 UI 有机会工作。该等待不是解码完成确认，也不是严格的队列背压或内存上限。
- 记录每个分段内的 frame 编号、提交字节数、提交后 WASM 线性内存。fatal 记录崩溃处理时的线性内存。
- 不更改原生 Viewer 界面、96MiB 回收目标、8MiB 响应读取上限或 native 分段 API。

## 验证范围

新增测试验证完整帧边界、压缩字节保留、元数据只保留一次、空输出、逐次提交的 yield、失败停止和重复导入保护。真实录制回归将逐帧输出重新组装，与单段过滤输出逐字节比较，覆盖所有保留数据而不仅是图像范围。

本次 `sh test.sh /sdcard/Download/977.rrd` 已通过：RrdFramesTest 43 项、RrdPreviewTest 17 项、mobile/filter/pacing JS 测试、1383 帧 native LZ4 oracle、319 分段（281 个含图像）逐字节重组，以及 RealRecordingTest。后台执行器不能访问 Termux 目录，故改在 Termux 直接运行；最初的后台失败不是测试失败。

这些测试不能证明 Android 图像渲染成功，也不能证明完整时长和 Boxes2D 可见。一个帧仍可能包含多张图像，Viewer 仍可能积累缓存并回收旧数据。v63 是用于降低集中提交压力和缩小失败范围的实验版本，不是已验证的播放修复。
