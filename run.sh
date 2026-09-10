#!/bin/bash
set -e
cd "$(dirname "$0")"

bash build.sh

APK="android/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.llamacpp.local"

echo ""
echo "Uninstalling old version..."
adb uninstall "$PACKAGE" 2>/dev/null || true

echo "Installing..."
adb install "$APK"

echo "Launching..."
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1 || true
echo "Done."
