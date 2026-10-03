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
if [ "$#" -gt 0 ] && [ -f "$1" ] && [ -f "../.toolchain/recording-probe/oracle.json" ]; then
  node tests/filter-real-test.js "$1" ../.toolchain/recording-probe/oracle.json
fi
if [ "$#" -gt 0 ]; then
  java -Xmx256m -cp "$OUT" io.rerun.viewer.shell.RealRecordingTest "$1"
fi
