# GitHub CI

Repository: https://github.com/Hgit-1/RerunViewer (private).

The Android workflow builds the bundled, precompiled Viewer WASM; it does not rebuild the external Rerun Rust workspace. It runs Java/JavaScript regression tests, builds an APK, verifies version/signature, and uploads APK + SHA256SUMS as an Actions artifact (30-day retention). Trigger on master pushes, v* tags, or workflow_dispatch.

The existing local signing keystore is stored only in the encrypted repository Actions secret SIGNING_KEYSTORE_BASE64. Do not commit keystores or tokens. Builds fail if that secret is absent rather than silently changing the signer. Current legacy keystore password/alias are those used by build.sh; migrate signing deliberately if replacing that key.

The complete test.sh suite requires the sibling patched rerun-source tree and .toolchain outputs and is currently run locally, not in this CI job. Android GPU/rendering and full-recording playback still require device tests.

Download signed artifacts from Actions after a successful run. Rebuilding does not imply bit-identical APK bytes, but signing identity is preserved.
