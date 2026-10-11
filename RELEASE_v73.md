# RerunViewer v73

- versionCode: `73`
- versionName: `0.20.53-direct-seek`
- APK SHA-256: `6eb9feb1dbf42d1bf5fd727e3acdab1359a2b676783cabc4867443b007c0d7d6`
- APK: `/sdcard/Download/RerunViewer-v73.apk`

## Direct seek optimization

v72 still loaded the source recording sequentially and used the resulting
resident windows while the user dragged the time panel. A large jump could
therefore make the Viewer pass through several old windows before reaching the
requested time.

v73 changes source-backed loading to two phases:

1. The Worker scans all bounded parts for compact frame metadata and publishes
   exact timeline bounds. No recording frame is submitted to the Viewer during
   this phase.
2. When a target time is selected, the loader selects only the target's
   overlapping chunks plus the necessary latest-at fallback chunks, groups them
   by source part, and fetches those parts directly. Intermediate windows are
   not submitted.

Additional behavior:

- Source metadata is sent once.
- A small 4 MiB LRU retains recently fetched bounded parts.
- The active source Chunk IDs are protected before reclamation.
- Reclamation occurs only when the requested working set changes, rather than
  on every idle poll.
- Target changes are checked after I/O and before frame submission; stale drag
  targets are abandoned.
- Complete RRF2 frames remain atomic. No source frame, Arrow chunk, payload,
  or Chunk ID is split or discarded.
- UI status reports the initial metadata indexing separately from on-demand
  source loading.

## Verification

The current local suite passes, including:

- 1383 complete source frames and byte-for-byte retention;
- 319 bounded parts and 281 image-bearing parts;
- actual Rust decoder: 2766 messages and 2,053,224 rows;
- paged wasm32 TextLog test with 480,597 rows;
- direct-seek selection and stale-target cancellation tests;
- source-loader execution test with atomic frames, metadata-once behavior,
  protection, reclamation, and cleanup;
- APK v2/v3 signature verification.

The package manager still needs to confirm installation of version 73, followed
by real-device testing of a large drag jump and normal playback. Local tests do
not prove Android WebView scheduling or GPU behavior.
