# Andalus Runtime Core

Core execution and coordination primitives for Andalus agents.

## Capability-based delegation selection

The coordination discovery package provides a deterministic, policy-neutral
selector for choosing candidate agents:

- required capabilities are hard filters;
- preferred capabilities improve ranking;
- unavailable candidates are excluded;
- an optional cost-factor ceiling can constrain candidates;
- ties are resolved by configured priority, cost, then stable agent id.

Capabilities use stable namespaced identifiers such as
`research.web-search`. The selector only ranks descriptive candidate metadata:
it does not authorize access, reserve execution budget, check tenant policy, or
dispatch work. Those checks remain responsibilities of the runtime caller and
its governance/execution layers.

See `CapabilityBasedAgentSelectorTest` for ordering and filtering examples.
