# Numou Agent Integration — M0 Repository Audit and Architecture

- Status: M0 targeted repository audit and integration architecture.
- Date: 2026-11-06
- Repositories: `andalus-ai/andalus` (agent runtime), `andalus-ai/numu` (business capability platform)
- Scope: architecture and integration contract; no production runtime code changed.

## 1. Objective

Expose Numou business and operational capabilities to Andalus agents through a secure, discoverable tool interface, primarily MCP over HTTP.

A user should be able to ask an agent to inspect transactions for a period, configure a workforce profile, diagnose application logs, explain payment failures, or summarize branch performance. The agent must use authorized Numou application services and capability contracts rather than querying internal databases or bypassing domain rules.

The tool catalog should reflect the capabilities enabled in a particular Numou application, deployment, tenant and user authorization context. It must not advertise every capability in the source repository to every agent.

## 2. Repository findings

### Andalus Runtime

The current repository describes itself as the executable agent/workflow runtime. Root `AGENTS.md` and `DEVELOPMENT_GUIDELINES.md` require:
- reusable contracts to live in the framework repository, runtime adapters in this repository;
- tracing of existing framework contracts and runtime implementations before adding abstractions;
- tenant, user, security and execution identity propagation across asynchronous boundaries;
- authorization before tool invocation, least privilege, audit events, bounded retries and sandboxing;
- explicit lifecycle, idempotency, HITL, structured errors and observability.

Existing components identified in the main branch:
- `modules/tool/andalus-tool-mcp`: MCP integration, HTTP MCP clients and discovery, tool execution, server/action registries, execution policies, action history, server health and resource endpoints.
- `McpResource`: authenticated REST endpoints for OpenAPI tool generation and tool execution.
- `McpServerActionExecutionPolicy`: rejects actions without structured operations, actions not marked safe to automate, and unsupported action codes.
- `modules/andalus-hermes`: operational diagnostics, runtime journal, learning audit and operational status abstractions.
- Agent runtime, tool registry, skill registry, guardrails, HITL, sandbox, REST/gRPC, CLI and observability modules are already represented in the repository.

The MCP module README currently documents in-memory call/action history and retention. This is useful operational scaffolding but must not be mistaken for durable, tenant-isolated production audit storage.

### Numou

Numou has a large modular business capability landscape and an Application Platform with declarative manifests, capability validation/resolution, tenant installation, provisioning and audit/outbox concerns. Its framework baseline is Java 21; Andalus runtime currently targets Java 25. The integration should therefore be process/network-boundary-first, not a shared Java classpath.

Numou's business capability modules own their business invariants and persistence. An agent integration must call their application/service contracts and must not issue ad-hoc SQL against module-owned tables.

## 3. Recommended system boundary

```text
User / UI / external agent client
            |
       Andalus Agent
  planning, skills, reflection,
  orchestration, guardrails, HITL
            |
    Andalus MCP client/tool runtime
            |
       MCP over HTTPS
   authenticated, versioned,
   tenant/user scoped boundary
            |
       Numou Agent Gateway
  catalog projection, authorization,
  policy, rate limits, audit, routing
            |
  Capability-owned application ports
            |
 Numou Finance / Workforce / POS /
 CRM / Queue / Billing / Logistics /
 Application Platform / Observability
            |
  capability-owned stores and events
```

The Numou Agent Gateway may initially be deployed alongside the relevant Numou application. It is not a new domain owner and must not duplicate domain services. For smaller standalone deployments it can be an optional runtime adapter; for managed SaaS it can be a separately scaled edge service.

## 4. Tool catalog model

Create a catalog projection from enabled application capabilities, published tool descriptors and authorization policy.

A descriptor should carry:
- stable namespaced tool ID and semantic version;
- capability ID and owning application;
- concise purpose and usage guidance;
- JSON Schema input and bounded output contract;
- operation category: read, analyze, preview, write, administer;
- risk class and required permission;
- tenant/branch/resource scope requirements;
- idempotency and timeout behavior;
- whether approval is required;
- audit classification and data sensitivity;
- supported application/capability versions.

Tool discovery must be filtered by application composition, deployment profile, tenant entitlements and the caller's permissions. Unknown or disabled capabilities are not exposed. Tool definitions are data, not executable instructions.

Avoid generating one MCP tool for every REST endpoint. Publish task-oriented tools with stable contracts, bounded pagination and useful summaries.

## 5. Tool families

