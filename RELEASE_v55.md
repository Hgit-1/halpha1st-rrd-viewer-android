# v55：移动触控与画布坐标修正

## 依据

用户截图已显示图像和检测框，但报告停首帧、缩放难用。设备v54日志有持续metrics，WASM线性容量约119209984 bytes，无所读区间的fatal；这不能证明图像播放成功，也不能证明所有期间无卡顿。

定位到v54的一处确定缺陷：只修改DPR getter为上限1，但eframe 0.29.1 `events.rs::get_display_size` 优先读取ResizeObserver的devicePixelContentBoxSize并将倍率设为1。因此物理画布仍为1080x2121，而逻辑DPR是1；排版过小且CSS触摸坐标与绘制坐标可能错配。

## 修正

- 将该ResizeObserver bridge分支改为CSS content box乘受限DPR。保留真实ResizeObserverSize实例以满足wasm-bindgen dyn_into检查；旧浏览器回退使用相同原型的只读尺寸对象。不会修改视频纹理尺寸getter。
- WebView启用wide viewport以遵守HTML width=device-width，仍禁止浏览器页面手势缩放，Viewer内部缩放不受此限制。
- 增加116 CSS px高触控区域，按钮至少44px，canvas让出区域，不遮挡原时间线：重播、退一步、播放/停、进一步、上段、下段、缩小、放大、面板。
- Space对应播放/暂停，左右箭头对应时间线步进，Ctrl+Left对应当前时间线重播。依据Rerun `re_ui/src/command.rs` 与 `app.rs`，非不存在的WebHandle API。UI不显示未经观测的“正在播放”状态。
- 用户先点图像/3D目标，再用±；以归一化坐标记住目标并发送mousemove及Ctrl-wheel，使用eframe的zoom事件，非页面整体拉伸。2D拖动/双击复位仍保留原生Viewer语义。
- 默认展开时间线，侧栏仍精简；面板按钮可切换。
- 显示当前分段号和传输完成提示（不当作解码完成）。上下段为用户明确操作，每次重建WebView。
- 日志增加CSS尺寸及原始DPR，以便验证真机画布一致性。

## 首帧问题的边界

本轮修正触控与排版错配并提供可触达的播放控制，未证明首帧停留唯一由按钮难点造成。
4MiB片段只包含短时范围，各实体时间线/采样密度不同，逐步前进不是“下一张图像”。没有增加自动切段、无限播放或完整流加载；需要在片段结束后按下段。跨段依赖与0.19/0.20兼容风险仍存在。
新增底部区域是用户操作控件，不恢复旧版报错状态栏。

## 验证

- 41项RRF2、15项LZ4与标识探测回归。
- 真实977.rrd全部帧Java/liblz4摘要一致；72分段结构一致。
- JS测试覆盖CSS尺寸/DPR、ResizeObserverSize类型、快捷键down/up、播放/步进/重播映射、缩放前目标检查与方向/坐标、切段方向、未就绪阻止操作、资源路由以及JS语法。
- 构建与签名验证；未执行Android UI/触摸/视频连续播放真机验证。

发布版本：55 / 0.20.35-touch-controls；固定交付 /sdcard/Download/RerunViewer.apk。
