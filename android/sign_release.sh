#!/bin/sh
# Підпис релізного APK справжнім ключем (trevoga.keystore — лише на комп'ютері автора, у репозиторій не потрапляє).
# APK без підпису збирає GitHub (workflow «Android release build», targetSdk 35): гілка apk-unsigned.
# sh sign_release.sh <Trevoga-unsigned.apk>  →  android/Trevoga.apk
set -e
cd "$(dirname "$0")"
IN=${1:?вкажіть APK без підпису}
[ -f trevoga.keystore ] || { echo "немає trevoga.keystore"; exit 1; }
apksigner sign --ks trevoga.keystore --ks-pass pass:trevoga123 --key-pass pass:trevoga123 --out Trevoga.apk "$IN"
apksigner verify --print-certs Trevoga.apk | grep -i "sha-256" | head -1
echo "OK: Trevoga.apk"
