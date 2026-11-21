#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage:
  JRE_IMAGE='registry/name:tag@sha256:<64-hex>' \
  IMAGE_TAG='andalus-shadow-worker:local' \
  TRUST_BUNDLE='/absolute/path/trusted-signers.ed25519' \
  [WORKER_JAR='/path/to/andalus-runtime-core.jar'] \
  [WORKER_LIB_DIR='/path/to/runtime-dependencies'] \
  [BUILD_METADATA='/path/to/worker-image-build.txt'] \
  ./build.sh

The base image must be digest-pinned. This script builds locally and records the
local image ID; it does not publish an image or claim a registry digest.
USAGE
}

fail() { echo "worker-image-build: $*" >&2; exit 2; }
command -v docker >/dev/null 2>&1 || fail "docker CLI is required"
command -v jar >/dev/null 2>&1 || fail "JDK jar tool is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"

: "${JRE_IMAGE:?JRE_IMAGE must be set to a digest-pinned Java 25 JRE image}"
: "${IMAGE_TAG:?IMAGE_TAG must be set}"
: "${TRUST_BUNDLE:?TRUST_BUNDLE must point to the public signer trust bundle}"

if [[ ! "$JRE_IMAGE" =~ ^[^[:space:]@]+@sha256:[a-fA-F0-9]{64}$ ]]; then
  fail "JRE_IMAGE must end with @sha256:<64 hexadecimal characters>"
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd)"
WORKER_JAR="${WORKER_JAR:-$REPO_ROOT/runtime/andalus-runtime-core/target/andalus-runtime-core.jar}"
WORKER_LIB_DIR="${WORKER_LIB_DIR:-$REPO_ROOT/runtime/andalus-runtime-core/target/worker-lib}"
BUILD_METADATA="${BUILD_METADATA:-$REPO_ROOT/worker-image-build.txt}"
TRUST_BUNDLE="$(cd -- "$(dirname -- "$TRUST_BUNDLE")" && pwd)/$(basename -- "$TRUST_BUNDLE")"

[[ -f "$WORKER_JAR" ]] || fail "worker JAR not found: $WORKER_JAR"
[[ -d "$WORKER_LIB_DIR" ]] || fail "dependency directory not found: $WORKER_LIB_DIR"
[[ -f "$TRUST_BUNDLE" && -s "$TRUST_BUNDLE" ]] || fail "trust bundle missing or empty: $TRUST_BUNDLE"

PROVIDER='META-INF/services/tech.kayys.andalus.runtime.learning.ShadowEvaluationWorkerEntrypoint$BootstrapProvider'
jar tf "$WORKER_JAR" | grep -Fqx "$PROVIDER" || fail "worker JAR lacks BootstrapProvider service descriptor"
find "$WORKER_LIB_DIR" -maxdepth 1 -type f -name '*.jar' -print -quit | grep -q . || fail "no dependency JARs found in $WORKER_LIB_DIR"

TMP_CONTEXT="$(mktemp -d)"
cleanup() { rm -rf "$TMP_CONTEXT"; }
trap cleanup EXIT
mkdir -p "$TMP_CONTEXT/target" "$TMP_CONTEXT/lib" "$TMP_CONTEXT/trust"
cp "$SCRIPT_DIR/Dockerfile" "$TMP_CONTEXT/Dockerfile"
cp "$WORKER_JAR" "$TMP_CONTEXT/target/andalus-runtime-core.jar"
cp "$WORKER_LIB_DIR"/*.jar "$TMP_CONTEXT/lib/"
cp "$TRUST_BUNDLE" "$TMP_CONTEXT/trust/trusted-signers.ed25519"
chmod 0444 "$TMP_CONTEXT/target/andalus-runtime-core.jar" "$TMP_CONTEXT/trust/trusted-signers.ed25519"
find "$TMP_CONTEXT/lib" -type f -name '*.jar' -exec chmod 0444 {} +

REVISION="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
JAR_SHA="$(sha256sum "$WORKER_JAR" | awk '{print $1}')"
TRUST_SHA="$(sha256sum "$TRUST_BUNDLE" | awk '{print $1}')"
docker build --pull --tag "$IMAGE_TAG" \
  --build-arg "JRE_IMAGE=$JRE_IMAGE" \
  --label "org.opencontainers.image.title=Andalus Shadow Evaluation Worker" \
  --label "org.opencontainers.image.revision=$REVISION" \
  --label "org.opencontainers.image.source=https://github.com/andalus-ai/andalus" \
  --label "tech.kayys.andalus.worker.jar.sha256=$JAR_SHA" \
  --label "tech.kayys.andalus.worker.trust-bundle.sha256=$TRUST_SHA" \
  "$TMP_CONTEXT"

IMAGE_ID="$(docker image inspect --format '{{.Id}}' "$IMAGE_TAG")"
[[ "$IMAGE_ID" =~ ^sha256:[a-fA-F0-9]{64}$ ]] || fail "Docker returned an unexpected local image ID"
REPO_DIGESTS="$(docker image inspect --format '{{json .RepoDigests}}' "$IMAGE_TAG")"
mkdir -p "$(dirname -- "$BUILD_METADATA")"
{
  echo "image_tag=$IMAGE_TAG"
  echo "local_image_id=$IMAGE_ID"
  echo "repo_digests=$REPO_DIGESTS"
  echo "base_image=$JRE_IMAGE"
  echo "source_revision=$REVISION"
  echo "worker_jar_sha256=$JAR_SHA"
  echo "trust_bundle_sha256=$TRUST_SHA"
  echo "built_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "publication_status=not_published"
  echo "isolation_validation=not_attested"
} > "$BUILD_METADATA"

echo "Built local worker image: $IMAGE_TAG ($IMAGE_ID)"
echo "Metadata: $BUILD_METADATA"
