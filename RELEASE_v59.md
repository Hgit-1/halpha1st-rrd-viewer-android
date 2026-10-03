# v59：保留完整时间线的图像对齐分段

## v58 的问题

v58 使用 `SparseStream` 只发送含 `EncodedImageIndicator` 的容器帧。这样图像可能出现，但其他实体的 Arrow 消息、时间线和最终时长会被直接丢弃。v58 的日志 `image-only-frames=337` 正是该问题的证据；它不能证明总时长正确。

## v59 修复

v59 移除稀疏帧输出，恢复连续 RRF2 帧流。图像标识只用于选择更合适的分段边界，边界之间的所有帧（包括 Boxes2D 和其他实体）都保留。每个 HTTP 分段仍然只包含完整帧并受编码/解压预算约束，不把整份记录一次性送入 WebView。

因此：

- 其他时间线不会因为图像筛选而丢失；
- 总时长以完整记录中最后一个时间消息为准，不由 Java 按图像帧猜测；
- 分段单独加载时，Rerun 只能看到当前段的时间范围；点击下一段会重建 Viewer，不能宣称跨段无缝播放；
- v59 不会伪造完整总时长。如果要求一个连续、可拖动的全记录时间轴，仍需要让同一个 Viewer 持有多个连续窗口，或实现按 Arrow 行筛选后的重编码。

## 验证

- RrdFramesTest：49项通过，包括连续图像对齐段覆盖全部数据帧。
- RrdPreviewTest：17项通过。
- 已检查用户日志：v58确实进入 `image-only-frames=337 parts=31`，随后只打开 `PREVIEW initial-part=1`；该行为解释了其他时间线消失。
- 未在Android真机验证跨段总时长和自动播放；必须观察 v59 日志中的 `TIME_ALIGNED contiguous-frames=... duration=preserved`。

版本59 / 0.20.39-contiguous-timeline。
