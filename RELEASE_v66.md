# v66 — Web upload allocation pressure (experimental)

## Device evidence

The supplied `rerun-viewer-debug_4-1417264007.log` is confirmed v65, unlike
previous v63 uploads. It fails after part 3/319 frame 5, with linear memory
93,650,944 bytes. The allocation stack is now:

`alloc::handle_alloc_error -> raw_vec::handle_error -> wgpu_hal::gles::Device::create_buffer -> StagingBuffer::new -> Queue::write_buffer_with -> AppRunner::paint`.

This is a host-side staging allocation failure, not evidence that a GPU texture
allocation failed. Exact allocation size was not present in that log.
The 0.19.0 -> 0.20.3 compatibility warning remains unresolved.

## Changes

- Web-only renderer upload belt minimum becomes 1 MiB rather than 32 MiB.
  Larger uploads still allocate their full required size. This does not split
  RRD, MessagePack, LZ4, Arrow chunks, images or chunk IDs.
- Vendored egui-wgpu 0.29.1 writes all mesh indices and vertices with original
  draw offsets through <=256 KiB writes on wasm32. Native code stays upstream.
  This bounds individual staging allocations, NOT total queued upload bytes:
  wgpu retains queued writes until submission. No vertices/indices are omitted.
- AccountingAllocator records last failed size/alignment without allocations.
  Failed alloc/zeroed alloc are no longer counted as successful; failed realloc
  retains the original allocation accounting as required by GlobalAlloc.
- Scalar WASM exports expose live allocation bytes and failure size/alignment.
  Fatal, frame and periodic metrics include these alongside linear memory size.

The native UI, data filtering/framing APIs, bounded segment loading, and fatal
error visibility are preserved. The 96 MiB memory_limit_bytes option remains a
Viewer reclamation target, not a hard linear-memory limit. Existing store GC
can evict old data; complete history preservation is NOT established by this
upload fix and must be tested separately.

## Verification status

- Full patched re_viewer release WASM build: exit 0.
- wasm-bindgen, wasm-opt, generated-glue preparation: passed.
- Complete `sh test.sh /sdcard/Download/977.rrd`: exit 0. Includes 1383
  container frames, 1382 data frames, 337 image markers, 319 bounded parts,
  72 Java segments, allocator churn and actual extracted Web upload loops.
- Shipped-WASM scalar live allocation metrics passed alloc/realloc/free checks.
  Test-only capped memory instances also proved exact failure size/alignment
  and correct retained allocation accounting after failed realloc.
- APK build and signature verification: exit 0.
- Copies in Download match APK SHA-256:
  `c6114c4d31b2347dd331d8707f5caa9368ec893c9ee7fa11e11b25642c2e1d5e`.

APK: `/sdcard/Download/RerunViewer-v66.apk` (also `RerunViewer.apk`).
Installed device package observed: v66. Device log confirms `asset_version=66`,
`app-started`, and functioning diagnostics: linear=89,260,032, live=33,697,725,
failed allocation=0. No v66 recording-import events were observed after the
attempted ACTION_VIEW launches; the Activity was merely brought to the front.
This is startup evidence only, not rendering/playback proof. Android full
playback, seeking/history, reopening and crash resolution remain unverified.
