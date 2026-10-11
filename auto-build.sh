#!/data/data/com.termux/files/usr/bin/sh
# Build and publish a new RerunViewer APK whenever project inputs change.
# This is intentionally separate from release.sh: it is for local automatic
# APK generation, not for a Git release.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
STATE_DIR="$ROOT/../.toolchain"
STATE="$STATE_DIR/rerun-viewer-auto-build.sha256"
LOG="$STATE_DIR/rerun-viewer-auto-build.log"
DEST=/sdcard/Download/RerunViewer.apk
META=/sdcard/Download/RerunViewer-auto-build.txt
INTERVAL=${AUTO_BUILD_INTERVAL:-5}

mkdir -p "$STATE_DIR"

project_hash() {
    (
        cd "$ROOT"
        find . -type f \
            ! -path './.git/*' \
            ! -path './build/*' \
            ! -path './dist/*' \
            ! -path './tools/rrd-filter/node_modules/*' \
            ! -name '*.apk' \
            ! -name '*.idsig' \
            -print | sort | while IFS= read -r f; do
                sha256sum "$f"
            done
    ) | sha256sum | awk '{print $1}'
}

version_code() {
    python3 - "$ROOT/AndroidManifest.xml" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'android:versionCode="([0-9]+)"', s)
if not m:
    raise SystemExit('AndroidManifest.xml has no versionCode')
print(m.group(1))
PY
}

bump_version() {
    python3 - "$ROOT/AndroidManifest.xml" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
def bump(m):
    return m.group(1) + str(int(m.group(2)) + 1) + m.group(3)
out, n = re.subn(r'(android:versionCode=")([0-9]+)(")', bump, s, count=1)
if n != 1:
    raise SystemExit('could not update versionCode')
open(p, 'w', encoding='utf-8').write(out)
PY
}

build_once() {
    old=$(version_code)
    bump_version
    new=$(version_code)
    {
        printf '%s build start versionCode=%s\n' "$(date '+%Y-%m-%d %H:%M:%S%z')" "$new"
        sh "$ROOT/build.sh"
        mkdir -p /sdcard/Download
        tmp="$DEST.partial"
        rm -f "$tmp"
        cp "$ROOT/dist/RerunViewer.apk" "$tmp"
        cmp -s "$ROOT/dist/RerunViewer.apk" "$tmp"
        mv -f "$tmp" "$DEST"
        sha=$(sha256sum "$DEST" | awk '{print $1}')
        {
            printf 'versionCode=%s\n' "$new"
            printf 'sha256=%s\n' "$sha"
            printf 'built=%s\n' "$(date '+%Y-%m-%d %H:%M:%S%z')"
            printf 'source=RerunViewer/auto-build.sh\n'
        } > "$META"
        printf '%s build success versionCode=%s apk=%s sha256=%s\n' "$(date '+%Y-%m-%d %H:%M:%S%z')" "$new" "$DEST" "$sha"
    } >> "$LOG" 2>&1 || {
        # Keep the incremented version: a failed build must not silently reuse
        # an APK version that may already have been delivered.
        printf '%s build FAILED versionCode=%s (previous=%s)\n' "$(date '+%Y-%m-%d %H:%M:%S%z')" "$new" "$old" >> "$LOG"
        return 1
    }
}

run_loop() {
    previous=''
    while :; do
        current=$(project_hash)
        if [ "$current" != "$previous" ]; then
            if [ -f "$STATE" ] && [ "$(cat "$STATE")" = "$current" ]; then
                previous=$current
            else
                if build_once; then
                    current=$(project_hash)
                    printf '%s\n' "$current" > "$STATE"
                    previous=$current
                else
                    # Retry on the next interval without rebuilding forever in
                    # a tight loop. The log contains the actual build failure.
                    previous=''
                fi
            fi
        fi
        sleep "$INTERVAL"
    done
}

case "${1:-watch}" in
    once)
        current=$(project_hash)
        if [ -f "$STATE" ] && [ "$(cat "$STATE")" = "$current" ]; then
            printf 'No source change; existing APK is current.\n'
            exit 0
        fi
        build_once
        printf '%s\n' "$(project_hash)" > "$STATE"
        ;;
    watch)
        run_loop
        ;;
    *)
        echo "usage: $0 [once|watch]" >&2
        exit 2
        ;;
esac
