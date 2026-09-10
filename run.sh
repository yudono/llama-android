#!/bin/bash
set -e

echo "============================================"
echo "  IronAI - Quick Run"
echo "============================================"

# Run the full build process
bash "$(dirname "$0")/build.sh"
