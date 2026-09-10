#!/bin/bash
set -e
cd "$(dirname "$0")/android"

export JAVA_HOME=/Users/yudonoputro/Library/Java/JavaVirtualMachines/corretto-17.0.16/Contents/Home
export ANDROID_HOME=/Users/yudonoputro/Library/Android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME

echo "============================================"
echo "  Llama.cpp - Build Debug APK (Kotlin+XML)"
echo "============================================"

./gradlew assembleDebug --no-daemon

APK="app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "APK: $APK"
ls -lh "$APK"
