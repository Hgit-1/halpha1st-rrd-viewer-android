#!/data/data/com.termux/files/usr/bin/sh
# Run after committing source and increasing AndroidManifest.xml versionCode.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT"
if [ -n "$(git status --porcelain)" ]; then
  echo 'Commit all changes before releasing.' >&2; exit 1
fi
DEST=/sdcard/Download/RerunViewer.apk
META=/sdcard/Download/RerunViewer-build.txt
for tool in git python3 aapt apksigner sha256sum; do command -v "$tool" >/dev/null; done
VERSION=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("AndroidManifest.xml").getroot().get("{http://schemas.android.com/apk/res/android}versionCode"))')
if [ -f "$DEST" ]; then
  OLD=$(aapt dump badging "$DEST" | python3 -c 'import sys,re; print(re.search(r"versionCode=\x27([0-9]+)\x27",sys.stdin.read()).group(1))')
  if [ "$VERSION" -le "$OLD" ]; then
    echo "versionCode $VERSION must exceed delivered version $OLD" >&2; exit 1
  fi
fi
# Never silently generate a different signing identity during a release.
[ -f rerun-debug.keystore ] || { echo 'Existing signing keystore required' >&2; exit 1; }
sh test.sh "$@"
sh build.sh
apksigner verify --verbose dist/RerunViewer.apk
# Avoid publishing an artifact that cannot update the previously delivered APK.
if [ -f "$DEST" ]; then
  OLD_CERT=$(apksigner verify --print-certs "$DEST" | grep 'certificate SHA-256 digest:')
  NEW_CERT=$(apksigner verify --print-certs dist/RerunViewer.apk | grep 'certificate SHA-256 digest:')
  [ "$OLD_CERT" = "$NEW_CERT" ] || { echo 'Signing identity changed' >&2; exit 1; }
fi
mkdir -p /sdcard/Download
TEMP=/sdcard/Download/.RerunViewer.apk.tmp
trap 'rm -f "$TEMP"' EXIT HUP INT TERM
cp dist/RerunViewer.apk "$TEMP"
[ "$(sha256sum dist/RerunViewer.apk | cut -d' ' -f1)" = "$(sha256sum "$TEMP" | cut -d' ' -f1)" ]
mv -f "$TEMP" "$DEST"
{
  git log -1 --format='commit=%H%nsubject=%s'
  aapt dump badging "$DEST" | head -1
  sha256sum "$DEST"
  printf '%s\n' 'Checked: structural tests, build, signature, copy hash. UI playback not tested by this script.'
} > "$META"
printf 'Delivered %s\nEvidence %s\n' "$DEST" "$META"
