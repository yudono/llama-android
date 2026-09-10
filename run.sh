#!/bin/bash
set -e
cd "$(dirname "$0")"

bash build.sh

APK="android/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.llamacpp.local"

echo ""
echo "Installing (data dipertahankan, tanpa uninstall)..."
adb install -r "$APK"

echo "Launching..."
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1 || true
echo "Done."
