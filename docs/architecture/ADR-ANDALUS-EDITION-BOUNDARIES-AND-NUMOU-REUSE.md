# ADR: Andalus Edition Boundaries and Numou Infrastructure Reuse

- Status: Proposed
- Date: 2026-11-05
- Scope: Andalus Community, Pro, Enterprise (EE), and optional infrastructure reuse from Numou

## Context

Andalus is an open-source, modular agent runtime/platform. Community must remain useful as a standalone distribution without commercial artifacts or mandatory managed infrastructure.

Repositories currently identified:
- `andalus-ai/andalus`: public, populated Community runtime repository.
- `andalus-ai/andalus-pro`: public repository exists but is currently empty.
- `andalus-ai/andalus-ee`: could not be resolved through the current GitHub connection (HTTP 404); confirm existence, visibility, and access before implementation.

The runtime repository guidance assigns reusable, infrastructure-independent contracts to `andalus-framework`; concrete runtime adapters, persistence, queues, CDI/Quarkus wiring, and deployment belong in the runtime. This ADR follows that boundary.

Numou (`andalus-ai/numu`) contains application-platform outbox and queue-management work. Some mechanics are relevant, but Numou's business outbox lifecycle is not the same domain contract as an observation DLQ.

## Decision

### 1. Three editions, two commercial capability layers

Maintain three product editions:
1. **Community** — open source, independently deployable, useful standalone distribution.
2. **Pro** — advanced operational and production capabilities.
3. **Enterprise (EE)** — organization-wide governance, compliance, federation, and fleet-scale capabilities.

Keep Pro and EE as separate capability/module boundaries and repositories, but make Enterprise a superset of Pro. An Enterprise deployment receives Community + Pro + EE capabilities as one compatible distribution. Do not create a separate Enterprise runtime fork.

The Pro and EE repositories are delivery/licensing boundaries, not permission to duplicate core runtime behavior. Commercial modules extend Community through stable public APIs and SPIs.

### 2. Dependency direction

```text
andalus-framework (open contracts and reusable capabilities)
                 ^
                 |
andalus Community runtime (open implementations and standalone distribution)
                 ^
                 |
andalus-pro (optional commercial modules)
                 ^
                 |
andalus-ee (enterprise-only extensions)
```

Rules:
- Framework APIs must not depend on runtime, Pro, EE, Numou, databases, or brokers.
- Community runtime may depend on framework APIs and open-source infrastructure adapters.
- Pro may depend on published Community/framework artifacts, never the reverse.
- EE may depend on compatible Pro and Community/framework artifacts.
- Community build, tests, startup, and standalone packaging must work without Pro/EE artifacts, licensing services, commercial databases, or commercial infrastructure.
- Avoid cyclic dependencies and cross-repository references to internal implementation packages.

### 3. Capability allocation

| Capability | Community | Pro | Enterprise |
|---|---|---|---|
| Agent runtime, lifecycle, framework contracts | Yes | Extends | Extends |
| Basic observability events, EventLedger, traces, metrics | Yes | Extends | Extends |
| Bounded in-memory DLQ and basic retry policy | Yes | Extends | Extends |
| Local/standalone deployment and baseline configuration | Yes | Extends | Extends |
| Advanced analytics, projections, retention controls | Basic | Yes | Yes |
| Operational dashboards, advanced replay operations | Basic | Yes | Yes |
| HA/distributed durable observability storage | Open SPI/basic adapter | Advanced options | Fleet-wide operation |
| Multi-cluster federation and centralized fleet management | No | Optional integrations | Yes |
| Advanced governance, compliance workflows, policy administration | Baseline security APIs | Advanced controls | Yes |
| Enterprise audit controls and organization-wide policy | No | Limited | Yes |

This is a proposed product boundary, not a claim that all listed modules already exist.

### 4. Observability and DLQ ownership

Community owns canonical observability contracts and basic operational reliability:
- Observation event model and EventLedger integration.
- Trace/correlation propagation and baseline metrics.
- Failure classification and retry policy.
- Bounded in-memory DLQ and consumer contracts.
- Storage SPI that does not force a particular database or broker.

Pro may add durable/high-availability DLQ adapters, retention, projections, analytics, replay administration, advanced dashboards, and production operations.

EE may add organization-wide governance/audit controls, fleet/multi-cluster observability, centralized administration, compliance reporting, and cross-environment policy enforcement.

EventLedger remains canonical event history. Analytics/projections are derived and rebuildable, not authoritative event stores.

Do not put the basic retry scheduler or the only usable DLQ implementation behind a commercial license. Community must remain operationally useful. Express durable storage through a Community SPI; include a basic open implementation where it can be maintained without mandatory infrastructure. Pro may supply advanced HA/managed implementations.

