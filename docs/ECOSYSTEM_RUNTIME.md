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


## Optional integration models for domain products

Andalus remains a **domain-neutral agent runtime and orchestration foundation**. It provides reusable execution, model/provider routing, tools, context, memory, delegation, lifecycle, policy hooks and observability. It does not own the business workflows, domain state, data authority or product experience of applications that use it.

Products such as coding systems, analytics platforms, research applications and future domain products have several ways to use or integrate with Andalus. They are options, not mandatory layers, and a product does not need to adopt every option.

### 1. Use the framework libraries directly (embedded)

A product can depend on selected Andalus Framework contracts/modules and compose the runtime in-process through Java APIs/SPIs. This is appropriate when the product wants low overhead, local execution, a desktop application or a single-process deployment.

- Select only the framework capabilities required; do not import the entire framework by default.
- Keep product-owned domain services and state outside Andalus.
- Verify JDK, BOM, dependency graph, lifecycle and security compatibility before embedding.
- A product may use framework contracts without adopting the full Andalus Runtime application.

### 2. Run Andalus as a local sidecar

A product can run Andalus Runtime as a separate process on the same host and communicate through a versioned local protocol (for example stdio/IPC, local gRPC or HTTP). This is useful for process isolation, native dependencies, independent restart/lifecycle or local desktop applications.

### 3. Integrate with a remote Andalus Runtime service

A product can call a separately deployed Andalus Runtime over versioned HTTP/gRPC APIs. This supports shared orchestration, centralized model/provider operations, multi-user deployments and independently scaled execution services.

Remote contracts should include authenticated identity propagation, tenant and capability context, correlation IDs, deadlines, budgets, streaming events, cancellation, idempotency and recoverable task status.

### 4. Expose selected capabilities as tools

A product can register narrow tools with Andalus so a general-purpose agent can invoke approved domain operations. Tools should call the product's application/use-case layer, not bypass its domain authorization or access raw infrastructure directly.

### 5. Register a specialist domain agent

A product can expose a specialist agent that Andalus can delegate a bounded task to. The specialist product owns its internal domain loop, state and validation. Andalus coordinates the delegation and consumes progress, artifacts and the final outcome; it should not micromanage every domain operation.

### 6. Use interoperability adapters

MCP, A2A and other protocols can connect Andalus or a domain product to external tools and agent ecosystems. Treat these as boundary adapters. They are not mandatory internal communication mechanisms and must not define the product's canonical domain model.

### One contract, multiple deployment choices

Where practical, preserve the same domain-facing extension contract across embedded, sidecar and remote deployments. The transport adapter changes; domain semantics and ownership do not. Do not introduce a distributed control plane, message broker or Kubernetes requirement before product and operational needs justify it.

### Ownership and trust boundaries

Andalus owns generic agent execution, orchestration, delegation records and runtime observability. Each integrating product remains authoritative for its domain data, workflows, domain authorization and canonical artifacts. Shared identity/tenancy services may be reused where compatible, but trusted actor and tenant context must be validated at every boundary.

A capability descriptor advertises what an extension can do; it does not grant permission. Enforce authentication, authorization, least privilege, policy, resource limits, audit and cancellation in embedded and remote modes alike.

Cifr and Farabi are examples of independent domain products that may integrate with Andalus through these options. They are not special cases hard-coded into the generic Andalus runtime, and Andalus must not acquire coding-specific or analytics-specific dependencies.
