# Andalus Runtime Core

Core execution and coordination primitives for Andalus agents.

## Capability-based delegation

The coordination discovery package exposes a runtime SPI and planning path:

- `AgentCapabilitySource` lets a runtime module advertise candidates for a
  specific coordination request and context.
- `AgentCapabilityDirectory` aggregates CDI-discovered sources, rejects
  duplicate agent ids, and returns stable id ordering.
- `AgentDelegationPlanner` combines discovery with
  `CapabilityBasedAgentSelector` to produce ranked delegation candidates.

Candidate metadata uses stable namespaced capability identifiers such as
`research.web-search`. Required capabilities are hard filters; preferred
capabilities improve ranking; availability and optional cost ceilings are
respected. Ties are resolved by configured priority, cost, then agent id.

This path plans a target only. Capability advertisements do not grant
authorization. Coordinators must still enforce tenant visibility, policy,
execution budgets, and dispatch rules before sending work to a candidate.


## Coordination plan validation

`CoordinationPlanValidator` checks a plan before compilation or dispatch:

- participating agents are declared, unique, and within `CoordinationPolicy.maxAgents`;
- every step has a unique id and targets a declared agent;
- dependencies reference existing steps without duplicates or self-references;
- the dependency graph is acyclic.

Validation is structural only. It does not grant permissions, establish tenant visibility,
reserve execution budget, or dispatch work; those checks remain mandatory at the execution boundary.

## Tool decisions in the ReAct loop (`ToolPolicyBridge`)

A tool call of the loop goes through `AgentToolExecutor`. What each decision means:

| Decision | Effect |
|---|---|
| `ToolCompleted` | the executor already ran the tool; its result is used |
| `ExecuteTool` | the executor defers: the tool is run directly, once |
| `Fail` | the call fails with the executor's error; the tool is not run |
| `WaitForApproval` | the tool is **not run**; the call fails with a `ToolExecutionDeniedException` naming the tool (never its arguments) |
| anything else | not run; fails the same way |

Behaviour change: before this bridge existed as its own class, `WaitForApproval` (and any unlisted decision) fell through to the direct execution path, so a policy
that required approval did not stop the tool. A durable park-and-resume of the call is not part of this path; until it is, approval-gated tools fail closed here.