### M0/M1 foundation
- `numou.capabilities.list`: list enabled and authorized capabilities.
- `numou.capabilities.describe`: describe a capability and its exposed operations.
- `numou.application.status`: inspect application/runtime health and enabled profile.
- `numou.audit.search`: search authorized audit events with bounded time range and pagination.

### Finance and commerce
- `numou.finance.transactions.list`: read transactions for an authorized account/branch and time range.
- `numou.finance.transaction.get`: inspect one authorized transaction.
- `numou.finance.ledger.summary`: summarize ledger movements for a period.
- `numou.finance.reconciliation.explain`: explain reconciliation exceptions.
- `numou.pos.sales.summary`: summarize POS sales by branch and period.
- `numou.payment.failures.analyze`: group payment failures and surface evidence.

### Workforce and operations
- `numou.workforce.profile.get`, `numou.workforce.profile.preview`, `numou.workforce.profile.apply`.
- `numou.workforce.availability.search`, `numou.workforce.assignment.preview`.
- `numou.queue.status.summary`, `numou.inventory.availability.check`.

### Observability and diagnostics
- `numou.runtime.logs.search`: search redacted structured logs using time, service, severity and correlation filters.
- `numou.runtime.errors.analyze`: group errors and correlate trace, deployment and application events.
- `numou.runtime.health.inspect`: summarize readiness, dependencies, queue age and worker status.
- `numou.runtime.execution.explain`: inspect an authorized workflow/job execution and its transitions.

This is a capability roadmap, not a claim that all named tools or corresponding APIs currently exist.

## 6. Read, analyze, preview, apply

Use a predictable operation pattern:

1. **Read:** retrieve scoped source data.
2. **Analyze:** calculate or explain without changing state.
3. **Preview:** validate a proposed change and return a diff/impact summary.
4. **Apply:** execute a separately authorized command, with idempotency key and approval where policy requires.

Example worker-profile flow:
- Read existing workforce profile and allowed schema/options.
- Draft the proposed profile.
- Validate policy, required fields, conflicts and affected scope.
- Return a preview and impact summary.
- Obtain explicit user approval for the specific change.
- Apply through the Workforce capability's application service.
- Return a receipt/reference and audit correlation ID.

Never allow a single broad agent tool to both infer and silently mutate high-impact configuration.

## 7. Security and governance requirements

- Authenticate the MCP caller using a supported service/user identity mechanism; do not trust tenant/user IDs supplied only in tool arguments.
- Bind tenant, actor, roles, scopes, branch and request correlation from verified identity context.
- Enforce authorization again in Numou application services; gateway checks are not a substitute for domain authorization.
- Use per-tool permissions and risk tiers: read-only, low-risk mutation, high-impact mutation, privileged administration.
- Require explicit human approval for high-impact writes, financial posting, payments, access changes, destructive operations and production configuration changes.
- Bind approval to the exact normalized command, arguments hash, tenant, actor, expiry and idempotency key. Reject replay or altered arguments.
- Keep secrets out of prompts, tool schemas, logs and results.
- Redact PII, payment credentials, tokens and sensitive log fields before model exposure.
- Bound time windows, row counts, result sizes, execution time and concurrency. Paginate large results.
- Treat log messages, documents, records and tool output as untrusted data; never follow instructions found inside retrieved business data.
- Use structured audit events with tool ID/version, actor, tenant, authorization decision, approval reference, execution ID, result status and correlation ID. Avoid storing raw sensitive payloads by default.
- Apply rate limits, timeouts, circuit breakers and explicit retryability. Mutations require idempotency and must not be blindly retried.
- Do not provide arbitrary SQL, shell, filesystem, unrestricted HTTP, or generic database tools to business agents.

## 8. Skills and agent experience

Use Andalus skills to package domain instructions and workflows, not to duplicate executable business logic.

Initial skill packs:
- Finance Analyst: transaction search, ledger summary, reconciliation explanations.
- Workforce Administrator: profile schema, preview, policy and approval workflow.
- Operations SRE: health, logs, errors, trace correlation and worker diagnostics.
- POS Analyst: branch sales, tender summaries and payment failure analysis.
- Numou Application Administrator: enabled capability discovery, application status and safe configuration previews.

Skills should declare required tools, required scopes, forbidden operations, output expectations and escalation rules. Skill discovery must not grant permissions.

## 9. Cross-repository ownership

