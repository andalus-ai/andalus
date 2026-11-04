package tech.kayys.andalus.workflow.catalog;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.schema.workflow.catalog.NodePortDescriptor;
import tech.kayys.andalus.schema.workflow.catalog.NodePortDirection;
import tech.kayys.andalus.schema.workflow.catalog.NodeTypeDescriptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeCapabilityCatalogServiceTest {

    @Test
    void discoversRegisteredProvidersAndAppliesSearchAndCategoryFilters() {
        NodeCapabilityCatalogService service = new NodeCapabilityCatalogService();
        service.register(provider("core", descriptor("agent.summarize", "Summarize", "agent")));

        var catalog = service.discover(
                new NodeCapabilityCatalogQuery().setText("summar").setCategory("agent"),
                CatalogAccessContext.publicAccess("1.2.0"));

        assertThat(catalog.getNodeTypes()).hasSize(1);
        assertThat(catalog.getNodeTypes().get(0).getTypeId()).isEqualTo("agent.summarize");
        assertThat(catalog.getCatalogVersion()).isNotBlank();
        assertThat(catalog.getGeneratedAt()).isNotBlank();
    }

    @Test
    void hidesDescriptorsWhenRequiredPermissionsOrCapabilitiesAreMissing() {
        NodeTypeDescriptor descriptor = descriptor("tool.http", "HTTP Request", "tool");
        descriptor.setRequiredPermissions(List.of("network.http.invoke"));
        descriptor.setRequiredCapabilities(List.of("network.http"));

        NodeCapabilityCatalogService service = new NodeCapabilityCatalogService();
        service.register(provider("http", descriptor));

        assertThat(service.discover(null, CatalogAccessContext.publicAccess("1.2.0"))
                .getNodeTypes()).isEmpty();

        CatalogAccessContext authorized = new CatalogAccessContext(
                "tenant-a", "user-a",
                Set.of("network.http.invoke"),
                Set.of("network.http"),
                "1.2.0");
        assertThat(service.discover(null, authorized).getNodeTypes())
                .extracting(NodeTypeDescriptor::getTypeId)
                .containsExactly("tool.http");
    }

    @Test
    void filtersByProviderAndFrameworkCompatibilityRange() {
        NodeTypeDescriptor descriptor = descriptor("agent.reason", "Reason", "agent");
        descriptor.setMinimumFrameworkVersion("1.2.0");
        descriptor.setMaximumFrameworkVersion("2.0.0");

        NodeCapabilityCatalogService service = new NodeCapabilityCatalogService();
        service.register(provider("reasoning", descriptor));

        assertThat(service.discover(
                new NodeCapabilityCatalogQuery().setProviderId("reasoning").setFrameworkVersion("1.3.0"),
                CatalogAccessContext.publicAccess("1.3.0")).getNodeTypes()).hasSize(1);

        assertThat(service.discover(
                new NodeCapabilityCatalogQuery().setFrameworkVersion("1.1.9"),
                CatalogAccessContext.publicAccess("1.1.9")).getNodeTypes()).isEmpty();
    }

    @Test
    void rejectsDuplicateProviderIdsAndInvalidDescriptors() {
        NodeCapabilityCatalogService service = new NodeCapabilityCatalogService();
        service.register(provider("core", descriptor("agent.first", "First", "agent")));

        assertThatThrownBy(() -> service.register(provider("core", descriptor("agent.second", "Second", "agent"))))
                .isInstanceOf(IllegalStateException.class);

        NodeCapabilityCatalogService invalid = new NodeCapabilityCatalogService();
        NodeTypeDescriptor broken = descriptor("agent.broken", "Broken", "agent");
        broken.setProviderId("different-provider");
        invalid.register(provider("core", broken));

        assertThatThrownBy(() -> invalid.discover(null, CatalogAccessContext.publicAccess("1.0.0")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declares provider");
    }

    @Test
    void unregistersProviderAndRequiresTrustedAccessContext() {
        NodeCapabilityCatalogService service = new NodeCapabilityCatalogService();
        service.register(provider("core", descriptor("agent.first", "First", "agent")));

        assertThat(service.unregister("core")).isTrue();
        assertThat(service.unregister("core")).isFalse();
        assertThat(service.discover(null, CatalogAccessContext.publicAccess("1.0.0"))
                .getNodeTypes()).isEmpty();

        assertThatThrownBy(() -> service.discover(null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("access context");
    }

    private NodeCapabilityProvider provider(String id, NodeTypeDescriptor descriptor) {
        return new NodeCapabilityProvider() {
            @Override
            public String getProviderId() {
                return id;
            }

            @Override
            public List<NodeTypeDescriptor> getNodeTypes() {
                return List.of(descriptor);
            }
        };
    }

    private NodeTypeDescriptor descriptor(String typeId, String displayName, String category) {
        NodeTypeDescriptor descriptor = new NodeTypeDescriptor();
        descriptor.setTypeId(typeId);
        descriptor.setVersion("1.0.0");
        descriptor.setDisplayName(displayName);
        descriptor.setCategory(category);
        descriptor.setProviderId("core");
        descriptor.setInputs(List.of(
                new NodePortDescriptor("input", "Input", NodePortDirection.INPUT, "json")));
        descriptor.setOutputs(List.of(
                new NodePortDescriptor("output", "Output", NodePortDirection.OUTPUT, "json")));
        return descriptor;
    }
}
