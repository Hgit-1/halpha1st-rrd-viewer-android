# RerunViewer v74 — direct seek candidate

- versionCode: `74`
- versionName: `0.20.54-direct-seek-fast-state`
- APK: `/sdcard/Download/RerunViewer-v74.apk`
- APK SHA-256: `de0e0d5d93cda4464384d9882a0f0714b6ce8a1d68bf3fe27f59f07d249550b2`
- WASM SHA-256: `0143c48c6904d793d52e7de34cf4d5e3e65dda3c316d4744c1a95f15cacf180f`

## Changes from installed v72

- Index all complete bounded source parts in the Worker before publishing full
  timeline bounds. Index-only responses transfer metadata, not source bytes.
  This adds an initial indexing wait but removes progressive moving bounds and
  sequential submission of unrelated windows to the Viewer.
- Select source chunks directly for the current target, including static data,
  overlapping chunks, latest-at component fallback, and the existing TextLog
  history. JPEG/depth history is not preloaded just for TextLog.
- Group restore work by source part; active image parts are prioritized. Each
  needed part is read once per restore rather than repeatedly per entity.
- A 4 MiB encoded-part LRU helps adjacent/repeated seeks. It does not change the
  source or the 8 MiB per-response bound.
- Protect the target working set and reclaim obsolete residency before loading.
  Do not run full reclamation on every unchanged idle poll.
- Check target changes both before and after part I/O, including paused seeks
  after pointer release. Immediately reschedule the latest target on stale work.
- Drain the receiver between bounded batches. Queue-empty is not a GPU/render
  completion acknowledgement.
- Make `mobile_playback_state` lightweight: no per-poll image latest-at query.
- Keep the native Rerun UI. Source RRF2/LZ4/MessagePack/Arrow frames and chunk IDs
  are unchanged, and remain accessible from the original file.

## Validation

- release Rust WASM build: PASS (`.toolchain/v74-wasm-final.log`)
- complete local suite: PASS (`.toolchain/v74-final-tests.log`)
- direct-seek execution tests: fixed bounds, metadata-only scan, target-first
  loading without intermediate windows, post-I/O cancellation, LRU reuse: PASS
- real recording: 1383 original frames, 319 bounded parts, 281 image-bearing
  parts; byte-for-byte source frame retention: PASS
- real Rust serde/Arrow/Chunk decoder: 2766 messages, 2053224 rows: PASS
  (`.toolchain/v74-native.log`)
- APK v2/v3 signatures and version metadata: PASS (`.toolchain/v74-apk.log`)

## Limits / device verification

This is an optimization candidate, not a measured Android performance result.
Initial full indexing cost, real WebView/GPU memory, playback, timeline stepping,
reopening and rapid drag latency need device testing. The compact index retains
chunk time ranges, not every exact tick, so complete timeline stepping/history
semantics are not proven by direct-seek tests. TextLog's v72 bounded rendering
history remains unchanged; complete source rows are not discarded. The WASM is
bindgen output, without a successful wasm-opt pass.

Look for `native-start version=74`, `asset_version=74`,
`source-index-complete`, and `source-seek ... parts=... elapsed-ms=...` in logs.
