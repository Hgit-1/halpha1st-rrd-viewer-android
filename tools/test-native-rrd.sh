#!/data/data/com.termux/files/usr/bin/sh
# Uses actual patched release dependency graph and wasm-bindgen for host RNG.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET=${RERUN_WASM_TARGET:-$ROOT/../rerun-source/rerun-0.20.3/target_wasm}
W=$TARGET/wasm32-unknown-unknown/release/deps
H=$TARGET/release/deps
mkdir -p "$ROOT/build"
OUT=$(mktemp -d "$ROOT/build/native-decode.XXXXXX")
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
CHUNK=${RERUN_CHUNK_RLIB:-$(ls -t "$W"/libre_chunk-*.rlib | head -1)}
LOG=${RERUN_LOG_TYPES_RLIB:-$(ls -t "$W"/libre_log_types-*.rlib | head -1)}
MSG=${RERUN_RMP_RLIB:-$(ls -t "$W"/librmp_serde-*.rlib | head -1)}
BINDGEN=${WASM_BINDGEN:-$ROOT/../.toolchain/cargo-home/bin/wasm-bindgen}
rustc --edition 2021 --target wasm32-unknown-unknown --crate-type cdylib \
  -C opt-level=2 -C panic=abort "$ROOT/tests/rrd-native-decode.rs" \
  -L "dependency=$W" -L "dependency=$H" \
  --extern "re_chunk=$CHUNK" --extern "re_log_types=$LOG" \
  --extern "rmp_serde=$MSG" -o "$OUT/probe.wasm"
"$BINDGEN" --target nodejs --out-dir "$OUT" --out-name decode "$OUT/probe.wasm"
cd "$ROOT"
RERUN_NATIVE_DECODE_JS="$OUT/decode.js" node tests/filter-real-test.js \
  "${1:?Pass recording path}" "${2:-../.toolchain/recording-probe/oracle.json}"
