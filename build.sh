#!/data/data/com.termux/files/usr/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build"
DIST="$ROOT/dist"
SDK_FRAMEWORK=${SDK_FRAMEWORK:-/system/framework/framework-res.apk}
ANDROID_JAR=${ANDROID_JAR:-$ROOT/android.jar}
AAPT=${AAPT:-aapt}
DX=${DX:-dx}
JAVAC=${JAVAC:-javac}
ZIP=${ZIP:-zip}
JAVA_HOME=${JAVA_HOME:-/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk}
if [ -x "$JAVA_HOME/bin/javac" ]; then
  PATH="$JAVA_HOME/bin:$PATH"
  export JAVA_HOME PATH
fi

rm -rf "$BUILD" "$DIST"
mkdir -p "$BUILD/gen" "$BUILD/obj" "$BUILD/classes" "$DIST"

if [ ! -f "$SDK_FRAMEWORK" ] || [ ! -f "$ANDROID_JAR" ]; then
  echo "Android framework files were not found" >&2
  exit 1
fi

"$AAPT" package -f -m \
  -M "$ROOT/AndroidManifest.xml" \
  -S "$ROOT/res" \
  -I "$SDK_FRAMEWORK" \
  -J "$BUILD/gen" \
  -F "$BUILD/resources.ap_" \
  -A "$ROOT/assets" \
  -0 html -0 js -0 wasm

"$JAVAC" -source 8 -target 8 -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  "$BUILD/gen/io/rerun/viewer/shell/R.java" \
        "$ROOT/src/io/rerun/viewer/shell/MainActivity.java" \
        "$ROOT/src/io/rerun/viewer/shell/RrdFrames.java" \
        "$ROOT/src/io/rerun/viewer/shell/RrdPreview.java" \
        "$ROOT/src/io/rerun/viewer/shell/RrdLiveSession.java"

"$DX" --dex --output="$BUILD/classes.dex" "$BUILD/classes"

cd "$BUILD"
# aapt's packaged archive contains the manifest/resources; add dex without recompressing.
cp "$BUILD/resources.ap_" "$BUILD/unsigned.apk"
"$ZIP" -q -0 "$BUILD/unsigned.apk" classes.dex

if command -v zipalign >/dev/null 2>&1; then
  zipalign -f 4 "$BUILD/unsigned.apk" "$DIST/RerunViewer-unsigned.apk"
else
  cp "$BUILD/unsigned.apk" "$DIST/RerunViewer-unsigned.apk"
fi

KEYSTORE="$ROOT/rerun-debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias rerun -keyalg RSA -keysize 2048 -validity 10000 \
    -dname 'CN=Rerun Viewer, OU=Local, O=Rerun, L=Local, S=Local, C=CN' >/dev/null 2>&1
fi

if command -v apksigner >/dev/null 2>&1; then
  apksigner sign --ks "$KEYSTORE" --ks-pass pass:android \
    --key-pass pass:android --out "$DIST/RerunViewer.apk" "$DIST/RerunViewer-unsigned.apk"
else
  cp "$DIST/RerunViewer-unsigned.apk" "$DIST/RerunViewer.apk"
  jarsigner -keystore "$KEYSTORE" -storepass android -keypass android \
    -sigalg SHA256withRSA -digestalg SHA-256 "$DIST/RerunViewer.apk" rerun >/dev/null
fi

if command -v apksigner >/dev/null 2>&1; then apksigner verify --verbose "$DIST/RerunViewer.apk"; fi
ls -lh "$DIST/RerunViewer.apk"
