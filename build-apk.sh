#!/bin/bash
set -e

echo "============================================"
echo "  IronAI - Android APK Build"
echo "============================================"

PROJECT_DIR="/Users/yudonoputro/Documents/projects/simple-app"
NDK="/Users/yudonoputro/Library/Android/sdk/ndk/28.2.13676358"
ANDROID_HOME="/Users/yudonoputro/Library/Android/sdk"
BUILD_TOOLS=$(ls -d "$ANDROID_HOME/build-tools/"* | sort -V | tail -1)
BUILD_TOOLS_VERSION=$(basename "$BUILD_TOOLS")
echo "  Using build-tools: $BUILD_TOOLS_VERSION"
APP_NAME="IronAI"
PACKAGE="com.ironai.app"

BUILD_DIR="$PROJECT_DIR/build/android"
APP_BUILD="$BUILD_DIR/app"

echo "[1/6] Cleaning previous build..."
rm -rf "$APP_BUILD"
mkdir -p "$APP_BUILD"/{gen,obj,apk,intermediates}

echo "[2/6] Creating Android project structure..."
mkdir -p "$APP_BUILD/src/main/res/values"
mkdir -p "$APP_BUILD/src/main/res/mipmap-hdpi"
mkdir -p "$APP_BUILD/src/main/java/$PACKAGE"
mkdir -p "$APP_BUILD/src/main/jniLibs/arm64-v8a"

# Copy the compiled library
cp "$BUILD_DIR/arm64-v8a/lib${APP_NAME}.so" "$APP_BUILD/src/main/jniLibs/arm64-v8a/"

# Create AndroidManifest.xml
cat > "$APP_BUILD/src/main/AndroidManifest.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.ironai.app">

    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34" />
    <uses-feature android:glEsVersion="0x00020000" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />

    <application
        android:allowBackup="true"
        android:hasCode="false"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.NoTitleBar.Fullscreen">

        <activity
            android:name="android.app.NativeActivity"
            android:configChanges="orientation|keyboardHidden|screenSize|screenLayout|smallestScreenSize"
            android:exported="true"
            android:launchMode="singleInstance"
            android:screenOrientation="unspecified">

            <meta-data
                android:name="android.app.lib_name"
                android:value="IronAI" />

            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

# Create strings.xml
cat > "$APP_BUILD/src/main/res/values/strings.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">IronAI</string>
</resources>
EOF

# Create a simple icon (using aapt2 fallback - will use default Android icon)
echo "  Using default Android icon..."

echo "[3/6] Compiling resources with aapt2..."
"$BUILD_TOOLS/aapt2" compile \
    -o "$APP_BUILD/obj/" \
    --dir "$APP_BUILD/src/main/res"

echo "[4/6] Linking resources into APK..."
"$BUILD_TOOLS/aapt2" link \
    -o "$APP_BUILD/apk/${APP_NAME}.unsigned.apk" \
    -I "$ANDROID_HOME/platforms/android-34/android.jar" \
    --manifest "$APP_BUILD/src/main/AndroidManifest.xml" \
    -R "$APP_BUILD/obj/"*.flat \
    --auto-add-overlay

echo "[5/6] Adding native libraries..."
cd "$APP_BUILD/apk"
# Create the correct directory structure for native libraries
mkdir -p "lib/arm64-v8a"
cp "$BUILD_DIR/arm64-v8a/lib${APP_NAME}.so" "lib/arm64-v8a/"
cp "$BUILD_DIR/arm64-v8a/libc++_shared.so" "lib/arm64-v8a/" 2>/dev/null || true
# Add to APK with correct path
zip -r "${APP_NAME}.unsigned.apk" "lib/"
cd "$PROJECT_DIR"

# Zipalign
echo "[6/6] Zipaligning and signing..."
"$BUILD_TOOLS/zipalign" -f 4 \
    "$APP_BUILD/apk/${APP_NAME}.unsigned.apk" \
    "$APP_BUILD/apk/${APP_NAME}.aligned.apk"

# Generate debug keystore if not exists
KEYSTORE="$BUILD_DIR/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair \
        -keystore "$KEYSTORE" \
        -storepass android \
        -alias androiddebugkey \
        -keypass android \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi

# Sign APK
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$APP_BUILD/apk/${APP_NAME}.debug.apk" \
    "$APP_BUILD/apk/${APP_NAME}.aligned.apk"

echo ""
echo "============================================"
echo "  APK Built Successfully!"
echo "============================================"
echo ""
echo "  APK Location: $APP_BUILD/apk/${APP_NAME}.debug.apk"
echo "  APK Size: $(ls -lh "$APP_BUILD/apk/${APP_NAME}.debug.apk" | awk '{print $5}')"
echo ""
echo "  Installing to device..."

adb install -r "$APP_BUILD/apk/${APP_NAME}.debug.apk"

echo ""
echo "  Launching app..."
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1

echo ""
echo "============================================"
echo "  IronAI installed and running!"
echo "============================================"
