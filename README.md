# IronAI - Local AI Inference Engine

A dummy UI application for AI inference on smartphones, built with **NativeActivity + Raylib + RayGUI**.

## Features

- 100% C++ native application
- Dark theme UI with modern design
- Mobile-first responsive layout
- Tab-based navigation (Chat, Settings, About)
- Simulated AI inference responses
- APK size: ~2-3 MB

## Prerequisites

### For Android Build
- Android SDK (API 34)
- Android NDK (28.2.13676358 or compatible)
- Raylib source code

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
# Install Raylib if you haven't
git clone https://github.com/raysan5/raylib.git ~/raylib

# Build and install to device
./run.sh
```

### Manual Build

```bash
# Set environment variables
export RAYLIB_PATH=~/raylib/src
export ANDROID_HOME=~/Android/Sdk
export ANDROID_NDK=$ANDROID_HOME/ndk/26.1.10909125

# Build
make android
```

## Project Structure

```
ironai/
├── src/
│   └── main.cpp              # Main application (UI + Logic)
├── android/
│   ├── app/
│   │   ├── build.gradle.kts  # Android build config
│   │   └── proguard-rules.pro
│   ├── build.gradle.kts      # Project-level config
│   ├── settings.gradle.kts   # Gradle settings
│   └── gradle.properties     # Gradle properties
├── build.sh                  # Full build script
├── run.sh                    # Quick build & install
├── run-desktop.sh            # Desktop test build
├── Makefile                  # Make build system
└── README.md                 # This file
```

## UI Components

### Chat Tab
- Model selector dropdown
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
- **Raylib**: Minimal game library for window/input/rendering
- **RayGUI**: Immediate mode GUI library for Raylib

## Notes

This is a **dummy application** - the AI inference is simulated. In a production version:
- Integrate llama.cpp or similar inference engine
- Add actual model loading and inference
- Implement streaming responses
- Add model download functionality

## License

MIT
