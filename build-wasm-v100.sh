#!/data/data/com.termux/files/usr/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT/../rerun-source/rerun-0.20.3"
exec cargo run -p re_dev_tools -- build-web-viewer --release --target no-modules-base --no-default-features --features grpc,map_view -o "$ROOT/assets/rerun"
