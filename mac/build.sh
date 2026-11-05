#!/bin/sh
# Збирання Тривога.app для macOS (Apple Silicon + Intel, macOS 12+). Потрібні Xcode Command Line Tools і python3.
# sh mac/build.sh [--no-tests]
set -e
cd "$(dirname "$0")"
VER=$(cat VERSION); BUILD=$(cat BUILD)
SDK=$(xcrun --show-sdk-path)
rm -rf build && mkdir -p build
python3 gen_locations.py ../android/src/ua/spokiy/trevoga/Locations.java Sources/Core/Locations.swift

if [ "$1" != "--no-tests" ]; then
  echo "== Автотести логіки"
  swiftc -O -sdk "$SDK" Sources/Core/*.swift Tests/main.swift -o build/coretests
  build/coretests
fi

echo "== Компіляція (arm64 + x86_64)"
for A in arm64 x86_64; do
  swiftc -O -sdk "$SDK" -target $A-apple-macos12.0 -module-name Trevoga Sources/Core/*.swift Sources/App/*.swift -o build/Trevoga-$A
done
APP=build/Trevoga.app
mkdir -p $APP/Contents/MacOS $APP/Contents/Resources/voice
lipo -create build/Trevoga-arm64 build/Trevoga-x86_64 -output $APP/Contents/MacOS/Trevoga

echo "== Ресурси"
cp ../android/res/raw/tetiana_*.wav ../android/res/raw/mykyta_*.wav $APP/Contents/Resources/voice/
python3 ../tools/make_sounds.py $APP/Contents/Resources
cp ../android/res/raw/geo.json ../android/res/raw/cities.json ../config.json $APP/Contents/Resources/
cp MenuIcon.png MenuIcon@2x.png MenuIconOff.png MenuIconOff@2x.png $APP/Contents/Resources/
IS=build/AppIcon.iconset; mkdir -p $IS
for s in 16 32 128 256 512; do
  sips -z $s $s AppIcon.png --out $IS/icon_${s}x${s}.png >/dev/null
  sips -z $((s*2)) $((s*2)) AppIcon.png --out $IS/icon_${s}x${s}@2x.png >/dev/null
done
iconutil -c icns $IS -o $APP/Contents/Resources/AppIcon.icns
sed -e "s/@VER@/$VER/g" -e "s/@BUILD@/$BUILD/g" Info.plist > $APP/Contents/Info.plist

echo "== Підпис (ad-hoc) і архів"
codesign --force --deep --sign - $APP
codesign --verify --verbose $APP
(cd build && ditto -c -k --sequesterRsrc --keepParent Trevoga.app Trevoga-mac.zip)
lipo -info $APP/Contents/MacOS/Trevoga
ls -la build/Trevoga-mac.zip
