#!/usr/bin/env bash
# Wrapper gradlew that forwards to aqli's gradlew when top-level wrapper is absent
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [[ -n "${AQLI_HOME:-}" && -x "${AQLI_HOME}/gradlew" ]]; then
  TARGET="${AQLI_HOME}"
elif [[ -x "$ROOT_DIR/../aqli/gradlew" ]]; then
  TARGET="$ROOT_DIR/../aqli"
elif [[ -x "$ROOT_DIR/../../../Aqli/aqli/gradlew" ]]; then
  TARGET="$ROOT_DIR/../../../Aqli/aqli"
else
  echo "Error: upstream gradlew not found in adjacent aqli or Families/Aqli/aqli" >&2
  exit 1
fi

(cd "$TARGET" && exec "./gradlew" "$@")

