# RerunViewer v68

- versionCode: `68`
- versionName: `0.20.48-single-batch-arrow`
- APK SHA-256: `038a691539679b83f7cac52e11fb3dc3c18a62c691b7503272e881fe1cd0c203`

## Changes

- Filtered recording import now preserves every original encoded RRF2 data frame byte-for-byte.
- No Arrow rows, image blobs, timelines, entities, chunk IDs, LZ4 blocks, or MessagePack payloads are removed or reserialized.
- This avoids producing invalid Arrow IPC streams containing multiple `RecordBatch` values in one Rerun `ArrowMsg`.
- Added a native Rust serde/Arrow/Chunk validation probe to the real-recording regression test.
- Decoder/store rejection messages are surfaced as fatal diagnostics instead of being silently treated as successful progress.

## Validation

- `sh test.sh /sdcard/Download/977.rrd`: passed.
- Real recording: 1383 container frames, 1382 data frames, 319 bounded parts.
- Every 1383 original encoded frame was retained in order by the bundled transform.
- Rust serde/Arrow/Chunk validation: 2766 original/submitted messages accepted, 2,053,224 decoded rows.
- APK build and v2/v3 signature verification: passed.

## Device status

The APK was copied to `/sdcard/Download/RerunViewer-v68.apk` and `/sdcard/Download/RerunViewer.apk`. The current installed package remains versionCode 67 because the Termux process does not have package-installer access; opening the APK was requested with `termux-open`.

The v67 device log still ends at the historical part 110 allocation failure. A successful v68 build/test is not proof of a successful Android replay. After installation, start `/sdcard/Download/977.rrd` and require a log containing `native-start version=68`, `asset_version=68`, all `filtered-part=...` entries through `319/319`, and no fatal/panic/renderer-gone entry.

## Known limitation

`send_rrd_to_channel` still has no decoder-drained acknowledgement. The 100 ms delay is pacing only. WASM/WebView/GPU peak memory and complete native playback/history remain real-device validation items.
