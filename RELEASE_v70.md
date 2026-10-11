# RerunViewer v70

- versionCode: `70`
- versionName: `0.20.50-textlog-compact-stable-sort`

## Memory change

The device v68 trace failed in `TextLogSystem::execute` while allocating
1,880,960 bytes. The trace does not distinguish vector growth from sorting;
stable sort's full-Entry temporary buffer is one peak allocation eliminated by
this build, not a proven diagnosis of the exact failing instruction. v70 retains the required stable
ordering for equal timestamps but sorts an index permutation instead of copying
the full `Entry` vector. The permutation uses one `usize` per entry and applies
cycles in place with safe `Vec::swap`; it has exhaustive duplicate-key and large
random/reverse tests against Rust's stable `sort_by_key`.

No TextLog body, level, color, entity, timeline, source row, encoded RRD frame,
image, Boxes2D item, depth sample, or chunk ID is discarded. The v68 atomic
RRF2/single-RecordBatch filter changes remain included.

## Validation and device status

- Patched Viewer WASM release build, wasm-bindgen and wasm-opt: passed.
- Safe compact sort: all 3-key combinations through length 9 and up to 65,536
  random/reverse entries match stable sort. Native standalone and wasm32 probes passed.
- `sh test.sh /sdcard/Download/977.rrd`: passed, including exact encoded-frame preservation.
- Actual Rust decoder: 2766 original/submitted messages accepted, 2,053,224 rows.
- Android build/signature verification: passed (v2/v3).
- APK SHA-256: `b6208542797b84b6aea33eaa95dccc656670b8a8c276dddd81277c1f66cd7b53`
- APK: `/sdcard/Download/RerunViewer-v70.apk`.

Installed version was still 68 at the last check. Device replay, playback,
rendering, seeking/history and reopening are not yet validated. Termux cannot
silently install packages or confirm system installer dialogs in this session.
The earlier claim that visualizers persist and accumulate entries across frames
was incorrect: the factory creates new instances each frame. No `entries.clear`
change is shipped. v69 was an intermediate unstable-sort candidate; use v70 to
preserve equal-time ordering.
