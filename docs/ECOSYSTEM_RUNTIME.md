# Andalus Runtime Ecosystem Architecture

Status: proposed implementation baseline for Phase 5  
Scope: runtime distribution, developer tooling, and ecosystem operations

## Goal

Provide a safe path from discovering an Andalus extension to installing, configuring, approving, activating, operating, upgrading, and removing it.

The runtime is the consumer and enforcement point for ecosystem artifacts. It must not treat a marketplace listing as trusted executable code.

## Responsibilities

The runtime owns:

- Catalog and artifact-store adapters.
- Artifact resolution and compatibility checks.
- Installation and upgrade orchestration.
- Local installed-artifact inventory.
- Trust-root configuration and signature verification integration.
- Permission review and approval workflow.
- Runtime activation and health checks.
- CLI and API surfaces for ecosystem operations.
- Audit events and operational status.

The framework owns portable contracts and SPIs. A hosted marketplace owns publisher accounts, public search, moderation, and distribution. The runtime remains usable with local directories and private catalogs.

## Lifecycle

```text
Search / Configure Catalog
          |
          v
Inspect Artifact Metadata
          |
          v
Resolve Exact Version + Dependencies
          |
          v
Verify Digest + Signature + Provenance
          |
          v
Validate Manifest + Compatibility
          |
          v
Review Requested Capabilities / Permissions
          |
          v
Approve (when policy requires)
          |
          v
Install into Isolated Artifact Store
          |
          v
Activate through Existing Plugin / Skill Lifecycle
          |
          v
Health Check + Audit
```

Discovery must not install. Installation must not activate. Activation must not bypass authorization or sandbox policy.

## Developer experience increments

### Local-first authoring

The first useful developer workflow should work without a hosted marketplace:

- `andalus extension init` creates a minimal extension project.
- `andalus extension validate` validates manifest, schemas, and compatibility.
- `andalus extension test` runs contract tests.
- `andalus extension package` creates a reproducible package and digest.
- `andalus extension inspect` displays package metadata and permissions.

These commands are a target UX, not a claim that the commands already exist.

### Registry operations

Later commands may include:

- `andalus catalog add|list|remove`
- `andalus search`
- `andalus install`
- `andalus inspect`
- `andalus approve`
- `andalus activate|deactivate`
- `andalus upgrade`
- `andalus uninstall`

All mutating commands should support dry-run where practical and emit structured output for automation.

### API and SDK

Expose versioned APIs for catalog search, artifact inspection, installation status, lifecycle operations, and compatibility diagnostics. Generate SDKs from the same API schema rather than maintaining handwritten clients independently.

## Security requirements

- Default to local/private catalogs; public catalogs are opt-in.
- Pin artifact versions and content digests.
- Verify signatures against explicitly configured trust roots.
- Enforce tenant and principal authorization for every mutation.
- Treat manifest descriptions, README content, and tool metadata as untrusted input.
- Require explicit permission review for network, filesystem, secrets, process, and data access.
- Execute extensions under sandbox and resource limits where applicable.
- Record publisher, digest, signer, requested permissions, approver, and lifecycle transitions.
- Support quarantine and revocation without silently deleting audit history.

## Rollout

1. Document artifact manifest and compatibility rules in the framework.
2. Add offline validation and packaging to the developer toolchain.
3. Add local artifact installation and inventory.
4. Integrate installation with existing PluginManager and SkillLifecycleManager.
5. Add private catalog support and trust policy.
6. Add hosted marketplace and publisher workflows only after the local lifecycle is secure and stable.

## Acceptance criteria

- A developer can build, validate, package, and test an extension offline.
- A runtime can install from a local artifact source.
- Exact version and digest are recorded.
- Signature and compatibility failures block activation.
- Permission review occurs before activation.
- Existing plugin and skill lifecycle APIs are reused.
- Every state-changing operation is authorized and audited.
- Runtime does not require a public marketplace to operate.
