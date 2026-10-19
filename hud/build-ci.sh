#!/usr/bin/env bash
# Build the HUD APK without Gradle, using the Android SDK that ships with the runner.
set -euo pipefail
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "no Android SDK"; exit 1; }
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
[ -d "$BT" ] || BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
[ -f "$AJ" ] || AJ="$(ls "$SDK"/platforms/*/android.jar | sort -V | tail -1)"
OUT="hud/build-out"
rm -rf "$OUT"
mkdir -p "$OUT"/{flat,classes,gen,dex}
while IFS= read -r -d '' f; do "$BT/aapt2" compile "$f" -o "$OUT/flat"; done < <(find hud/app/src/main/res -type f -print0)
mapfile -t FLATS < <(find "$OUT/flat" -name '*.flat')
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJ" --manifest hud/app/src/main/AndroidManifest.xml \
  --java "$OUT/gen" --min-sdk-version 29 --target-sdk-version 30 "${FLATS[@]}"
mapfile -t SRCS < <(find hud/app/src/main/java "$OUT/gen" -name '*.java')
javac -source 11 -target 11 -encoding UTF-8 -nowarn -classpath "$AJ" -d "$OUT/classes" "${SRCS[@]}"
mapfile -t CLS < <(find "$OUT/classes" -name '*.class')
"$BT/d8" --lib "$AJ" --min-api 29 --output "$OUT/dex" "${CLS[@]}"
cd "$OUT"
zip -q -j base.apk dex/classes.dex
"$BT/zipalign" -f 4 base.apk aligned.apk
keytool -genkeypair -keystore debug.keystore -alias androiddebugkey -storepass android -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out tvhud.apk aligned.apk
"$BT/apksigner" verify --print-certs tvhud.apk | head -3
