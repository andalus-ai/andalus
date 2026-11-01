package tech.kayys.andalus.coordination;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates a coordination plan against structural invariants and runtime limits.
 *
 * <p>This validation is deliberately independent of authorization and dispatch.
 * A structurally valid plan is not, by itself, permission to execute it.</p>
 */
public class CoordinationPlanValidator {

    /**
     * Validates the plan before it is compiled or dispatched.
     *
     * <p>Maximum depth counts steps on the longest dependency path, including the root step.</p>
     *
     * @throws IllegalArgumentException when the plan is malformed or exceeds policy limits
     */
    public void validate(CoordinationPlan plan, CoordinationPolicy policy) {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        if (plan.agentIds().isEmpty()) {
            throw invalid("plan must declare at least one participating agent");
        }
        if (plan.agentIds().size() > policy.maxAgents()) {
            throw invalid("plan declares " + plan.agentIds().size()
                    + " agents, exceeding maxAgents=" + policy.maxAgents());
        }

        Set<String> agents = new HashSet<>();
        for (String agentId : plan.agentIds()) {
            requireNonBlank(agentId, "agent id");
            if (!agents.add(agentId)) {
                throw invalid("duplicate participating agent id: " + agentId);
            }
        }

        if (plan.steps().isEmpty()) {
            throw invalid("plan must contain at least one step");
        }

        Map<String, CoordinationPlan.CoordinationStep> stepsById = new HashMap<>();
        for (CoordinationPlan.CoordinationStep step : plan.steps()) {
            Objects.requireNonNull(step, "plan step must not be null");
            requireNonBlank(step.id(), "step id");
            requireNonBlank(step.agentId(), "step agent id");
            if (!agents.contains(step.agentId())) {
                throw invalid("step '" + step.id() + "' targets undeclared agent '" + step.agentId() + "'");
            }
            if (stepsById.putIfAbsent(step.id(), step) != null) {
                throw invalid("duplicate step id: " + step.id());
            }
        }

        Map<String, Integer> remainingDependencies = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (CoordinationPlan.CoordinationStep step : plan.steps()) {
            Set<String> uniqueDependencies = new HashSet<>();
            for (String dependency : step.dependsOn()) {
                requireNonBlank(dependency, "dependency step id");
                if (step.id().equals(dependency)) {
                    throw invalid("step '" + step.id() + "' cannot depend on itself");
                }
                if (!stepsById.containsKey(dependency)) {
                    throw invalid("step '" + step.id() + "' depends on unknown step '" + dependency + "'");
                }
                if (!uniqueDependencies.add(dependency)) {
                    throw invalid("step '" + step.id() + "' contains duplicate dependency '" + dependency + "'");
                }
                dependents.computeIfAbsent(dependency, ignored -> new HashSet<>()).add(step.id());
            }
            remainingDependencies.put(step.id(), uniqueDependencies.size());
        }

        // Depth is the number of steps on the longest dependency path (roots are depth 1).
        ArrayDeque<String> ready = new ArrayDeque<>();
        Map<String, Integer> depthByStep = new HashMap<>();
        remainingDependencies.forEach((id, count) -> {
            if (count == 0) {
                ready.addLast(id);
                depthByStep.put(id, 1);
            }
        });

        int visited = 0;
        while (!ready.isEmpty()) {
            String completed = ready.removeFirst();
            visited++;
            for (String dependent : dependents.getOrDefault(completed, Set.of())) {
                int depth = depthByStep.merge(dependent, depthByStep.get(completed) + 1, Math::max);
                if (depth > policy.maxDepth()) {
                    throw invalid("dependency path to step '" + dependent + "' exceeds maxDepth="
                            + policy.maxDepth());
                }
                int remaining = remainingDependencies.compute(dependent,
                        (ignored, count) -> count - 1);
                if (remaining == 0) {
                    ready.addLast(dependent);
                }
            }
        }

        if (visited != stepsById.size()) {
            throw invalid("plan dependencies contain a cycle");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid coordination plan: " + message);
    }
}
