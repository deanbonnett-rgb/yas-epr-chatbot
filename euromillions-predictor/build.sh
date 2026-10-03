#!/usr/bin/env bash
# Builds a signed APK without Gradle, using the Debian/Ubuntu Android tools:
#   apt-get install aapt dalvik-exchange zipalign apksigner
# plus a JDK. The Android 34 platform jar, the tess-two OCR library (Tesseract 3.05, Apache 2.0)
# and Tesseract's English data (Apache 2.0) are downloaded on first run and checked by SHA-256.
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
fetch() { # url file sha256
  if [ ! -f "$2" ]; then
    for repo in "$1" "${1/repo.maven.apache.org/repo1.maven.org}"; do curl -fsSL -o "$2" "$repo" && break || sleep 3; done
  fi
  echo "$3  $2" | sha256sum -c --quiet - || { rm -f "$2"; echo "Checksum mismatch for $2" >&2; exit 1; }
}
TESS_AAR=build/tess-two-9.1.0.aar
TESS_DATA=build/eng.traineddata
fetch https://repo.maven.apache.org/maven2/com/rmtheis/tess-two/9.1.0/tess-two-9.1.0.aar "$TESS_AAR" \
  99f307367539699e3fddd09e0f27f24af14655c545b7b8ccea29277266c10ad8
fetch https://raw.githubusercontent.com/tesseract-ocr/tessdata/3.04.00/eng.traineddata "$TESS_DATA" \
  c0515c9f1e0c79e1069fcc05c2b2f6a6841fb5e1082d695db160333c1154f06d
rm -rf build/tess && mkdir -p build/tess && unzip -qo "$TESS_AAR" classes.jar 'jni/arm64-v8a/*' 'jni/armeabi-v7a/*' -d build/tess
TESS_JAR=build/tess/classes.jar

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
javac -source 8 -target 8 -bootclasspath "$SDK_JAR" -cp "$TESS_JAR" -Xlint:-options -d build/classes \
  $(find src build/gen -name '*.java')

# 3. Convert to Dalvik bytecode (app + OCR library) and add it, the OCR native libraries (phones'
#    ARM CPUs only) and the OCR language data to the APK.
dalvik-exchange --dex --min-sdk-version=21 --output=build/apk/classes.dex build/classes "$TESS_JAR"
mkdir -p build/apk/lib build/apk/assets/tessdata
cp -r build/tess/jni/arm64-v8a build/tess/jni/armeabi-v7a build/apk/lib/
cp "$TESS_DATA" build/apk/assets/tessdata/eng.traineddata
(cd build/apk && aapt add -f unaligned.apk classes.dex lib/*/*.so assets/tessdata/eng.traineddata >/dev/null)

# 4. Align and sign (v1 + v2 + v3 signatures).
zipalign -f -p 4 build/apk/unaligned.apk build/apk/aligned.apk
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
  --min-sdk-version 21 --v4-signing-enabled false --out "$OUT" build/apk/aligned.apk
apksigner verify --print-certs "$OUT" | head -1
echo "Built $OUT ($(du -h "$OUT" | cut -f1))"
