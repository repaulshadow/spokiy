#!/bin/sh
# Збірка Trevoga.exe на Linux (Mono) строго проти API .NET Framework 4.0 — гарантія, що піде на Windows 7.
# Використання: ./build_mono.sh [вихідний.exe] [додаткові .cs ...]
set -e
cd "$(dirname "$0")"
OUT=${1:-Trevoga.exe}; [ $# -gt 0 ] && shift
API=/usr/lib/mono/4.0-api
RES=""
for f in voice/*.wav; do RES="$RES -resource:$f,voice.$(basename $f)"; done
MAIN=""
[ $# -gt 0 ] && MAIN="-target:exe -main:${MAIN_CLASS:-Trevoga.Tests}"
mcs -nologo -langversion:5 -nostdlib -noconfig ${MAIN:--target:winexe} -optimize+ \
  -r:$API/mscorlib.dll -r:$API/System.dll -r:$API/System.Core.dll -r:$API/System.Windows.Forms.dll -r:$API/System.Drawing.dll \
  $RES -resource:config.json,config.json -out:"$OUT" Trevoga.cs "$@"
