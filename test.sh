#!/data/data/com.termux/files/usr/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVA_HOME=${JAVA_HOME:-/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk}
if [ -x "$JAVA_HOME/bin/javac" ]; then
  PATH="$JAVA_HOME/bin:$PATH"
  export JAVA_HOME PATH
fi
mkdir -p "$ROOT/build"
OUT=$(mktemp -d "$ROOT/build/regression.XXXXXX")
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
javac -source 8 -target 8 -encoding UTF-8 -d "$OUT" \
  "$ROOT/src/io/rerun/viewer/shell/RrdFrames.java" \
  "$ROOT/src/io/rerun/viewer/shell/RrdPreview.java" \
  "$ROOT/tests/io/rerun/viewer/shell/RrdPreviewTest.java" \
  "$ROOT/tests/io/rerun/viewer/shell/RrdFramesTest.java" \
  "$ROOT/tests/io/rerun/viewer/shell/RealRecordingTest.java"
cd "$ROOT"
java -Xmx256m -cp "$OUT" io.rerun.viewer.shell.RrdFramesTest
java -Xmx64m -cp "$OUT" io.rerun.viewer.shell.RrdPreviewTest "$@"
node tests/mobile-test.js
node tests/filter-test.js
node tests/stream-test.js
node tests/source-loader-test.js
node tests/source-worker-test.js
node tests/source-loader-direct-seek-test.js
node tests/source-loader-jump-test.js
node tests/source-loader-playback-test.js
node tests/wasm-allocator-test.js
node tests/web-upload-test.mjs
node tests/rgb-upload-test.mjs
rustc --edition 2021 --crate-type cdylib --target wasm32-unknown-unknown \
  -C opt-level=2 -C panic=abort tests/textlog-sort.rs \
  -o ../.toolchain/textlog-sort.wasm
node tests/textlog-sort-test.js
rustc --edition 2021 --test ../rerun-source/rerun-0.20.3/crates/viewer/re_space_view_text_log/src/paged_entries.rs \
  -o ../.toolchain/paged-entries-tests
../.toolchain/paged-entries-tests
rustc --edition 2021 --crate-type cdylib --target wasm32-unknown-unknown \
  -C opt-level=2 -C panic=abort tests/textlog-pages.rs \
  -o ../.toolchain/textlog-pages.wasm
node tests/textlog-pages-test.js
if [ "$#" -gt 0 ] && [ -f "$1" ] && [ -f "../.toolchain/recording-probe/oracle.json" ]; then
  node tests/filter-real-test.js "$1" ../.toolchain/recording-probe/oracle.json
  node tests/source-worker-real-test.js "$1" ../.toolchain/recording-probe/oracle.json
fi
if [ "$#" -gt 0 ]; then
  java -Xmx256m -cp "$OUT" io.rerun.viewer.shell.RealRecordingTest "$1"
fi
