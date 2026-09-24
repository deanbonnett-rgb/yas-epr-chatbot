#!/usr/bin/env bash
# Builds a signed APK without Gradle, using the Debian/Ubuntu Android tools:
#   apt-get install aapt dalvik-exchange zipalign apksigner
# plus a JDK. The Android 34 platform jar is downloaded on first run.
set -euo pipefail
cd "$(dirname "$0")"

SDK_JAR=build/android-34.jar
OUT=release/LotteryPredictor.apk
KEYSTORE=keystore/predictor.jks
KEY_PASS=euromillions

mkdir -p build release
if [ ! -f "$SDK_JAR" ]; then
  curl -fsSL -o "$SDK_JAR" https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar
fi
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEY_PASS" -keypass "$KEY_PASS" -alias predictor \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=EuroMillions Predictor"
fi

rm -rf build/classes build/apk build/gen && mkdir -p build/classes build/apk build/gen

# 1. Compile resources + manifest (also generates R.java).
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -A assets -I "$SDK_JAR" \
  -F build/apk/unaligned.apk

# 2. Compile Java to Java 8 bytecode against the platform jar.
javac -source 8 -target 8 -bootclasspath "$SDK_JAR" -Xlint:-options -d build/classes \
  $(find src build/gen -name '*.java')

# 3. Convert to Dalvik bytecode and add to the APK.
dalvik-exchange --dex --min-sdk-version=21 --output=build/apk/classes.dex build/classes
(cd build/apk && aapt add -f unaligned.apk classes.dex >/dev/null)

# 4. Align and sign (v1 + v2 + v3 signatures).
zipalign -f -p 4 build/apk/unaligned.apk build/apk/aligned.apk
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
  --min-sdk-version 21 --v4-signing-enabled false --out "$OUT" build/apk/aligned.apk
apksigner verify --print-certs "$OUT" | head -1
echo "Built $OUT ($(du -h "$OUT" | cut -f1))"
