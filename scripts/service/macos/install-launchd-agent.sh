#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
ANDALUS_HOME="${ANDALUS_HOME:-$HOME/.andalus}"
BUNDLED_ANDALUS_HOME="${BUNDLE_ROOT}/.andalus"

if [ -d "$BUNDLED_ANDALUS_HOME" ] && [ "$BUNDLED_ANDALUS_HOME" != "$ANDALUS_HOME" ]; then
  mkdir -p "$ANDALUS_HOME"
  tar -C "$BUNDLED_ANDALUS_HOME" -cf - . | tar -C "$ANDALUS_HOME" -xf -
fi

ANDALUS_CONFIG_DIR="${ANDALUS_CONFIG_DIR:-$ANDALUS_HOME/config}"
ANDALUS_LOG_DIR="${ANDALUS_LOG_DIR:-$ANDALUS_HOME/logs}"
ANDALUS_SERVER_LOG_DIR="${ANDALUS_SERVER_LOG_DIR:-$ANDALUS_LOG_DIR/server}"
ANDALUS_LOG_FILE_PATH="${ANDALUS_LOG_FILE_PATH:-$ANDALUS_SERVER_LOG_DIR/server.log}"
ANDALUS_PLUGINS_DIR="${ANDALUS_PLUGINS_DIR:-$ANDALUS_HOME/plugins}"
ANDALUS_SECRETS_DIR="${ANDALUS_SECRETS_DIR:-$ANDALUS_HOME/secrets}"
ANDALUS_MODELS_DIR="${ANDALUS_MODELS_DIR:-$ANDALUS_HOME/models}"
ANDALUS_MCP_DIR="${ANDALUS_MCP_DIR:-$ANDALUS_HOME/mcp}"
ANDALUS_RUN_DIR="${ANDALUS_RUN_DIR:-$ANDALUS_HOME/run}"
ANDALUS_VECTOR_DIR="${ANDALUS_VECTOR_DIR:-$ANDALUS_HOME/vector}"
ANDALUS_AQLI_HOME="${ANDALUS_AQLI_HOME:-$ANDALUS_HOME/aqli}"
LEGACY_AQLI_HOME="${AQLI_HOME:-$HOME/.aqli}"

if mkdir -p "$ANDALUS_AQLI_HOME" 2>/dev/null; then
  AQLI_HOME="$ANDALUS_AQLI_HOME"
else
  mkdir -p "$LEGACY_AQLI_HOME"
  AQLI_HOME="$LEGACY_AQLI_HOME"
fi

ANDALUS_AQLI_MODELS_DIR="${ANDALUS_AQLI_MODELS_DIR:-$AQLI_HOME/models}"
ANDALUS_AQLI_STORAGE_DIR="${ANDALUS_AQLI_STORAGE_DIR:-$AQLI_HOME/storage}"

mkdir -p \
  "$ANDALUS_CONFIG_DIR" \
  "$ANDALUS_SERVER_LOG_DIR" \
  "$ANDALUS_PLUGINS_DIR" \
  "$ANDALUS_SECRETS_DIR" \
  "$ANDALUS_MODELS_DIR" \
  "$ANDALUS_MCP_DIR" \
  "$ANDALUS_RUN_DIR" \
  "$ANDALUS_VECTOR_DIR" \
  "$ANDALUS_AQLI_MODELS_DIR" \
  "$ANDALUS_AQLI_STORAGE_DIR" \
  "$HOME/Library/LaunchAgents"

ANDALUS_EXECUTABLE="${ANDALUS_EXECUTABLE:-$ANDALUS_HOME/bin/andalus}"
if [ ! -x "$ANDALUS_EXECUTABLE" ]; then
  chmod +x "$ANDALUS_EXECUTABLE" 2>/dev/null || true
fi
if [ ! -x "$ANDALUS_EXECUTABLE" ]; then
  echo "Andalus executable not found or not executable: $ANDALUS_EXECUTABLE" >&2
  exit 1
fi

PLIST_FILE="$HOME/Library/LaunchAgents/tech.kayys.andalus.plist"
cat > "$PLIST_FILE" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>tech.kayys.andalus</string>
  <key>ProgramArguments</key>
  <array>
    <string>$ANDALUS_EXECUTABLE</string>
  </array>
  <key>WorkingDirectory</key>
  <string>$ANDALUS_HOME</string>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>StandardOutPath</key>
  <string>$ANDALUS_SERVER_LOG_DIR/stdout.log</string>
  <key>StandardErrorPath</key>
  <string>$ANDALUS_SERVER_LOG_DIR/stderr.log</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>ANDALUS_HOME</key><string>$ANDALUS_HOME</string>
    <key>ANDALUS_CONFIG_DIR</key><string>$ANDALUS_CONFIG_DIR</string>
    <key>ANDALUS_LOG_DIR</key><string>$ANDALUS_LOG_DIR</string>
    <key>ANDALUS_SERVER_LOG_DIR</key><string>$ANDALUS_SERVER_LOG_DIR</string>
    <key>ANDALUS_LOG_FILE_PATH</key><string>$ANDALUS_LOG_FILE_PATH</string>
    <key>ANDALUS_PLUGINS_DIR</key><string>$ANDALUS_PLUGINS_DIR</string>
    <key>ANDALUS_SECRETS_DIR</key><string>$ANDALUS_SECRETS_DIR</string>
    <key>ANDALUS_MODELS_DIR</key><string>$ANDALUS_MODELS_DIR</string>
    <key>ANDALUS_MCP_DIR</key><string>$ANDALUS_MCP_DIR</string>
    <key>ANDALUS_RUN_DIR</key><string>$ANDALUS_RUN_DIR</string>
    <key>ANDALUS_VECTOR_DIR</key><string>$ANDALUS_VECTOR_DIR</string>
    <key>ANDALUS_AQLI_HOME</key><string>$ANDALUS_AQLI_HOME</string>
    <key>AQLI_HOME</key><string>$AQLI_HOME</string>
    <key>ANDALUS_AQLI_MODELS_DIR</key><string>$ANDALUS_AQLI_MODELS_DIR</string>
    <key>ANDALUS_AQLI_STORAGE_DIR</key><string>$ANDALUS_AQLI_STORAGE_DIR</string>
  </dict>
</dict>
</plist>
EOF

launchctl unload "$PLIST_FILE" 2>/dev/null || true
launchctl load "$PLIST_FILE"
echo "Installed Andalus launchd agent at $PLIST_FILE"
