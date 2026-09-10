# IronAI - Makefile for Android Build
# Uses NativeActivity + Raylib + RayGUI
# Requires: Android SDK, NDK, and Raylib source

RAYLIB_PATH ?= $(HOME)/raylib/src
ANDROID_HOME ?= $(HOME)/Android/Sdk
ANDROID_NDK ?= $(ANDROID_HOME)/ndk/28.2.13676358

APP_NAME = IronAI
PACKAGE_NAME = com.ironai.app
VERSION_CODE = 1
VERSION_NAME = 0.1.0

CC = $(ANDROID_NDK)/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android33-clang++
CXX = $(ANDROID_NDK)/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android33-clang++

CFLAGS = -O2 -Wall -I$(RAYLIB_PATH) -I$(RAYLIB_PATH)/external -Isrc
CXXFLAGS = -std=c++17 $(CFLAGS)
LDFLAGS = -lm -landroid -llog -lEGL -lGLESv2 -lOpenSLES -landroid

BUILD_DIR = build/android
SRC = src/main.cpp

.PHONY: all clean run install

all: android

android: $(BUILD_DIR)/libs/arm64-v8a/lib$(APP_NAME).so
	@echo "=== Packaging APK ==="
	@mkdir -p android/app/src/main/java/com/ironai/app
	@mkdir -p android/app/src/main/jniLibs/arm64-v8a
	@cp $(BUILD_DIR)/libs/arm64-v8a/lib$(APP_NAME).so android/app/src/main/jniLibs/arm64-v8a/
	@echo "=== APK components ready ==="
	@echo "Use ./run.sh to build and install APK"

$(BUILD_DIR)/libs/arm64-v8a/lib$(APP_NAME).so: $(SRC)
	@echo "=== Compiling $(APP_NAME) ==="
	@mkdir -p $(BUILD_DIR)/obj
	$(CXX) $(CXXFLAGS) -shared -o $@ $< $(LDFLAGS)
	@echo "=== Build complete: $@ ==="

clean:
	rm -rf $(BUILD_DIR)
	rm -rf android/app/build
	rm -rf android/.gradle

run: android
	@echo "=== Installing to device ==="
	adb install -r android/app/build/outputs/apk/debug/app-debug.apk
	adb shell monkey -p $(PACKAGE_NAME) -c android.intent.category.LAUNCHER 1
