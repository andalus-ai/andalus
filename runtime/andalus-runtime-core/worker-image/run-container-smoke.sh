#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../../.." && pwd)"
MODULE_DIR="${REPO_ROOT}/runtime/andalus-runtime-core"
: "${WORKER_IMAGE:?Set WORKER_IMAGE to the locally built smoke image}"
command -v docker >/dev/null
command -v java >/dev/null

CP="${MODULE_DIR}/target/classes:${MODULE_DIR}/target/test-classes"
for dependency in "${MODULE_DIR}"/target/worker-smoke-lib/*.jar; do CP="${CP}:${dependency}"; done
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

java -cp "${CP}" tech.kayys.andalus.runtime.learning.WorkerSmokeFrameGenerator \
  | docker run --rm -i --network=none --read-only \
      --tmpfs /tmp:rw,noexec,nosuid,size=16m --user 65532:65532 \
      --cap-drop=ALL --security-opt=no-new-privileges --pids-limit=32 --memory=256m \
      "${WORKER_IMAGE}" >"${TMP}/response.bin" 2>"${TMP}/worker.stderr"

if [[ -s "${TMP}/worker.stderr" ]]; then
  echo "Unexpected worker stderr for protocol rejection:" >&2
  cat "${TMP}/worker.stderr" >&2
  exit 1
fi
java -cp "${CP}" tech.kayys.andalus.runtime.learning.WorkerSmokeResponseVerifier "${TMP}/response.bin"
echo "Container smoke passed: JVM bootstrap and protocol verified; production isolation is not attested."
