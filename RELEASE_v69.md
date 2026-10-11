# RerunViewer v69

- versionCode: `69`
- versionName: `0.20.49-textlog-inplace-sort`

## Device issue addressed

v68 reached part 8/319 and failed in `TextLogSystem::execute` while requesting
an approximately 1.88 MiB allocation. The failure occurred during the stable
slice sort of the derived TextLog entries. Stable slice sorting may allocate a
temporary buffer proportional to the `Entry` vector, which is a significant
WASM peak for this recording.

v69 uses `sort_unstable_by_key` for the derived TextLog snapshot. This removes
that large temporary buffer while retaining every TextLog entry, body, level,
color, entity, and timeline value. Equal-time log rows have no semantic order
requirement in the native TextLog table, so unstable ordering for equal times
is acceptable.

The v68 atomic RRF2 frame preservation and single-RecordBatch filter fix remain
included. No encoded recording data is deleted or truncated.

## Validation status

The patched Viewer WASM release build, wasm-bindgen, wasm-opt, and generated
JavaScript glue completed successfully. Full local regression and fresh device
replay are required before declaring the crash fixed.