### 5. Numou reuse policy: reuse mechanics, not domain coupling

Do not add a direct Community runtime dependency on Numou merely to obtain outbox/DLQ functionality. Do not copy Numou's entire outbox or application-platform modules.

Preference order:
1. Reuse a published generic library/stable public module only if API, semantics, lifecycle, license, and dependency footprint fit Andalus.
2. Extract a genuinely generic capability into a neutral shared library only when both projects have multiple real consumers and agree on ownership, compatibility, and release governance.
3. Reimplement the small required mechanics in Andalus when Numou code is business-domain-specific or extraction creates more coupling than value. Reuse design ideas and tests, not source, unless license and provenance are verified.
4. Keep Numou service adapters optional; standalone Community must not depend on Numou services.

Potentially reusable mechanics: bounded claims/batches, lease expiry/recovery, retry scheduling/backoff, idempotency keys, terminal dead-letter state/counters, and PostgreSQL transaction/outbox patterns.

Do not transplant Numou-specific installation transitions, tenant RLS assumptions, provisioning aggregate reconciliation, or business event types into Andalus Observability. Observation records need their own event identity, delivery state, classification, retention, and replay semantics.

Before copying source, verify the exact file's license/notices, dependencies, tests, and ownership. A public repository alone does not establish code reuse rights across product/license boundaries.

### 6. Repository and module layout

Keep Community in the existing Community repository and follow the framework/runtime split. Proposed module names are illustrative; inspect the actual Maven reactor and artifact conventions before creating them.

**Community**
- Framework: observation event, publisher, failure, DLQ store, retry policy, handler and storage SPI contracts where genuinely reusable.
- Runtime: bounded in-memory queue, publisher integration, scheduler/consumer wiring, metrics and health integration.
- Optional open adapters: database/broker implementations only when there is a real standalone need and a clean dependency boundary.

**Pro repository**
- Commercial runtime extensions and production operations modules.
- Advanced observability persistence, analytics/projections, retention and replay management.
- Pro distribution/build metadata, entitlement integration, and compatibility tests against Community.

**EE repository**
- Enterprise governance, compliance, federation, fleet management, organization-wide policy/audit modules.
- EE distribution/build metadata and compatibility tests against exact supported Pro + Community versions.

Do not create empty placeholder modules in Community to reserve commercial names. Do not introduce commercial classes, licensing checks, or Pro/EE dependencies into Community.

### 7. Distribution strategy

Use a single compatible version train across Community, Pro, and EE, with explicit compatibility metadata.

- Community: install/run without account activation, licensing endpoint, or commercial artifact.
- Pro: Community plus Pro modules and required entitlement validation.
- EE: Community + Pro + EE modules, with enterprise entitlement and optional enterprise infrastructure.
- Support standalone, on-premises, and later managed deployment profiles without changing core runtime contracts.
- Assemble editions at packaging/deployment level; do not fork runtime lifecycle code.
- Add dependency checks that fail if Community imports Pro/EE packages.

Maven profiles alone are not a sufficient security boundary. Artifact publication, repository access, licensing, and runtime entitlement enforcement need an explicit release/security design.

## Consequences

Benefits: Community remains independently useful; Pro/EE evolve without runtime fragmentation; Enterprise is a superset rather than a fork; Numou remains independent; observability contracts stay portable across storage implementations.

Costs/follow-up: version compatibility policy and cross-repository CI; license/provenance review before source reuse; packaging and entitlement design; audit actual module POMs, packages, reactor inclusion, and release configuration before creating Pro/EE code modules.

## Implementation sequence

1. Complete Community DLQ with bounded in-memory semantics and nonblocking retry scheduling.
2. Confirm/add framework-owned storage SPI only after inspecting the framework contract tree.
3. Add Community conformance tests for storage adapters.
4. Evaluate Numou mechanics against the SPI; reuse only generic, license-cleared components.
5. Implement a basic open durable adapter if needed for standalone users; reserve advanced HA/analytics/replay operations for Pro.
6. Bootstrap `andalus-pro` with README, build conventions, dependency policy, compatibility matrix, and first real module.
7. Confirm/create `andalus-ee`, then bootstrap as an extension of Pro rather than a fork.
8. Add edition assembly and cross-repository compatibility CI.

## Open decisions

- Initial Community durable adapter: PostgreSQL, SQLite, filesystem, or separate optional project.
- Pro/EE license and entitlement mechanism.
- Commercial artifact distribution channel.
- Whether a generic outbox/lease library should be extracted from Numou after multiple consumers exist.
