# v67 — direct RGB upload and earlier derived-image cache eviction

## Device evidence driving this build

The v66 device log was a real v66 run and passed the old part-3 staging-buffer
failure, reaching part 185/319. It then failed while rendering an RGB image:

```text
wasm-linear-bytes=135135232
wasm-live-bytes=101911287
failed-alloc-bytes=3686400
failed-alloc-align=1
pad_rgb_to_rgba -> texture_creation_desc_from_color_image
  -> image_to_gpu -> EncodedImageVisualizer
```

v67 removes that temporary RGB-to-RGBA Vec on WebGL. RGB8 data is passed to the
renderer and expanded directly into the mapped upload belt, with exact row
padding and no pixel loss. The derived decoded-image cache is capped at 16 MiB
on wasm and stale entries are purged before a new decode. Encoded recording
bytes, all image rows, timelines, entities and original pixel values remain.

The v66 bounded WebGL staging changes remain: 1 MiB minimum belt chunks and
256 KiB egui mesh queue writes. These bound contiguous host staging allocations
but do not assert a strict total WebGL/WebView memory limit.

## Local verification

- Full patched `re_viewer` wasm release build: exit 0.
- wasm-bindgen, wasm-opt and Android glue preparation: passed.
- `sh test.sh /sdcard/Download/977.rrd`: exit 0.
- Real recording invariants: 1383 container frames, 1382 data frames, 337 image
  markers, 319 bounded parts and 72 Java segments.
- Actual direct RGB upload probe: all pixels and alpha, row padding, offsets,
  capacity guard, typed fallback and 1280x720 case passed.
- Actual Web upload probe, allocator diagnostics and TextLog iterator probes
  passed.

APK: `/sdcard/Download/RerunViewer-v67.apk`
SHA-256: `e917d3c2d2fba93323e42646caf86841ff9c012be25888ccea052cdf68e91722`

The installed package was v66 at the last check. v67 Android rendering, full
playback, duration, seeking/history, reopening and crash resolution remain
unverified until the installer update is confirmed and a fresh device log is
collected. Existing Rerun Store RAM GC can remove old in-memory chunks; source
RRF2 data is preserved, but full Viewer history semantics still require device
evidence.
