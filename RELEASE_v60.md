# v60：Arrow 时间窗过滤与视频更新修复

v59 仍按独立分段加载，因此 Viewer 每次只收到一个分段，视频时间线不会在同一 Viewer 中连续推进。v60 选择 Arrow 行级过滤方案：浏览器逐段流式读取本地分段，用 Arrow IPC 解码每个 RRF2 消息，按该段图像消息的时间范围（前后各2秒）保留所有实体行，然后通过 Rerun 0.20.3 的 `open_channel` / `send_rrd_to_channel` 发送到同一个 Viewer channel。

这不会丢掉 Boxes2D、depth 或 diagnostics 的同窗数据；同时避免将跨越很长时间范围的整块检测消息全部塞进图像窗口。每段仍保持完整的RRF2消息和独立的header/terminator，后续段只通过同一channel追加。

v60 也修复了原先只打开 `PREVIEW initial-part=1` 的路径：现在默认进入 `filtered:` receiver，所有分段按顺序发送，视频帧不应再停留在第一张。日志应出现 `filtered-start`、`filtered-part=N/..` 和 `filtered-done`。

限制：这是浏览器端过滤，单段会暂存不超过24MiB的HTTP响应；超出时会报错而不是加载整文件。Rerun 0.19/0.20兼容性警告仍存在。尚未在Android真机确认长记录完整拖动时长，需根据新日志继续调整。
