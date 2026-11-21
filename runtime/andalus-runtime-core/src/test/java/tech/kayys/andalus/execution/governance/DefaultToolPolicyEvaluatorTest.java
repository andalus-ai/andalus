package tech.kayys.andalus.execution.governance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefaultToolPolicyEvaluatorTest {

    @Test
    void deniesWhenNoPolicyBeansAreAvailable() {
        DefaultToolPolicyEvaluator evaluator = new DefaultToolPolicyEvaluator();

        PolicyEvaluationResult result =
                evaluator.evaluateDetailed((PolicyEvaluationContext) null);

        assertTrue(result.isDenied());
        assertEquals("runtime-policy-set-required", result.decision().policyId());
        assertTrue(result.evaluations().isEmpty());
    }
}
