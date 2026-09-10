# llama.cpp - Local AI Inference Engine

A dummy UI application for local AI inference on smartphones, built with **Dear ImGui + Android NativeActivity + OpenGL ES 3**.

## Features

- 100% C++ native application
- Dark theme UI with modern design
- Mobile-first responsive layout
- Tab-based navigation (Chat, Settings, About)
- Simulated inference responses
- Uses llama.cpp engine architecture
- APK size: ~2.8 MB

## Prerequisites

### For Android Build
- Android SDK (API 34)
- Android NDK (28.2.13676358 or compatible)
- Dear ImGui source code

### For Desktop Testing
- Raylib installed (via Homebrew on macOS or apt on Linux)

## Quick Start

### Test on Desktop (Recommended First)

```bash
# Install raylib and run on desktop
./run-desktop.sh
```

### Build for Android

```bash
# Install ImGui if you haven't
git clone https://github.com/ocornut/imgui.git /tmp/imgui-android

# Build and install to device
./run.sh
```

### Manual Build

```bash
# Set environment variables
export ANDROID_HOME=~/Library/Android/sdk
export ANDROID_NDK=$ANDROID_HOME/ndk/28.2.13676358

# Build
./build-imgui.sh
```

## Project Structure

```
llama.cpp/
├── src/
│   └── main.cpp              # Main application (UI + Logic)
├── android/
│   ├── app/
│   │   ├── build.gradle.kts  # Android build config
│   │   └── proguard-rules.pro
│   ├── build.gradle.kts      # Project-level config
│   ├── settings.gradle.kts   # Gradle settings
│   └── gradle.properties     # Gradle properties
├── build-imgui.sh            # Main build script
├── build.sh                  # Build script
├── run.sh                    # Quick build & install
├── run-desktop.sh            # Desktop test build
├── Makefile                  # Make build system
└── README.md                 # This file
```

## UI Components

### Chat Tab
- Model selector dropdown (TinyLlama, Phi-2, Llama-2, CodeLlama)
- Prompt input area
- Generate button with loading state
- Quick actions (Clear, Copy, Share)
- Response output area

### Settings Tab
- Temperature slider (0.0 - 2.0)
- Max Tokens selector (64 - 2048)
- Device info display

### About Tab
- App logo and name
- Version information
- Project description
- GitHub link

## Architecture

- **NativeActivity**: Android's native C++ activity (no Java/Kotlin)
- **Dear ImGui**: Immediate mode GUI library
- **OpenGL ES 3**: Graphics rendering
- **llama.cpp**: Local inference engine (simulated in demo)

## Supported Models (Production)

- TinyLlama-1.1B
- Phi-2-2.7B
- Llama-2-7B/13B
- CodeLlama-7B

## Notes

This is a **dummy application** - the inference is simulated. In a production version:
- Integrate llama.cpp library
- Add GGUF model loading
- Implement token generation
- Add model download functionality

## License

MIT
