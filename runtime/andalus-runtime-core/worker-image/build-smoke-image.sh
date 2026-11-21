#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../../.." && pwd)"
MODULE_DIR="${REPO_ROOT}/runtime/andalus-runtime-core"

: "${JRE_IMAGE:?Set JRE_IMAGE to a digest-pinned JRE image}"
: "${IMAGE_TAG:?Set IMAGE_TAG to a local smoke image tag}"
[[ "${JRE_IMAGE}" =~ @sha256:[0-9a-f]{64}$ ]] || {
  echo "JRE_IMAGE must end in @sha256:<64 lowercase hex>" >&2; exit 2;
}
command -v docker >/dev/null
command -v jar >/dev/null

RUNTIME_JAR="$(find "${MODULE_DIR}/target" -maxdepth 1 -type f \
  -name 'andalus-runtime-core-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
  ! -name '*-tests.jar' | head -n 1)"
[[ -n "${RUNTIME_JAR}" ]] || { echo "Build runtime module first" >&2; exit 2; }
[[ -d "${MODULE_DIR}/target/test-classes" ]] || {
  echo "Run test-compile before building smoke image" >&2; exit 2;
}

"${REPO_ROOT}/mvnw" -B -ntp -f "${MODULE_DIR}/pom.xml" dependency:copy-dependencies \
  -DincludeScope=runtime -DoutputDirectory="${MODULE_DIR}/target/worker-smoke-lib"

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT
mkdir -p "${TMP}/lib"
cp "${RUNTIME_JAR}" "${TMP}/runtime.jar"
cp "${MODULE_DIR}/target/worker-smoke-lib/"*.jar "${TMP}/lib/"
jar --create --file "${TMP}/smoke-bootstrap.jar" \
  -C "${MODULE_DIR}/target/test-classes" tech/kayys/andalus/runtime/learning/WorkerSmokeTestBootstrapProvider.class \
  -C "${MODULE_DIR}/target/test-classes" 'META-INF/services/tech.kayys.andalus.runtime.learning.ShadowEvaluationWorkerEntrypoint$BootstrapProvider'
cp "${SCRIPT_DIR}/Dockerfile.smoke" "${TMP}/Dockerfile"
docker build --pull --build-arg "JRE_IMAGE=${JRE_IMAGE}" \
  --label org.opencontainers.image.title="Andalus test-only worker smoke fixture" \
  -t "${IMAGE_TAG}" "${TMP}"
echo "Built ${IMAGE_TAG}; this is not a production image or isolation attestation."
