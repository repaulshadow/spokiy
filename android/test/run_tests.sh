#!/bin/sh
# Автотести Android-версії на звичайній Java: логіка + WebSocket-клієнт.
# Потрібні: android.jar (як для збирання) і org-json.jar (справжня бібліотека org.json; в android.jar — заглушки).
set -e
cd "$(dirname "$0")/.."
if [ -z "$SDK" ]; then
  if [ -f /usr/lib/android-sdk/platforms/android-23/android.jar ]; then SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
  else SDK=$(ls -d "${ANDROID_HOME:-$ANDROID_SDK_ROOT}"/platforms/android-* | sort -V | tail -1)/android.jar; fi
fi
AAPT=${AAPT:-$(command -v aapt || ls -d "${ANDROID_HOME:-$ANDROID_SDK_ROOT}"/build-tools/* | sort -V | tail -1)/aapt}
[ -x "$AAPT" ] || AAPT=$(command -v aapt)
JSON=${JSON_JAR:?вкажіть JSON_JAR=шлях/до/org-json.jar}
rm -rf build/test && mkdir -p build/test/gen build/test/classes
mkdir -p res/raw && cp ../config.json res/raw/config.json
"$AAPT" package -f -m -J build/test/gen -M AndroidManifest.xml -S res -I "$SDK"
javac --release 8 -nowarn -encoding UTF-8 -classpath "$JSON:$SDK" -d build/test/classes build/test/gen/ua/spokiy/trevoga/R.java src/ua/spokiy/trevoga/*.java test/*.java
CP="$JSON:build/test/classes:$SDK"
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP" ua.spokiy.trevoga.AndroidLogicTests
if [ "$1" = "stream" ]; then
  python3 ../test/fake_ws.py 8790 build/test/ws_res.json &
  P=$!
  sleep 2
  java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP" ua.spokiy.trevoga.AndroidStreamTest ws://127.0.0.1:8790/api/v1/stream 120 || { kill $P; exit 1; }
  kill $P
  python3 -c "import json,sys; r=json.load(open('build/test/ws_res.json')); ok=r['pong_ok'] and r['close_reply_ok'] and r['client_masked'] and r['handshake_ok'] and not r['errors'] and r['connections']>=5; print('сервер-імітація: OK' if ok else 'сервер-імітація: FAIL ' + json.dumps(r, ensure_ascii=False)); sys.exit(0 if ok else 1)"
fi
