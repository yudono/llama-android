#!/bin/bash
set -e

echo "============================================"
echo "  llama.cpp - Local Inference Build"
echo "============================================"

PROJECT_DIR="/Users/yudonoputro/Documents/projects/simple-app"
NDK="/Users/yudonoputro/Library/Android/sdk/ndk/28.2.13676358"
ANDROID_HOME="/Users/yudonoputro/Library/Android/sdk"
BUILD_TOOLS=$(ls -d "$ANDROID_HOME/build-tools/"* | sort -V | tail -1)
BUILD_TOOLS_VERSION=$(basename "$BUILD_TOOLS")
IMGUI_SRC="/tmp/imgui-android"

APP_NAME="llamacpp"
PACKAGE="com.llamacpp.local"
CC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang++"

BUILD_DIR="$PROJECT_DIR/build/android"
APP_BUILD="$BUILD_DIR/app"
OBJ_DIR="$BUILD_DIR/obj"

echo "  Using build-tools: $BUILD_TOOLS_VERSION"
echo "  ImGui source: $IMGUI_SRC"

# --- Step 1: Compile ImGui ---
echo "[1/7] Compiling ImGui core files..."

mkdir -p "$OBJ_DIR"

IMGUI_CORE_FILES=(
    "$IMGUI_SRC/imgui.cpp"
    "$IMGUI_SRC/imgui_demo.cpp"
    "$IMGUI_SRC/imgui_draw.cpp"
    "$IMGUI_SRC/imgui_tables.cpp"
    "$IMGUI_SRC/imgui_widgets.cpp"
)

IMGUI_BACKEND_FILES=(
    "$IMGUI_SRC/backends/imgui_impl_android.cpp"
    "$IMGUI_SRC/backends/imgui_impl_opengl3.cpp"
)

APP_FILES=(
    "$PROJECT_DIR/src/main.cpp"
)

APP_C_FILES=(
    "$NDK/sources/android/native_app_glue/android_native_app_glue.c"
)

ALL_FILES=("${IMGUI_CORE_FILES[@]}" "${IMGUI_BACKEND_FILES[@]}" "${APP_FILES[@]}")

for src in "${ALL_FILES[@]}"; do
    basename=$(basename "$src" .cpp)
    obj="$OBJ_DIR/${basename}.o"
    echo "  Compiling: $basename"
    $CC -c "$src" \
        -std=c++17 \
        -O2 \
        -DANDROID \
        -DPLATFORM_ANDROID \
        -DIMGUI_IMPL_OPENGL_ES3 \
        -I"$IMGUI_SRC" \
        -I"$IMGUI_SRC/backends" \
        -I"$PROJECT_DIR/src" \
        -I"$NDK/sources/android/native_app_glue" \
        -fPIC \
        -o "$obj"
done

# Compile C files
echo "  Compiling: android_native_app_glue"
CC_C="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang"
for src in "${APP_C_FILES[@]}"; do
    basename=$(basename "$src" .c)
    obj="$OBJ_DIR/${basename}.o"
    $CC_C -c "$src" \
        -std=c11 \
        -O2 \
        -DANDROID \
        -DPLATFORM_ANDROID \
        -I"$NDK/sources/android/native_app_glue" \
        -fPIC \
        -o "$obj"
done

# --- Step 2: Create shared library ---
echo "[2/7] Linking library..."

$CC -shared \
    -o "$BUILD_DIR/arm64-v8a/lib${APP_NAME}.so" \
    "$OBJ_DIR"/*.o \
    -lm -landroid -llog -lEGL -lGLESv3 -lc++_shared

echo "  Library: $BUILD_DIR/arm64-v8a/lib${APP_NAME}.so"
ls -lh "$BUILD_DIR/arm64-v8a/lib${APP_NAME}.so"

# --- Step 3: Create Android project structure ---
echo "[3/7] Creating Android project..."

mkdir -p "$APP_BUILD/obj"
mkdir -p "$APP_BUILD/src/main/res/values"
mkdir -p "$APP_BUILD/src/main/java/$PACKAGE"
mkdir -p "$APP_BUILD/apk"

cp "$BUILD_DIR/arm64-v8a/lib${APP_NAME}.so" "$APP_BUILD/"

# Generate launcher icons from logo.png (mipmap densities)
if [ -f "$PROJECT_DIR/logo.png" ]; then
    echo "  Generating launcher icons from logo.png..."
    for spec in "mipmap-mdpi:48" "mipmap-hdpi:72" "mipmap-xhdpi:96" "mipmap-xxhdpi:144" "mipmap-xxxhdpi:192"; do
        d="${spec%%:*}"; px="${spec##*:}"
        mkdir -p "$APP_BUILD/src/main/res/$d"
        sips -z "$px" "$px" "$PROJECT_DIR/logo.png" --out "$APP_BUILD/src/main/res/$d/ic_launcher.png" > /dev/null
    done
else
    echo "  WARNING: logo.png not found, skipping launcher icon"
fi

# Create AndroidManifest.xml
cat > "$APP_BUILD/src/main/AndroidManifest.xml" << EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$PACKAGE">

    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34" />
    <uses-feature android:glEsVersion="0x00030000" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />

    <application
        android:allowBackup="true"
        android:hasCode="false"
        android:icon="@mipmap/ic_launcher"
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
                android:value="$APP_NAME" />

            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

# Create strings.xml
cat > "$APP_BUILD/src/main/res/values/strings.xml" << EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Llama.cpp</string>
</resources>
EOF

# --- Step 4: Compile resources ---
echo "[4/7] Compiling resources..."

"$BUILD_TOOLS/aapt2" compile \
    -o "$APP_BUILD/obj/" \
    --dir "$APP_BUILD/src/main/res"

# --- Step 5: Link resources ---
echo "[5/7] Linking resources..."

"$BUILD_TOOLS/aapt2" link \
    -o "$APP_BUILD/apk/${APP_NAME}.unsigned.apk" \
    -I "$ANDROID_HOME/platforms/android-34/android.jar" \
    --manifest "$APP_BUILD/src/main/AndroidManifest.xml" \
    -R "$APP_BUILD/obj/"*.flat \
    --auto-add-overlay

# --- Step 6: Add native library ---
echo "[6/7] Adding native library..."

cd "$APP_BUILD/apk"
mkdir -p "lib/arm64-v8a"
cp "$APP_BUILD/lib${APP_NAME}.so" "lib/arm64-v8a/"
# Copy libc++_shared.so from NDK
cp "$NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "lib/arm64-v8a/"
zip -r "${APP_NAME}.unsigned.apk" "lib/"
cd "$PROJECT_DIR"

# Zipalign
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

# --- Step 7: Install ---
echo "[7/7] Installing to device..."

APK_SIZE=$(ls -lh "$APP_BUILD/apk/${APP_NAME}.debug.apk" | awk '{print $5}')
echo ""
echo "============================================"
echo "  APK Built Successfully!"
echo "============================================"
echo ""
echo "  APK: $APP_BUILD/apk/${APP_NAME}.debug.apk"
echo "  Size: $APK_SIZE"
echo ""

adb install -r "$APP_BUILD/apk/${APP_NAME}.debug.apk"

echo ""
echo "  Launching app..."
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1

echo ""
echo "============================================"
echo "  Llama.cpp installed and running!"
echo "============================================"
