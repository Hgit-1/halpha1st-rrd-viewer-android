#!/bin/sh
# Keep the license copies shipped inside the APK in sync with the repository
# and verify that the required notices are still present.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

SYNC=0
if [ "${1:-}" = "--sync" ]; then SYNC=1; fi

fail() { echo "license check: $*" >&2; exit 1; }

for required in LICENSE THIRD_PARTY_NOTICES.md \
  LICENSES/Rerun-MIT.txt LICENSES/Apache-2.0.txt LICENSES/apache-arrow-NOTICE.txt \
  LICENSES/msgpackr-MIT.txt LICENSES/tslib-0BSD.txt LICENSES/egui-MIT.txt; do
  [ -s "$required" ] || fail "missing or empty $required"
done

grep -q 'Copyright (c) 2022 Rerun Technologies AB' LICENSES/Rerun-MIT.txt \
  || fail 'Rerun copyright notice missing from LICENSES/Rerun-MIT.txt'
grep -q 'Apache License' LICENSES/Apache-2.0.txt \
  || fail 'Apache-2.0 text missing'
grep -q 'The Apache Software Foundation' LICENSES/apache-arrow-NOTICE.txt \
  || fail 'Apache Arrow NOTICE text missing'
grep -q 'Copyright (c) 2020 Kris Zyp' LICENSES/msgpackr-MIT.txt \
  || fail 'msgpackr copyright notice missing'
grep -q 'Copyright (c) Microsoft Corporation' LICENSES/tslib-0BSD.txt \
  || fail 'tslib copyright notice missing'
grep -q 'Copyright (c) 2018-2021 Emil Ernerfeldt' LICENSES/egui-MIT.txt \
  || fail 'egui copyright notice missing'
grep -q 'Hgit-1' LICENSE || fail 'project copyright notice missing from LICENSE'

for name in LICENSE THIRD_PARTY_NOTICES.md Rerun-MIT.txt Apache-2.0.txt \
  apache-arrow-NOTICE.txt msgpackr-MIT.txt tslib-0BSD.txt egui-MIT.txt; do
  if [ "$SYNC" -eq 1 ]; then
    case "$name" in
      LICENSE|THIRD_PARTY_NOTICES.md) cp "$name" "assets/legal/$name" ;;
      *) cp "LICENSES/$name" "assets/legal/$name" ;;
    esac
  fi
  [ -f "assets/legal/$name" ] || fail "APK asset assets/legal/$name is missing"
  src=$name
  case "$name" in LICENSE|THIRD_PARTY_NOTICES.md) ;; *) src="LICENSES/$name" ;; esac
  cmp -s "$src" "assets/legal/$name" \
    || fail "assets/legal/$name differs from $src; run tools/check-legal-assets.sh --sync"
done

echo 'license check: PASS'
