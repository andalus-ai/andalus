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
