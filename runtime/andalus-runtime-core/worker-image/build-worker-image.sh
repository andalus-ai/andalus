#!/usr/bin/env bash
set -euo pipefail

: "${JRE_IMAGE:?Set JRE_IMAGE to a Java 25 JRE image pinned with @sha256:<64-hex-digest>}"
: "${ANDALUS_SHADOW_TRUST_BUNDLE:?Set ANDALUS_SHADOW_TRUST_BUNDLE to the public-key trust bundle file}"
IMAGE_TAG="${IMAGE_TAG:-andalus-shadow-worker:local}"

if [[ ! "${JRE_IMAGE}" =~ @sha256:[0-9a-f]{64}$ ]]; then
  echo "JRE_IMAGE must be pinned by an immutable sha256 digest" >&2
  exit 2
fi
if [[ ! -f "${ANDALUS_SHADOW_TRUST_BUNDLE}" || -L "${ANDALUS_SHADOW_TRUST_BUNDLE}" ]]; then
  echo "ANDALUS_SHADOW_TRUST_BUNDLE must be a regular, non-symlink file" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${MODULE_DIR}/../.." && pwd)"
IMAGE_CONTEXT="${SCRIPT_DIR}"

# Install the reactor artifacts locally so the standalone dependency-copy step can
# resolve runtime-core's reactor dependencies. Install andalus-framework first as
# documented by the repository root README.
mvn -B -f "${REPO_ROOT}/pom.xml" -pl runtime/andalus-runtime-core -am -DskipTests install

rm -rf "${IMAGE_CONTEXT}/lib" "${IMAGE_CONTEXT}/target" "${IMAGE_CONTEXT}/trust"
mkdir -p "${IMAGE_CONTEXT}/lib" "${IMAGE_CONTEXT}/target" "${IMAGE_CONTEXT}/trust"

mvn -B -f "${MODULE_DIR}/pom.xml" \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
  -DincludeScope=runtime -DoutputDirectory="${IMAGE_CONTEXT}/lib"

mapfile -t jars < <(find "${MODULE_DIR}/target" -maxdepth 1 -type f \
  -name 'andalus-runtime-core-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar')
if [[ "${#jars[@]}" -ne 1 ]]; then
  echo "Expected exactly one runtime-core jar, found ${#jars[@]}" >&2
  exit 2
fi
install -m 0444 "${jars[0]}" "${IMAGE_CONTEXT}/target/andalus-runtime-core.jar"
install -m 0444 "${ANDALUS_SHADOW_TRUST_BUNDLE}" \
  "${IMAGE_CONTEXT}/trust/trusted-signers.ed25519"

docker build --pull --build-arg "JRE_IMAGE=${JRE_IMAGE}" \
  --tag "${IMAGE_TAG}" --file "${IMAGE_CONTEXT}/Dockerfile" "${IMAGE_CONTEXT}"

echo "Built local image ${IMAGE_TAG}."
echo "Push it to the deployment registry, resolve its immutable manifest digest, and configure"
echo "andalus.shadow-worker.image=<registry>/<image>@sha256:<published-manifest-digest>."
echo "Do not set image-validated=true until the exact pushed digest passes end-to-end validation."
