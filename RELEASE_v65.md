# v65 — direct TextLog timeline extraction

The v63 device evidence still used asset/version 63 and crashed while
`iter_component_timepoints` created a per-row `TimePoint` (`BTreeMap<Timeline,
TimeInt>`). v65 changes the visualization path to read the Chunk time columns
directly, emitting one compact `Arc<[(Timeline, TimeInt)]>` per valid source row.
All component values in that source row share it. No recording rows, timelines,
TextLog bodies, images, Boxes2D, or other entities are removed.

The Viewer UI and filtering/framing APIs remain unchanged. This is an
allocation-focused build, not a claim of successful Android playback until the
new version is installed and replayed on the device.

## Local evidence

- `cargo check -p re_chunk --target wasm32-unknown-unknown --release`: passed.
- Full `re_viewer` WASM release build: passed.
- wasm-bindgen and wasm-opt: passed.
- The direct iterator probe exercised 0, 1, 3, 5 and 12 timelines, dense and
  sparse component rows, empty component batches, missing components, iterator
  exhaustion, Arc sharing, and equivalence with the old TimePoint iterator:
  result `0`.
- Full `RerunViewer/test.sh /sdcard/Download/977.rrd`: passed, including 1383
  container frames, 1382 data frames, 337 image markers, 319 bounded parts and
  72 Java real-recording segments.
- APK SHA-256:
  `0ed45f4918d6ebf8170fe57aafd2b773b3a0c95e7ba3759907a5771c80253a76`.

The current installed package is still v63. Android Package Manager rejects
Termux's direct install because the process is not the shell/system UID. The
v65 APK is available at `/sdcard/Download/RerunViewer-v65.apk`; opening it via
the Android package-installer UI requires the user to confirm the update.
