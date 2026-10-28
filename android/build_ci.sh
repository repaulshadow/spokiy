#!/bin/sh
# Збирання APK на GitHub (стандартний Android SDK раннера: aapt, d8, zipalign, apksigner).
# SERVER_URL — інша адреса сервера (для тесту в емуляторі: http://10.0.2.2:8787). Підпис — тестовий ключ.
# Використання: ./build_ci.sh <вихідний.apk>
set -e
cd "$(dirname "$0")"
OUT=${1:-build/test.apk}
SDKROOT=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
BT=$(ls -d "$SDKROOT"/build-tools/* | sort -V | tail -1)
PLAT=$(ls -d "$SDKROOT"/platforms/android-* | grep -v -e beta -e ext | sort -V | tail -1)/android.jar
echo "build-tools: $BT; platform: $PLAT"
rm -rf build/ci && mkdir -p build/ci/gen build/ci/classes build/ci/dex res/raw
if [ -n "$SERVER_URL" ]; then printf '{"server": "%s", "fixed": true}\n' "$SERVER_URL" > res/raw/config.json; else cp ../config.json res/raw/config.json; fi
"$BT/aapt" package -f -m -J build/ci/gen -M AndroidManifest.xml -S res -I "$PLAT" -F build/ci/unsigned.apk
javac --release 8 -nowarn -encoding UTF-8 -classpath "$PLAT" -d build/ci/classes build/ci/gen/ua/spokiy/trevoga/R.java src/ua/spokiy/trevoga/*.java
"$BT/d8" --min-api 21 --lib "$PLAT" --output build/ci/dex $(find build/ci/classes -name '*.class')
(cd build/ci/dex && "$BT/aapt" add ../unsigned.apk classes.dex >/dev/null)
"$BT/zipalign" -f 4 build/ci/unsigned.apk build/ci/aligned.apk
[ -f build/ci/test.keystore ] || keytool -genkeypair -keystore build/ci/test.keystore -alias t -keyalg RSA -keysize 2048 \
  -validity 1000 -storepass testtest -keypass testtest -dname "CN=Trevoga test" >/dev/null 2>&1
mkdir -p "$(dirname "$OUT")"
"$BT/apksigner" sign --ks build/ci/test.keystore --ks-pass pass:testtest --key-pass pass:testtest --out "$OUT" build/ci/aligned.apk
cp ../config.json res/raw/config.json
echo "OK: $OUT"
