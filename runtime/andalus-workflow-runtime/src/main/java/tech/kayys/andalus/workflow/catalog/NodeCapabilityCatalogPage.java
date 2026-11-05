package tech.kayys.andalus.workflow.catalog;

import tech.kayys.andalus.schema.workflow.catalog.NodeTypeDescriptor;

import java.util.List;

/** A page over one caller-authorized catalog snapshot. */
public record NodeCapabilityCatalogPage(
        String catalogVersion,
        String generatedAt,
        int offset,
        int limit,
        long total,
        List<NodeTypeDescriptor> nodeTypes) {

    public NodeCapabilityCatalogPage {
        nodeTypes = nodeTypes == null ? List.of() : List.copyOf(nodeTypes);
    }
}
