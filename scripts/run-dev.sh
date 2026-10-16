#!/bin/bash

set -euo pipefail

RESET="$(printf '\033[0m')"
BOLD="$(printf '\033[1m')"
GREEN="$(printf '\033[32m')"
CYAN="$(printf '\033[36m')"
YELLOW="$(printf '\033[33m')"
MAGENTA="$(printf '\033[35m')"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Removed help interception as it delegates to a broken script

source "$ROOT_DIR/scripts/module-selection-current.env"
ARCHITECTURE_VALUE="${ARCHITECTURE_TARGETS:-${ARCHITECTURE_TARGET:-native-java,binding}}"

if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_25_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
  if [[ -n "${JAVA_25_HOME}" ]]; then
    export JAVA_HOME="${JAVA_25_HOME}"
    export PATH="${JAVA_HOME}/bin:${PATH}"
  fi
fi

GRADLE_JAVA_HOME_ARG=()
if [[ -n "${JAVA_HOME:-}" ]]; then
  GRADLE_JAVA_HOME_ARG+=("-Dorg.gradle.java.home=${JAVA_HOME}")
fi
GRADLE_MAX_WORKERS_ARG=()
if [[ -n "${ANDALUS_GRADLE_MAX_WORKERS:-1}" ]]; then
  GRADLE_MAX_WORKERS_ARG+=("--max-workers=${ANDALUS_GRADLE_MAX_WORKERS:-1}")
fi

append_java_tool_option() {
  local option="$1"
  case " ${JAVA_TOOL_OPTIONS:-} " in
    *" ${option} "*) ;;
    *) JAVA_TOOL_OPTIONS="${option}${JAVA_TOOL_OPTIONS:+ ${JAVA_TOOL_OPTIONS}}" ;;
  esac
}

append_java_tool_option "--enable-native-access=ALL-UNNAMED"
append_java_tool_option "--add-modules=jdk.incubator.vector"
# Enable embedded Aqli only when explicitly requested (safer for dev).
# Set ENABLE_AQLI_EMBED=1 to force embedding on the JVM command line.
if [[ "${ENABLE_AQLI_EMBED:-0}" == "1" ]]; then
  append_java_tool_option "-Dandalus.aqli.enabled=true"
fi
export JAVA_TOOL_OPTIONS

# Use ~/.andalus/config.json as authoritative source for model/provider unless ANDALUS_IGNORE_CONFIG is set
CFG="$HOME/.andalus/config.json"
if [ -f "$CFG" ] && [ -z "${ANDALUS_IGNORE_CONFIG:-}" ]; then
  PROVIDER=$(grep -oE '"provider"[[:space:]]*:[[:space:]]*"[^"]+"' "$CFG" | sed -E 's/.*:[[:space:]]*"([^"]+)".*/\1/' | head -n1 || true)
  
  if [ -n "$PROVIDER" ]; then
    export ANDALUS_PROVIDER="$PROVIDER"
  fi

  # Try to find provider-specific model first (e.g. "cerebrasModel": "...")
  MODEL=""
  if [ -n "$PROVIDER" ] && [ "$PROVIDER" != "aqli" ]; then
    MODEL=$(grep -oE "\"${PROVIDER}Model\"[[:space:]]*:[[:space:]]*\"[^\"]+\"" "$CFG" | sed -E 's/.*:[[:space:]]*"([^"]+)".*/\1/' | head -n1 || true)
  fi

  # If not found and provider is aqli (or not set), fall back to global defaultModel
  if [ -z "$MODEL" ] && { [ -z "$PROVIDER" ] || [ "$PROVIDER" = "aqli" ]; }; then
    MODEL=$(grep -oE '"(model|defaultModel|default_model)"[[:space:]]*:[[:space:]]*"[^"]+"' "$CFG" | sed -E 's/.*:[[:space:]]*"([^"]+)".*/\1/' | head -n1 || true)
  fi

  if [ -n "$MODEL" ]; then
    export ANDALUS_MODEL="$MODEL"
  fi
fi

echo "${BOLD}${GREEN}:) Resolved backend targets:${RESET} ${BACKEND_TARGETS}"
echo "${BOLD}${GREEN}:) Resolved format targets:${RESET} ${FORMAT_TARGETS}"
echo "${BOLD}${GREEN}:) Resolved LLM targets:${RESET} ${LLM_TARGETS}"
echo "${BOLD}${MAGENTA}:) Architecture:${RESET} ${ARCHITECTURE_VALUE}"
echo "${BOLD}${MAGENTA}:) Profile:${RESET} ${BUILD_PROFILE}"
echo "${BOLD}${MAGENTA}:) Java home:${RESET} ${JAVA_HOME:-$(command -v java)}"
echo "${BOLD}${MAGENTA}:) Build mode:${RESET} Quarkus Dev"
echo "${BOLD}${GREEN}:) Selection manifest:${RESET} ${ROOT_DIR}/scripts/module-selection-current.env"
echo "$GREEN:) Module manifest resolved cleanly.$RESET"

cd "$ROOT_DIR"

# Forward arguments directly to the CLI (don't rewrite 'code' or 'agent')
if [[ "$#" -gt 0 && "$1" == "andalus" ]]; then
  shift
fi



# Prefer running already-built Andalus CLI artifact (no build). If not present, fall back to aqli-cli artifact.
ANDALUS_CLI_DIR="$ROOT_DIR/cli/andalus-cli"
# Prefer assembled artifact that names the CLI explicitly and avoid repackager 'original-' jars
ANDALUS_JAR_CANDIDATES=(
  "$ANDALUS_CLI_DIR/target/quarkus-app/quarkus-run.jar"
  "$ANDALUS_CLI_DIR/build/quarkus-app/quarkus-run.jar"
  "$ANDALUS_CLI_DIR/target"/*andalus-cli*.jar
  "$ANDALUS_CLI_DIR/build/libs"/*-runner.jar
  "$ANDALUS_CLI_DIR/build/libs"/*-all.jar
  "$ANDALUS_CLI_DIR/build/libs"/*.jar
  "$ANDALUS_CLI_DIR/target"/*.jar
)
ANDALUS_FOUND_JAR=""
for cand in "${ANDALUS_JAR_CANDIDATES[@]}"; do
  for f in $cand; do
    if [[ -f $f ]]; then
      base=$(basename "$f")
      # skip repackager 'original-' jars which lack Main-Class
      if [[ "$base" == original-* ]]; then
        continue
      fi
      ANDALUS_FOUND_JAR="$f"
      break 2
    fi
  done
done

if [[ -n "$ANDALUS_FOUND_JAR" ]]; then
  echo "Running Andalus CLI from artifact: $ANDALUS_FOUND_JAR"
  exec java -jar "$ANDALUS_FOUND_JAR" "$@"
else
  if [[ "${ALLOW_BUILD:-0}" == "1" ]]; then
    echo "No built andalus-cli artifact found; ALLOW_BUILD=1 so falling back to building andalus-cli via maven."
    cd "$ROOT_DIR"
    ./mvnw clean package -pl cli/andalus-cli -am -Dmaven.test.skip=true
    exec ./scripts/run-dev.sh "$@"
  else
    echo "No built andalus-cli artifact found. To avoid building, run './scripts/build-andalus.sh' first or set ALLOW_BUILD=1 to permit building here."
    exit 1
  fi
fi