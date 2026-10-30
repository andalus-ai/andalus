# Repository guidance for AI coding agents

This file applies to the entire Andalus Runtime repository. Read `DEVELOPMENT_GUIDELINES.md` before making architectural or cross-module changes.

## Repository role

- This repository contains the deployable/runtime side of Andalus: concrete execution, adapters, integrations, APIs, CLI, deployment and operational wiring.
- Reusable contracts and implementation-neutral abstractions belong in `andalus-framework` unless an existing, documented boundary says otherwise.
- Do not create a second public contract in the runtime when the framework already owns that concept. If a contract is missing, propose and review the framework change first.

## Before changing code

1. Inspect the target module's `pom.xml`, README, source packages and tests.
2. Trace callers and implementations across both repositories for public API or lifecycle changes.
3. Check whether the target module is part of the root Maven reactor. Some modules may be intentionally excluded; do not silently enable them.
4. Prefer the smallest change that fixes the problem. Avoid unrelated refactors and broad dependency upgrades.
5. Never assume a dependency, service, database, broker or external provider is available in every deployment.

## Architecture rules

- Keep HTTP/gRPC/CLI transport concerns in API or CLI modules; do not move transport dependencies into framework contracts.
- Keep Quarkus/CDI wiring, persistence, queues, provider adapters and infrastructure-specific implementations in runtime modules.
- Depend on framework APIs rather than duplicating framework behavior.
- Keep module dependencies acyclic and as narrow as possible. Avoid depending on another module's internal packages.
- Preserve tenant, user, security and execution identity across asynchronous boundaries. Never use a null tenant or user as an implicit production default.
- Treat checkpoints, retries, cancellation, approval and resumption as durable lifecycle concerns; document their consistency and idempotency behavior.
- Do not log prompts, secrets, credentials, tokens, personal data or full tool payloads by default.

## Java and Maven

- Follow the Java version and Maven configuration declared by the repository; currently the parent POM targets Java 25.
- Use the Maven Wrapper (`./mvnw`) rather than relying on a locally installed Maven version.
- Keep dependency versions in parent dependency management/BOMs where practical. Do not add an ad-hoc version if the project already manages it.
- Avoid adding direct dependencies to Quarkus or infrastructure libraries in modules that should remain framework-neutral.
- Do not change artifact coordinates, publishing destinations, module membership or release versions without checking both repositories and documenting the migration.

## Tests and quality

- Add or update unit tests for behavior changes and regression tests for bug fixes.
- Add integration or contract tests when changing module boundaries, persistence, messaging, APIs, provider adapters or framework/runtime compatibility.
- Test failure paths: timeout, cancellation, retry, duplicate delivery, invalid state transition, unavailable dependency and partial failure where relevant.
- Do not weaken, skip or delete tests merely to make a build pass. Explain any unavoidable exclusion.
- Run the narrowest relevant module tests first, then the appropriate reactor verification. Record commands and results; do not claim tests passed if they were not run.

## API and compatibility

- Treat published interfaces, DTOs, event schemas, configuration keys and persisted checkpoint formats as compatibility-sensitive.
- Prefer additive changes. For breaking changes, provide migration notes and a compatibility/deprecation plan.
- Validate untrusted input at boundaries. Apply authorization before invoking tools or mutating external state.
- Keep error responses structured and avoid exposing internal stack traces or secrets to callers.

## Documentation and completion

- Update the affected module README and examples when public behavior or configuration changes.
- Document new configuration, permissions, external dependencies, operational implications and migration steps.
- In the final change summary, list changed modules, tests actually run, known limitations and any required framework repository change.
