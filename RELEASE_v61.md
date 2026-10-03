# v61：修复 LZ4 解压并增强分段诊断

v60 在首个过滤分段提交后出现 `fatal:Script error`。v61 修复了浏览器端 LZ4 重叠匹配复制的索引错误，并将过滤流程改为阶段化诊断：`fetch/read/inspect/filter/send`。每个分段会记录原始和过滤后字节数，读取响应失败时取消 reader，避免残留请求。

同时将过滤分段上限降至 8MiB，原生分段目标降至 0.5–1MiB，降低 Arrow/WASM 临时内存峰值；过滤导入不再伪造“解码已完成”，因为 `send_rrd_to_channel` 没有完成回调。

测试新增 LZ4 重叠匹配、literal-only 各长度、损坏输入及资源脚本检查。
