package tech.kayys.andalus.workflow.catalog;

import tech.kayys.andalus.schema.workflow.catalog.NodeTypeDescriptor;

import java.util.Collection;

/**
 * Runtime extension point for a component that contributes trusted workflow
 * node type descriptors. Implementations must return metadata only; executable
 * plugin loading and invocation remain separate runtime concerns.
 */
public interface NodeCapabilityProvider {

    /** Stable provider identifier, unique within one runtime instance. */
    String getProviderId();

    /** Current descriptors contributed by this provider. */
    Collection<NodeTypeDescriptor> getNodeTypes();
}
