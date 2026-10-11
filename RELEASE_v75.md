# RerunViewer v75 — file-open regression candidate

- versionCode: 75
- versionName: `0.20.55-file-open-stability`
- APK: `/sdcard/Download/RerunViewer-v75.apk`
- SHA-256: `7b5a297c63db2e6cd99e16053dd9ade49789716bca6c01f4d574eca621296e63`

## Evidence and limits

Installed v74 device logs are saved at `.toolchain/v74-device-file-open.log`.
They show source indexing progressing to 82/319, repeated Viewer startup, and
later attempts stopping near the beginning of indexing. No native fatal stack,
WASM failed allocation, or renderer-gone event is present in the captured log.
The user clarified that rerun.io script output is acceptable and not the reported
failure. It is NOT treated as the cause and external navigation remains allowed.
The exact reason for returning to the welcome screen is not yet proven.

## Candidate changes

- Submit the first bounded part (original metadata and original frames) and
  drain the Viewer receiver before the index-only scan. v74 submitted nothing
  until all 319 parts were indexed, leaving the welcome page active throughout.
- Keep direct target seeking after the full index is complete. Intermediate
  future frames are not submitted during the scan.
- Do not duplicate one-shot index-only metadata in the Worker cache.
- Restart the Worker every 16 indexed parts as a conservative lifetime bound
  for JS parser/temporary allocations. Memory retention was not demonstrated as
  the specific device failure cause.
- Yield between scan parts.
- Stop silent automatic welcome-screen recovery on WebView renderer exit.
  Show the real stage and didCrash flag and allow explicit user recovery.
- Add file-picker results and page-start/page-finished diagnostics, plus
  Android 11+ historical process exit information when the platform provides it.
- No source recording frame, payload, ID or row is rewritten/discarded.

## Verification

`.toolchain/v75-tests.log`: full local suite PASS, including bootstrap activation
before indexing, direct seeking, cancellation, Worker responses, actual wasm32
TextLog rows, and original recording reconstruction.

Actual Worker handler executed on all real source parts:
319 parts, 1382 original chunks, exact decimal-string time ranges, 20 bounded
Worker lifetimes: PASS.

`.toolchain/v75-final-apk.log`: Android build and v2/v3 signatures PASS.

Shipped WASM is unchanged from v74. This release is a mitigation/diagnostic
candidate, not a verified fix for Android file-open failure. Install v75 and
retry 977.rrd once; inspect source-bootstrap-complete, source-index-complete,
page-start, renderer-gone, and previous-process-exit evidence before concluding
that the regression is resolved.
