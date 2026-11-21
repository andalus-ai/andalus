# Shadow evaluation worker image

This directory contains the worker container recipe and a guarded repeatable local build
script. It does not claim bit-for-bit reproducibility across Docker builders. The image is a single-request worker; it is not a production isolation
attestation by itself.

## Prerequisites

- Docker CLI and access to a trusted Docker daemon.
- JDK matching the repository's Java 25 target (the `jar` tool is used to
  verify the worker bootstrap service descriptor).
- A Java 25 JRE base image reference pinned by digest.
- The built `andalus-runtime-core.jar`, its runtime dependency JARs, and the
  deployment-owned public signer trust bundle.

## Build

From this directory, for example:

```bash
JRE_IMAGE='registry.example/java:25-jre@sha256:<64-hex-digest>' \
IMAGE_TAG='andalus-shadow-worker:local' \
TRUST_BUNDLE='/secure/deployment/trusted-signers.ed25519' \
bash build.sh
```

The default artifact locations are:

- `../target/andalus-runtime-core.jar`
- `../target/worker-lib/*.jar`

Override them with `WORKER_JAR` and `WORKER_LIB_DIR` when the deployment
assembly uses a different layout. The script validates the digest-pinned base
reference, required inputs, dependency presence, and the exact
`BootstrapProvider` service descriptor before invoking Docker. It builds from
a temporary context and does not copy trust material into the repository.

The script writes a local metadata file (default:
`worker-image-build.txt` at repository root) containing the local image ID,
any repository digests already known to Docker, source revision, and input
SHA-256 values. A local image ID is not a registry manifest digest. The script
does not push the image, sign provenance, or validate the Docker daemon.

## Security and release requirements

- Keep the trust bundle deployment-controlled and read-only. It contains
  public verification keys, not private signing keys.
- Pin the base image by digest; do not use a mutable tag alone.
- Review the complete runtime dependency set and ensure no application
  credentials are included in the image.
- Publish through the organization's registry pipeline, then capture the
  registry-resolved manifest digest and signed build provenance.
- Validate the actual daemon/runtime policy (network isolation, read-only root,
  non-root identity, dropped capabilities, resource limits, no host mounts,
  process-tree termination) in the deployment environment.
- Independently verify artifact signatures and digests in the worker before
  evaluator class loading. The worker classloader is not a sandbox.

The script provides repeatable input validation and build invocation, not a guarantee of bit-for-bit identical image bytes across builders. A successful local build is only a build result. It must not set the
`image-validated` or `daemon-validated` deployment attestations and must not
enable production shadow execution without the separate isolation review.
