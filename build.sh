#!/bin/bash
set -e

echo "============================================"
echo "  IronAI - Android Build Script"
echo "============================================"

# --- Configuration ---
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RAYLIB_PATH="${RAYLIB_PATH:-$HOME/raylib/src}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_NDK="${ANDROID_NDK:-$ANDROID_HOME/ndk/28.2.13676358}"

APP_NAME="IronAI"
PACKAGE_NAME="com.ironai.app"

# --- Check Dependencies ---
echo "[1/5] Checking dependencies..."

if [ ! -f "$RAYLIB_PATH/raylib.h" ]; then
    echo "ERROR: Raylib not found at $RAYLIB_PATH"
    echo "Please install Raylib first:"
    echo "  git clone https://github.com/raysan5/raylib.git ~/raylib"
    exit 1
fi

if [ ! -d "$ANDROID_NDK" ]; then
    echo "ERROR: Android NDK not found at $ANDROID_NDK"
    echo "Install via: sdkmanager 'ndk;26.1.10909125'"
    exit 1
fi

echo "  Raylib:    $RAYLIB_PATH"
echo "  Android:   $ANDROID_HOME"
echo "  NDK:       $ANDROID_NDK"

# --- Compile ---
echo "[2/5] Compiling C++ source..."

CC="$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android33-clang++"
BUILD_DIR="build/android"
mkdir -p "$BUILD_DIR/obj"

$CC \
    -O2 -std=c++17 \
    -I"$RAYLIB_PATH" \
    -I"$RAYLIB_PATH/external" \
    -I"src" \
    -shared \
    -o "$BUILD_DIR/libs/arm64-v8a/lib$APP_NAME.so" \
    src/main.cpp \
    -lm -landroid -llog -lEGL -lGLESv2 -lOpenSLES -landroid

echo "  Compiled: $BUILD_DIR/libs/arm64-v8a/lib$APP_NAME.so"

# --- Setup Android Project ---
echo "[3/5] Setting up Android project..."

# Create Android project structure
mkdir -p "android/app/src/main/java/$PACKAGE_NAME"
mkdir -p "android/app/src/main/res/values"
mkdir -p "android/app/src/main/res/mipmap-hdpi"
mkdir -p "android/app/src/main/jniLibs/arm64-v8a"

# Copy the compiled library
cp "$BUILD_DIR/libs/arm64-v8a/lib$APP_NAME.so" \
   "android/app/src/main/jniLibs/arm64-v8a/"

# Generate AndroidManifest.xml
cat > "android/app/src/main/AndroidManifest.xml" << 'MANIFEST'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.ironai.app">

    <uses-feature android:glEsVersion="0x00020000" />
    <uses-feature android:name="android.hardware.screen.landscape"
        android:required="false" />
    <uses-feature android:name="android.hardware.touchscreen"
        android:required="false" />

    <application
        android:allowBackup="true"
        android:hasCode="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.NoTitleBar.Fullscreen"
        android:hasFragileUserData="false">

        <activity
            android:name="android.app.NativeActivity"
            android:configChanges="orientation|keyboardHidden|screenSize|screenLayout|smallestScreenSize"
            android:exported="true"
            android:launchMode="singleInstance"
            android:screenOrientation="unspecified">

            <meta-data
                android:name="android.app.lib_name"
                android:value="IronAI" />
            <meta-data
                android:name="android.app.sqlite_preload_provider"
                android:value="false" />

            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
MANIFEST

# Generate strings.xml
cat > "android/app/src/main/res/values/strings.xml" << 'STRINGS'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">IronAI</string>
</resources>
STRINGS

echo "  Android project structure created"

# --- Build APK ---
echo "[4/5] Building APK..."

# Check for gradle wrapper or use command line tools
if [ -f "android/gradlew" ]; then
    cd android && ./gradlew assembleDebug && cd ..
    APK_PATH="android/app/build/outputs/apk/debug/app-debug.apk"
else
    echo "  Using aapt2 + d8 + manual packaging..."
    
    BUILD_TOOLS="$ANDROID_HOME/build-tools/34.0.0"
    if [ ! -d "$BUILD_TOOLS" ]; then
        BUILD_TOOLS=$(ls -d "$ANDROID_HOME/build-tools/"* | sort -V | tail -1)
    fi
    
    APK_DIR="build/android/apk"
    mkdir -p "$APK_DIR/gen" "$APK_DIR/obj" "$APK_DIR/apk"
    
    # Compile resources
    "$BUILD_TOOLS/aapt2" compile \
        -o "$APK_DIR/obj/" \
        --dir "android/app/src/main/res"
    
    # Link resources
    "$BUILD_TOOLS/aapt2" link \
        -o "$APK_DIR/apk/app.unsigned.apk" \
        -I "$ANDROID_HOME/platforms/android-34/android.jar" \
        --manifest "android/app/src/main/AndroidManifest.xml" \
        -R "$APK_DIR/obj/"*.flat \
        --auto-add-overlay
    
    # Add native library
    cd "$APK_DIR/apk"
    "$BUILD_TOOLS/aapt" add app.unsigned.apk \
        "../../jniLibs/arm64-v8a/lib$APP_NAME.so"
    cd "$SCRIPT_DIR"
    
    # Zipalign
    "$BUILD_TOOLS/zipalign" -f 4 \
        "$APK_DIR/apk/app.unsigned.apk" \
        "$APK_DIR/apk/app.aligned.apk"
    
    # Sign APK
    KEYSTORE="build/android/debug.keystore"
    if [ ! -f "$KEYSTORE" ]; then
        keytool -genkeypair \
            -keystore "$KEYSTORE" \
            -storepass android \
            -alias androiddebugkey \
            -keypass android \
            -keyalg RSA \
            -keysize 2048 \
            -validity 10000 \
            -dname "CN=Debug,O=Debug,C=US" 2>/dev/null
    fi
    
    "$BUILD_TOOLS/apksigner" sign \
        --ks "$KEYSTORE" \
        --ks-pass pass:android \
        --key-pass pass:android \
        --out "$APK_DIR/apk/app-debug.apk" \
        "$APK_DIR/apk/app.aligned.apk"
    
    APK_PATH="$APK_DIR/apk/app-debug.apk"
fi

echo "  APK built: $APK_PATH"

# --- Install ---
echo "[5/5] Installing to device..."

if adb devices | grep -q "device$"; then
    adb install -r "$APK_PATH"
    adb shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1
    echo ""
    echo "============================================"
    echo "  IronAI installed and launched!"
    echo "============================================"
else
    echo "  WARNING: No device connected"
    echo "  APK available at: $APK_PATH"
    echo "  Connect device and run: adb install -r $APK_PATH"
fi
