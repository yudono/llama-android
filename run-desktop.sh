#!/bin/bash
set -e

echo "============================================"
echo "  IronAI - Desktop Test Build"
echo "============================================"

# Detect OS
OS="$(uname -s)"

# Check for raylib via Homebrew (macOS) or system
if [ "$OS" = "Darwin" ]; then
    echo "[1/3] Checking for raylib (macOS)..."

    if ! command -v brew &> /dev/null; then
        echo "  Homebrew not found. Installing raylib via brew..."
        /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
    fi

    if ! brew list raylib &> /dev/null 2>&1; then
        echo "  Installing raylib..."
        brew install raylib
    fi

    RAYLIB_CFLAGS=$(pkg-config --cflags raylib 2>/dev/null || echo "-I/opt/homebrew/include")
    RAYLIB_LIBS=$(pkg-config --libs raylib 2>/dev/null || echo "-L/opt/homebrew/lib -lraylib")

    echo "[2/3] Compiling for macOS..."
    mkdir -p build/desktop

    clang++ -std=c++17 -O2 \
        $RAYLIB_CFLAGS \
        -Isrc \
        -o build/desktop/IronAI \
        src/main.cpp \
        $RAYLIB_LIBS \
        -framework IOKit -framework Cocoa -framework OpenGL

    echo "[3/3] Running IronAI..."
    echo ""
    ./build/desktop/IronAI

elif [ "$OS" = "Linux" ]; then
    echo "[1/3] Checking for raylib (Linux)..."

    if ! pkg-config --exists raylib 2>/dev/null; then
        echo "  Installing raylib..."
        sudo apt-get update && sudo apt-get install -y libraylib-dev
    fi

    RAYLIB_CFLAGS=$(pkg-config --cflags raylib)
    RAYLIB_LIBS=$(pkg-config --libs raylib)

    echo "[2/3] Compiling for Linux..."
    mkdir -p build/desktop

    g++ -std=c++17 -O2 \
        $RAYLIB_CFLAGS \
        -Isrc \
        -o build/desktop/IronAI \
        src/main.cpp \
        $RAYLIB_LIBS \
        -lm -lpthread -ldl

    echo "[3/3] Running IronAI..."
    echo ""
    ./build/desktop/IronAI
else
    echo "Unsupported OS: $OS"
    echo "Please compile manually with raylib installed."
    exit 1
fi