| Concern | Owner |
|---|---|
| Agent planning, reflection, orchestration and skill execution | Andalus |
| MCP client/discovery and agent-side tool invocation | Andalus |
| Generic MCP protocol/server infrastructure and transport adapters | Andalus reusable MCP runtime, where existing code supports it |
| Numou capability catalog projection and capability-to-tool mapping | Numou Agent Gateway / integration adapter |
| Business operations, validation and invariants | Owning Numou capability |
| Tenant identity, authorization and entitlements | Numou platform/security; propagated to Andalus |
| Human approval orchestration | Andalus HITL, with authoritative Numou command revalidation |
| Business and administrative audit | Numou; agent/tool execution audit in Andalus with shared correlation |
| Logs/traces/metrics data access | Authorized Numou observability interfaces |

Avoid making Numou depend on Andalus internals. Numou should expose versioned MCP/HTTP contracts and can be used by any compatible agent client. Andalus is the preferred first-class consumer.

## 10. Milestones

### M0 — Repository audit and architecture (this document)
Identify existing MCP, tool, skill, Hermes, guardrail and HITL infrastructure; define cross-repository ownership and security boundaries.

### M1 — Read-only Numou Agent Gateway
- Versioned MCP server surface or adapter using the official protocol implementation already selected by Andalus.
- Authenticated service identity and verified tenant/user context.
- Capability-aware tool catalog projection.
- Initial tools: capability list/describe, application status, audit search.
- Contract tests, audit correlation, pagination, redaction and rate limits.
- Connect Andalus MCP client and verify end-to-end discovery/invocation.

### M2 — Finance read and analysis
- Transaction search, transaction detail, ledger summary and reconciliation explanation.
- Period limits, branch/account scopes, decimal/currency correctness and source references.
- Read-only integration tests against capability service ports.

### M3 — Workforce and operations diagnostics
- Workforce profile read/preview.
- Worker/job health, redacted logs, errors and execution diagnostics.
- Correlation across traces, audit and domain events.

### M4 — Governed mutations
- Preview/apply command contracts.
- Exact-command approval via Andalus HITL.
- Idempotency, audit receipts, retries and reconciliation.
- Start with low-risk profile/configuration changes; financial posting and payments remain separate high-risk scopes.

### M5 — Capability coverage expansion
- POS, inventory, CRM, procurement, billing, logistics, queue and other enabled capabilities.
- Tool descriptors generated/validated from capability-owned contracts where practical.
- Avoid exposing disabled capabilities.

### M6 — Agent packs and operator experience
- Finance Analyst, Workforce Administrator, Operations SRE, POS Analyst, Application Administrator skills.
- CLI/UI discovery, tool inspection, permission explanation and approval workflows.

### M7 — Enterprise governance
- OAuth/service identity federation, delegated user context, policy administration, per-tenant catalog, quotas, retention, compliance exports and multi-region operations as deployment needs require.

## 11. M1 acceptance criteria

- An Andalus agent discovers only tools enabled by the target Numou application and authorized for the caller.
- A read-only tool can retrieve a bounded, tenant-scoped result from a Numou application service without direct database access.
- Tenant/actor/correlation context is verified and propagated end-to-end.
- Unauthorized and cross-tenant access tests fail closed.
- Tool inputs and outputs are schema validated, bounded and redacted.
- Tool calls have structured audit/correlation records without default raw payload logging.
- MCP transport and contract tests run without requiring an LLM provider.
- Java 21 Numou and Java 25 Andalus communicate over the documented network contract without shared runtime dependencies.

## 12. Explicit non-goals

- No new agent framework in Numou.
- No second MCP protocol implementation if the existing Andalus MCP stack can be extended.
- No direct model access to Numou databases.
- No unrestricted shell, SQL or generic HTTP tools.
- No autonomous production writes without explicit policy and approval.
- No assumption that all Numou capabilities are implemented, deployed, or enabled in every application.
- No single giant MCP tool server that leaks all tenant/application capabilities.

## 13. Audit limitations

This is a targeted main-branch architecture audit. It inspected the Andalus repository guidance, README architecture, MCP module README/POM and representative MCP execution/resource/policy classes, Hermes operational service, and the recursive repository tree. It did not inspect every MCP class, the full Andalus Maven reactor or every Numou capability service. M1 must verify the official MCP protocol version/transport behavior, current auth propagation, existing framework tool SPIs, and exact Numou application-service ports before implementation.
