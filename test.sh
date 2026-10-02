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
  "$ROOT/tests/io/rerun/viewer/shell/RrdFramesTest.java" \
  "$ROOT/tests/io/rerun/viewer/shell/RealRecordingTest.java"
cd "$ROOT"
java -Xmx256m -cp "$OUT" io.rerun.viewer.shell.RrdFramesTest
if [ "$#" -gt 0 ]; then
  java -Xmx256m -cp "$OUT" io.rerun.viewer.shell.RealRecordingTest "$1"
fi
