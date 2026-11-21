package tech.kayys.andalus.execution.governance;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import tech.kayys.andalus.tool.ToolInvocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Default CDI-managed {@link ToolPolicyEvaluator}.
 *
 * <p>Discovers all {@link ToolPolicy} beans via CDI, sorts them by
 * {@link ToolPolicy#priority()}, and evaluates them in order.
 * Short-circuits on the first {@link PolicyDecision.Deny}.
 * {@link PolicyDecision.RequireApproval} is latched (first one wins if multiple policies fire it).</p>
 */
@ApplicationScoped
public class DefaultToolPolicyEvaluator implements ToolPolicyEvaluator {

    private static final Logger LOG = Logger.getLogger(DefaultToolPolicyEvaluator.class.getName());

    @Inject
    Instance<ToolPolicy> policyInstances;

    @Override
    public PolicyDecision evaluate(ToolInvocation invocation, ToolPermissionContext context) {
        return evaluateDetailed(invocation, context).decision();
    }

    @Override
    public PolicyDecision evaluate(PolicyEvaluationContext context) {
        return evaluateDetailed(context).decision();
    }

    @Override
    public PolicyEvaluationResult evaluateDetailed(ToolInvocation invocation, ToolPermissionContext context) {
        return evaluateDetailed(PolicyEvaluationContexts.create(context, invocation));
    }

    public PolicyEvaluationResult evaluateDetailed(PolicyEvaluationContext context) {
        List<ToolPolicy> sorted = policies();
        if (sorted.isEmpty()) {
            PolicyDecision deny = PolicyDecision.deny(
                    "No tool policies are registered; refusing to execute tools without an explicit policy set.",
                    "runtime-policy-set-required");
            return new PolicyEvaluationResult(deny, List.of());
        }
        PolicyDecision aggregate = PolicyDecision.allow();
        List<PolicyEvaluation> evaluations = new ArrayList<>(sorted.size());

        for (ToolPolicy policy : sorted) {
            PolicyDecision decision;
            try {
                decision = policy.evaluate(context);
            } catch (Exception e) {
                LOG.warning(() -> "Policy [" + policy.id() + "] threw exception, treating as DENY: " + e.getMessage());
                decision = PolicyDecision.deny("Policy evaluation error in " + policy.id(), policy.id());
            }

            evaluations.add(new PolicyEvaluation(policy.id(), policy.priority(), decision));

            final PolicyDecision finalDecision = decision;
            if (decision instanceof PolicyDecision.Deny || decision.isDenied()) {
                LOG.fine(() -> "DENY from policy [" + policy.id() + "]: " + finalDecision.message());
                return new PolicyEvaluationResult(decision, evaluations);
            }

            aggregate = PolicyDecisionCombiner.combine(aggregate, decision);
        }

        return new PolicyEvaluationResult(aggregate, evaluations);
    }

    @Override
    public List<ToolPolicy> policies() {
        List<ToolPolicy> result = new ArrayList<>();
        if (policyInstances != null) {
            policyInstances.forEach(result::add);
        }
        result.sort(Comparator.comparingInt(ToolPolicy::priority));
        return List.copyOf(result);
    }
}
