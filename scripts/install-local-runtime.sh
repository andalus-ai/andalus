#!/bin/bash
# andalus - Local Development Installer
# Builds the CLI from source and installs a local shim for testing.
# Usage: ./scripts/install-local-runtime.sh [--native]

set -e

# --- Default Options ---
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLEW="${PROJECT_ROOT}/gradlew"


chmod +x "$AQLI_CLI_BIN"

prewarm_installed_models
verify_installed_fast_paths

# 5. Final Instructions
echo ""
echo -e "${GREEN}✅ andalus CLI installed locally to ${AQLI_CLI_BIN}${NC}"
echo ""

if [[ ":$PATH:" != *":$BIN_DIR:"* ]]; then
    echo -e "${YELLOW}⚠ ${BIN_DIR} is not in your PATH.${NC}"
    echo -e "Add this to your .zshrc or .bashrc:"
    echo -e "  ${BLUE}export PATH=\"\$HOME/.local/bin:\$PATH\"${NC}"
    echo ""
fi

echo -e "Try running: ${YELLOW}andalus --version${NC}"
echo -e "${BLUE}--------------------------------------------------${NC}"
