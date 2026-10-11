# RerunViewer v72

- versionCode: `72`
- versionName: `0.20.52-paged-textlog`
- APK SHA-256: `0e83163aa687067f0d9030e59a26eeb560b721ed2f9ecfffc1338c51acfac630`
- APK: `/sdcard/Download/RerunViewer-v72.apk`

## v71 failure evidence

The device reached part 127/319 on v71. The fatal allocation was:

```text
failed-alloc-bytes=8388608
failed-alloc-align=8
RawVec<re_space_view_text_log::visualizer_system::Entry>::grow_one
TextLogSystem::execute
```

The error is in derived TextLog rendering state, not in JPEG decoding or
source Arrow validity.

## v72 changes

- TextLog derived entries use bounded 16 KiB pages, avoiding a large contiguous
  `Vec<Entry>` growth allocation.
- The TextLog visualizer clears its previous derived rows each execution and
  queries a 10-second history plus 1-second lookahead. This bounds the active
  render working set; it does not rewrite, discard, or split source recording
  rows. Source-backed reload restores an older window when seeking.
- New source-backed insertion protection occurs before the next part's frames
  are sent.
- After each bounded source part, unprotected source residency is reclaimed;
  the original file remains the source of truth and protected active chunks are
  retained.
- Source-backed GC no longer additionally retains the latest revision of every
  unrelated component; explicit active Chunk IDs are the protection mechanism.
- Added execution tests for 480,597 paged entries, stable equal-key ordering,
  field/timeline payload retention, source-loader cleanup, pre-insert
  protection, and reclamation.

## Verification

Full local `test.sh /sdcard/Download/977.rrd` passed, including:

- 1383 complete source frames with byte-for-byte retention;
- 319 real bounded parts and 281 image-bearing parts;
- actual Rust decoder: 2766 messages and 2,053,224 rows;
- actual wasm32 paged TextLog test: 480,597 entries;
- source-loader execution and native RRF2 tests;
- APK v2/v3 signature verification.

The device currently reports versionCode 71. v72 still requires manual system
installer confirmation and a clean replay before it can be called stable.
