package tech.kayys.andalus.coordination;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CoordinationPlanValidatorTest {

    private final CoordinationPlanValidator validator = new CoordinationPlanValidator();
    private final CoordinationPolicy policy = CoordinationPolicy.defaults();

    @Test
    void acceptsValidDependencyGraph() {
        var plan = plan(
                List.of("researcher", "writer"),
                List.of(
                        step("research", "researcher", List.of()),
                        step("write", "writer", List.of("research"))));

        assertDoesNotThrow(() -> validator.validate(plan, policy));
    }

    @Test
    void rejectsPlansExceedingAgentLimit() {
        var plan = plan(
                List.of("a", "b"),
                List.of(step("one", "a", List.of()), step("two", "b", List.of())));

        var restrictive = new CoordinationPolicy(1, 1, 1,
                policy.timeout(), false, false);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan, restrictive));
    }

    @Test
    void acceptsDependencyPathAtMaximumDepth() {
        var plan = plan(List.of("a"), List.of(
                step("one", "a", List.of()),
                step("two", "a", List.of("one")),
                step("three", "a", List.of("two"))));
        var depthLimited = new CoordinationPolicy(3, 3, 10, policy.timeout(), false, true);

        assertDoesNotThrow(() -> validator.validate(plan, depthLimited));
    }

    @Test
    void rejectsDependencyPathExceedingMaximumDepth() {
        var plan = plan(List.of("a"), List.of(
                step("one", "a", List.of()),
                step("two", "a", List.of("one")),
                step("three", "a", List.of("two"))));
        var depthLimited = new CoordinationPolicy(3, 2, 10, policy.timeout(), false, true);

        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan, depthLimited));
    }

    @Test
    void measuresDepthUsingLongestDependencyPath() {
        var plan = plan(List.of("a"), List.of(
                step("root", "a", List.of()),
                step("short", "a", List.of("root")),
                step("long", "a", List.of("short")),
                step("join", "a", List.of("root", "long"))));
        var depthLimited = new CoordinationPolicy(3, 3, 10, policy.timeout(), false, true);

        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan, depthLimited));
    }

    @Test
    void rejectsUndeclaredStepAgent() {
        var plan = plan(List.of("known"), List.of(step("work", "unknown", List.of())));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan, policy));
    }

    @Test
    void rejectsUnknownDependency() {
        var plan = plan(List.of("a"), List.of(step("work", "a", List.of("missing"))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan, policy));
    }

    @Test
    void rejectsDependencyCycles() {
        var plan = plan(
                List.of("a"),
                List.of(step("first", "a", List.of("second")),
                        step("second", "a", List.of("first"))));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan, policy));
    }

    @Test
    void rejectsDuplicateStepIds() {
        var plan = plan(
                List.of("a"),
                List.of(step("same", "a", List.of()), step("same", "a", List.of())));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan, policy));
    }

    private static CoordinationPlan plan(
            List<String> agents, List<CoordinationPlan.CoordinationStep> steps) {
        return new CoordinationPlan("plan", "test", CoordinationPattern.CENTRALIZED,
                agents, steps, Map.of());
    }

    private static CoordinationPlan.CoordinationStep step(
            String id, String agent, List<String> dependencies) {
        return CoordinationPlan.CoordinationStep.of(id, agent, "execute", dependencies, Map.of());
    }
}
