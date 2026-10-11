# RerunViewer v71

- versionCode: `71`
- versionName: `0.20.51-source-backed-seek`
- APK SHA-256: `52b29ef6c6118ac595a99a2c0b15899d43006a4193d20eb20f21de650e9c2b60`
- APK: `/sdcard/Download/RerunViewer-v71.apk`
- wasm SHA-256: `7278c56cfd80a2630869ffbe145c47cc3934075ea90a048a15d1af00b2f1d971`

## Fixes

The reproducible black-image mechanism is source chunks being deleted by the
Viewer RAM GC while the current time remains in the timeline. This does not
exclude an additional device GPU, view-configuration or compatibility issue.
The source JPEGs are
valid 1280x720 images and the Rust decoder reproduces them; after GC the
`/image/img` latest-at query returned no rows, and reloading the original chunk
restored it.

v71 therefore adds:

- source-backed, exact RRF2 frame indexing in a Web Worker;
- synchronous complete-frame decode with receiver-queue backpressure;
- exact decimal-string nanosecond seek state;
- active-query chunk protection from Viewer GC;
- source reload for missing chunks after memory purge and drag seeking;
- exact source timeline bounds that remain after resident chunks are purged;
- drag-seek coalescing: only the latest target is restored after a yield;
- no splitting of RRF2 frames, LZ4 blocks, MessagePack payloads, Arrow chunks,
  or chunk IDs.

The native Rerun UI and all recording data remain unchanged.

## Validation

Passed:

- complete `sh test.sh /sdcard/Download/977.rrd`;
- source seek selection and exact nanosecond tests;
- atomic frame reconstruction and failure-stop tests;
- 1383 encoded frame retention;
- 319 bounded parts / 281 image-bearing parts;
- actual Rust decoder: 2766 messages and 2,053,224 rows;
- APK v2/v3 signature verification.

The new APK has not yet been confirmed installed on the device. The last
Package Manager query still showed versionCode 70; the system installer must
complete the update before a v71 replay can be claimed.

The shipped WASM is the successful wasm-bindgen output. `wasm-opt` did not
finish within the available device build window, so this release must be
validated for Android memory behavior before production use.
