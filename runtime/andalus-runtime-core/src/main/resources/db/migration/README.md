


## Mandatory isolated evaluator execution boundary (security hardening)

The earlier paired-evaluator and managed-resolver descriptions above are superseded
for execution safety by this section. The filesystem resolver and artifact loader
must not instantiate untrusted evaluator code in the application process.

`ShadowEvaluationExecutionBoundary` is the deployment SPI for an isolated worker.
It accepts signed evaluator-artifact manifests and immutable dataset/evaluation
metadata, never an `Evaluator` instance. Implementations must load the artifact
inside an OS-enforced container or microVM, independently verify the artifact
digest in the worker, enforce CPU/memory/time/output limits, avoid inheriting
application credentials, restrict network access, and terminate the complete
worker process tree on timeout. A classloader, thread, or ordinary subprocess
alone is not a security boundary.

The `shadow-evaluation-trust` readiness check now requires exactly one execution
boundary declaring `CONTAINER` or `MICRO_VM` isolation. Missing, ambiguous,
`NONE`, or `PROCESS_ONLY` implementations keep readiness DOWN. The legacy
`ShadowEvaluationService.evaluateAndRecord(RunRequest, ...)` entry point is
deliberately fail-closed because its request contains host-loaded evaluator
instances. It will not execute them. The new SPI is a contract only at this
stage; no built-in container runtime or production worker adapter is bundled.
Consequently shadow evaluator execution remains disabled until a deployment
provides and tests a conforming worker and the manifest verification path is
refactored to verify bytes without host-side evaluator construction.
