# llama.cpp - Makefile for Android Build
# Uses NativeActivity + Dear ImGui + OpenGL ES 3

IMGUI_SRC ?= /tmp/imgui-android
ANDROID_HOME ?= $(HOME)/Library/Android/sdk
ANDROID_NDK ?= $(ANDROID_HOME)/ndk/28.2.13676358

APP_NAME = llamacpp
PACKAGE_NAME = com.llamacpp.local

CC = $(ANDROID_NDK)/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang++
CC_C = $(ANDROID_NDK)/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang

CFLAGS = -O2 -std=c++17 -DANDROID -DPLATFORM_ANDROID -DIMGUI_IMPL_OPENGL_ES3 -fPIC
CFLAGS += -I$(IMGUI_SRC) -I$(IMGUI_SRC)/backends -Isrc
CFLAGS += -I$(ANDROID_NDK)/sources/android/native_app_glue

CFLAGS_C = -O2 -std=c11 -DANDROID -DPLATFORM_ANDROID -fPIC
CFLAGS_C += -I$(ANDROID_NDK)/sources/android/native_app_glue

LDFLAGS = -shared -lm -landroid -llog -lEGL -lGLESv3 -lc++_shared

BUILD_DIR = build/android
OBJ_DIR = $(BUILD_DIR)/obj

IMGUI_SRC_FILES = \
    $(IMGUI_SRC)/imgui.cpp \
    $(IMGUI_SRC)/imgui_demo.cpp \
    $(IMGUI_SRC)/imgui_draw.cpp \
    $(IMGUI_SRC)/imgui_tables.cpp \
    $(IMGUI_SRC)/imgui_widgets.cpp \
    $(IMGUI_SRC)/backends/imgui_impl_android.cpp \
    $(IMGUI_SRC)/backends/imgui_impl_opengl3.cpp

APP_SRC = src/main.cpp
GLUE_SRC = $(ANDROID_NDK)/sources/android/native_app_glue/android_native_app_glue.c

IMGUI_OBJS = $(OBJ_DIR)/imgui.o $(OBJ_DIR)/imgui_demo.o $(OBJ_DIR)/imgui_draw.o \
    $(OBJ_DIR)/imgui_tables.o $(OBJ_DIR)/imgui_widgets.o \
    $(OBJ_DIR)/imgui_impl_android.o $(OBJ_DIR)/imgui_impl_opengl3.o
APP_OBJS = $(OBJ_DIR)/main.o $(OBJ_DIR)/android_native_app_glue.o

.PHONY: all clean run install

all: $(BUILD_DIR)/libs/arm64-v8a/lib$(APP_NAME).so
	@echo "=== Build complete ==="

$(OBJ_DIR)/imgui.o: $(IMGUI_SRC)/imgui.cpp
	@mkdir -p $(OBJ_DIR)
	$(CC) -c $< $(CFLAGS) -o $@

$(OBJ_DIR)/imgui_demo.o: $(IMGUI_SRC)/imgui_demo.cpp
	@mkdir -p $(OBJ_DIR)
	$(CC) -c $< $(CFLAGS) -o $@

$(OBJ_DIR)/imgui_draw.o: $(IMGUI_SRC)/imgui_draw.cpp
	@mkdir -p $(OBJ_DIR)
	$(CC) -c $< $(CFLAGS) -o $@

$(OBJ_DIR)/imgui_tables.o: $(IMGUI_SRC)/imgui_tables.cpp
	@mkdir -p $(OBJ_DIR)
	$(CC) -c $< $(CFLAGS) -o $@

$(OBJ_DIR)/imgui_widgets.o: $(IMGUI_SRC)/imgui_widgets.cpp
	@mkdir -p $(OBJ_DIR)
	$(CC) -c $< $(CFLAGS) -o $@

$(OBJ_DIR)/imgui_impl_android.o: $(IMGUI_SRC)/backends/imgui_impl_android.cpp
	@mkdir -p $(OBJ_DIR)
	$(CC) -c $< $(CFLAGS) -o $@

$(OBJ_DIR)/imgui_impl_opengl3.o: $(IMGUI_SRC)/backends/imgui_impl_opengl3.cpp
	@mkdir -p $(OBJ_DIR)
	$(CC) -c $< $(CFLAGS) -o $@

$(OBJ_DIR)/main.o: $(APP_SRC)
	@mkdir -p $(OBJ_DIR)
	$(CC) -c $< $(CFLAGS) -o $@

$(OBJ_DIR)/android_native_app_glue.o: $(GLUE_SRC)
	@mkdir -p $(OBJ_DIR)
	$(CC_C) -c $< $(CFLAGS_C) -o $@

$(BUILD_DIR)/libs/arm64-v8a/lib$(APP_NAME).so: $(IMGUI_OBJS) $(APP_OBJS)
	@mkdir -p $(BUILD_DIR)/libs/arm64-v8a
	$(CC) -shared -o $@ $(IMGUI_OBJS) $(APP_OBJS) $(LDFLAGS)
	@echo "  Library: $@"

clean:
	rm -rf $(BUILD_DIR)

run: all
	bash run.sh

install: all
	adb install -r $(BUILD_DIR)/libs/arm64-v8a/lib$(APP_NAME).so
	adb shell monkey -p $(PACKAGE_NAME) -c android.intent.category.LAUNCHER 1
