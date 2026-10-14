#!/bin/zsh
# Build + sign EasyDensCtrl.app. Adhoc-signed: every rebuild resets the macOS
# Bluetooth (TCC) grant — re-allow in System Settings → Privacy → Bluetooth.
set -e
cd "$(dirname "$0")/.."
swiftc -O -parse-as-library -o app/EasyDensCtrl app/EasyDensCtrl.swift
mkdir -p EasyDensCtrl.app/Contents/MacOS
cp app/EasyDensCtrl EasyDensCtrl.app/Contents/MacOS/
cp app/Info.plist EasyDensCtrl.app/Contents/
codesign -f -s - EasyDensCtrl.app
echo "built EasyDensCtrl.app (Bluetooth permission will re-prompt)"
