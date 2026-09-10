#!/bin/bash
set -e

echo "============================================"
echo "  llama.cpp - Quick Build & Install"
echo "============================================"

# Uninstall old version first
adb uninstall com.ironai.app 2>/dev/null || true

# Run the full build
bash "$(dirname "$0")/build-imgui.sh"
