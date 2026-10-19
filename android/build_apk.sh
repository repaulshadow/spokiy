#!/bin/sh
# Сборка APK без Android Studio (Debian/Ubuntu: apt install android-sdk-platform-23 aapt dalvik-exchange apksigner zipalign default-jdk)
set -e
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf build && mkdir -p build/gen build/classes
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$SDK" -F build/unsigned.apk
javac --release 8 -nowarn -encoding UTF-8 -classpath "$SDK" -d build/classes build/gen/ua/spokiy/trevoga/R.java src/ua/spokiy/trevoga/*.java
dalvik-exchange --dex --min-sdk-version=21 --output=build/classes.dex build/classes
(cd build && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f trevoga.keystore ] || keytool -genkeypair -keystore trevoga.keystore -alias trevoga -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass trevoga123 -keypass trevoga123 -dname "CN=Trevoga"
apksigner sign --ks trevoga.keystore --ks-pass pass:trevoga123 --key-pass pass:trevoga123 --out Trevoga.apk build/aligned.apk
apksigner verify Trevoga.apk && echo "OK: Trevoga.apk"
