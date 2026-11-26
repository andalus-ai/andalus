# Test-only worker container smoke test

This harness builds a disposable image containing the runtime JAR, runtime dependencies, and a
**test-only** ServiceLoader bootstrap. The bootstrap trusts no signer and rejects the deliberately
malformed request payload before any evaluator artifact can be loaded. It contains no production
trust keys, model artifacts, credentials, or deployment configuration.

The test verifies the real JVM process inside Docker, image classpath packaging, ServiceLoader
discovery, one-request stdin/stdout framing, response correlation, and clean stderr on a
protocol-level rejection. It does **not** verify production signer provisioning, artifact loading,
worker attestation, daemon isolation, or production readiness.

## Prerequisites

- Docker Engine and CLI.
- JDK matching the project toolchain (including `jar`).
- A built `andalus-runtime-core` module and compiled test classes.
- A Java runtime base image pinned by immutable digest.

## Build and run

From the repository root, first build the runtime module and compile tests using the normal
reactor build (including its sibling repository prerequisites). Then run:

```bash
JRE_IMAGE='eclipse-temurin:25-jre@sha256:<verified-64-hex-digest>' \
IMAGE_TAG='andalus-worker-smoke:local' \
bash runtime/andalus-runtime-core/worker-image/build-smoke-image.sh

WORKER_IMAGE='andalus-worker-smoke:local' \
bash runtime/andalus-runtime-core/worker-image/run-container-smoke.sh
```

Replace the illustrative digest placeholder with a digest verified from the selected image
registry. The scripts intentionally reject mutable tags.

The container is launched with no network, a read-only root filesystem, a non-root UID, dropped
Linux capabilities, no-new-privileges, and bounded memory/PIDs. The image is local and is never
published by these scripts.

## Security boundary

A passing result is only a packaging/process smoke test. Docker CLI flags are not an independent
security attestation: production use still requires validation of the daemon/host configuration,
container runtime policy, resource and syscall controls, artifact mount policy, worker-local
trust bundle, signer revocation behavior, and independent signature/digest verification. Do not
use the test bootstrap provider or this image for production shadow evaluation.
