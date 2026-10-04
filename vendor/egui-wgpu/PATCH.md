# Local egui-wgpu patch

Upstream: egui-wgpu 0.29.1, https://github.com/emilk/egui/tree/0.29.1/crates/egui-wgpu
License: MIT OR Apache-2.0 (see Cargo.toml and upstream repository licenses).
Source copied from the Cargo registry; upstream registry files are otherwise
unchanged except src/renderer.rs.

Web-only mesh upload loops write <=256 KiB per Queue::write_buffer call instead
of one full-frame Queue::write_buffer_with. Native loops retain upstream code.
No mesh bytes, draw slices, callbacks, viewport behavior or UI are removed.
The queue still retains pending writes until submission: this patch bounds
individual contiguous staging allocations, not total per-frame staging memory.

To use when rebuilding Rerun 0.20.3, add under [patch.crates-io]:

    egui-wgpu = { path = "../../RerunViewer/vendor/egui-wgpu" }

Apply web_upload_memory.patch and textlog_direct_timelines.patch to Rerun source
before rebuilding. web_upload_memory.patch includes the pre-existing startup
memory-limit option, so apply it to the upstream files rather than blindly
stacking over a file that already contains that option.

Regression: node tests/web-upload-test.mjs extracts and executes both actual
patched Web loops with a mocked queue. This validates bytes, offsets, alignment,
empty meshes and callbacks; it is not an Android WebGL playback test.
