#!/bin/bash
set -e

echo "============================================"
echo "  llama.cpp - Full Build Script"
echo "============================================"

# Uninstall old version
adb uninstall com.ironai.app 2>/dev/null || true
adb uninstall com.llamacpp.local 2>/dev/null || true

# Run the ImGui build
bash "$(dirname "$0")/build-imgui.sh"
