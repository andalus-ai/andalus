#!/bin/bash
set -euo pipefail

RESET="$(printf '\033[0m')"
BOLD="$(printf '\033[1m')"
GREEN="$(printf '\033[32m')"
CYAN="$(printf '\033[36m')"
YELLOW="$(printf '\033[33m')"
MAGENTA="$(printf '\033[35m')"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
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

append_java_tool_option() {
  local option="$1"
  case " ${JAVA_TOOL_OPTIONS:-} " in
    *" ${option} "*) ;;
    *) JAVA_TOOL_OPTIONS="${option}${JAVA_TOOL_OPTIONS:+ ${JAVA_TOOL_OPTIONS}}" ;;
  esac
}

append_java_tool_option "--enable-native-access=ALL-UNNAMED"
append_java_tool_option "--add-modules=jdk.incubator.vector"
export JAVA_TOOL_OPTIONS

is_legacy_gradle_module() {
  case "$1" in
    :ml:*|:trainer:*|:examples:aqli-ml-examples|:models:aqli-model-common|:runner:onnx:aqli-ml-export-onnx|:runner:onnx:aqli-ml-onnx|:runner:diffuser:aqli-diffuser) return 0 ;;
    *) return 1 ;;
  esac
}

MODULE_ARRAY=()
if [[ -n "${MODULES:-}" ]]; then
  read -r -a MODULE_ARRAY <<< "${MODULES}"
fi
SUPPORTED_MODULES=()
SKIPPED_MODULES=()
for module in "${MODULE_ARRAY[@]}"; do
  if is_legacy_gradle_module "$module"; then
    SKIPPED_MODULES+=("$module")
    continue
  fi
  # Map Gradle project path to filesystem path and check existence (e.g. :ui:andalus-cli -> ui/andalus-cli)
  module_path="${module#":"}"
  module_path_fs="${module_path//:/\/}"
  if [[ -d "$ROOT_DIR/$module_path_fs" || -d "$ROOT_DIR/../aqli/$module_path_fs" || -d "$ROOT_DIR/../../../Aqli/aqli/$module_path_fs" ]]; then
    SUPPORTED_MODULES+=("$module")
  else
    SKIPPED_MODULES+=("$module")
  fi
done

GRADLE_BUILD_TASKS=(clean)
if [[ ${#SUPPORTED_MODULES[@]} -gt 0 ]]; then
  for module in "${SUPPORTED_MODULES[@]}"; do
    GRADLE_BUILD_TASKS+=("${module}:build" "${module}:publishToMavenLocal")
  done
fi

echo "${BOLD}${CYAN}:) Resolved backend targets:${RESET} ${BACKEND_TARGETS}"
echo "${BOLD}${CYAN}:) Resolved format targets:${RESET} ${FORMAT_TARGETS}"
echo "${BOLD}${CYAN}:) Resolved LLM targets:${RESET} ${LLM_TARGETS}"
echo "${BOLD}${MAGENTA}:) Architecture:${RESET} ${ARCHITECTURE_VALUE}"
echo "${BOLD}${MAGENTA}:) Profile:${RESET} ${BUILD_PROFILE}"
echo "${BOLD}${MAGENTA}:) Java home:${RESET} ${JAVA_HOME:-$(command -v java)}"
echo "${BOLD}${GREEN}:) Selection manifest:${RESET} ${ROOT_DIR}/scripts/module-selection-current.env"
echo "$GREEN:) Module manifest resolved cleanly.$RESET"

if [[ ${#SKIPPED_MODULES[@]} -gt 0 ]]; then
  echo "${BOLD}${YELLOW}:| Warning:${RESET} skipping legacy Gradle modules in wrapper build:"
  for module in "${SKIPPED_MODULES[@]}"; do
    echo "   - ${module}"
  done
fi

./gradlew "${GRADLE_JAVA_HOME_ARG[@]}" "${GRADLE_BUILD_TASKS[@]}" \
  -Paqli.backend="${RESOLVED_BACKEND_PROPERTY}" \
  -Paqli.profile="${BUILD_PROFILE}" \
  -Paqli.model.formats="${FORMAT_TARGETS}" \
  -Paqli.llm.types="${LLM_TARGETS}" \
  -Paqli.architecture="${ARCHITECTURE_VALUE}"
