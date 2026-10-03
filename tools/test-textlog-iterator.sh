#!/data/data/com.termux/files/usr/bin/sh
# Requires the patched Rerun release rlibs, so kept separate from routine APK tests.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET=${RERUN_WASM_TARGET:-$ROOT/../rerun-source/rerun-0.20.3/target_wasm}
W=$TARGET/wasm32-unknown-unknown/release/deps
H=$TARGET/release/deps
mkdir -p "$ROOT/build"
OUT=$(mktemp -d "$ROOT/build/textlog-probe.XXXXXX")
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
# Use the newest built graph by default; set explicit paths when multiple graphs
# have been built recently. rustc fails rather than mixing incompatible dependencies.
CHUNK=${RERUN_CHUNK_RLIB:-$(ls -t "$W"/libre_chunk-*.rlib 2>/dev/null | head -1)}
TYPES=${RERUN_TYPES_CORE_RLIB:-$(ls -t "$W"/libre_types_core-*.rlib 2>/dev/null | head -1)}
ARROW=${RERUN_ARROW_RLIB:-$(ls -t "$W"/libre_arrow2-*.rlib 2>/dev/null | head -1)}
for file in "$CHUNK" "$TYPES" "$ARROW"; do
  [ -f "$file" ] || { echo "Missing matching patched dependency: $file" >&2; exit 1; }
done
rustc --edition 2021 --target wasm32-unknown-unknown --crate-type cdylib \
  -C opt-level=2 -C panic=abort "$ROOT/tests/textlog-iterator.rs" \
  -L "dependency=$W" -L "dependency=$H" \
  --extern "re_chunk=$CHUNK" --extern "re_types_core=$TYPES" \
  --extern "re_arrow2=$ARROW" -o "$OUT/test.wasm"
node "$ROOT/tests/textlog-iterator-test.js" "$OUT/test.wasm"
