package tech.kayys.andalus.operator.runtime.tool;

import tech.kayys.andalus.operator.runtime.DenyAllOperatorAuthorization;
import tech.kayys.andalus.spi.capability.Capability;
import tech.kayys.andalus.spi.capability.CapabilityRegistry;
import tech.kayys.andalus.spi.operator.OperatorAuthorization;
import tech.kayys.andalus.spi.operator.OperatorAuthorizationException;
import tech.kayys.andalus.spi.operator.OperatorContext;
import tech.kayys.andalus.spi.operator.OperatorResult;
import tech.kayys.andalus.spi.operator.OperatorService;
import tech.kayys.andalus.spi.operator.tool.CapabilitySummary;
import tech.kayys.andalus.spi.operator.tool.ToolCapabilityOperatorPermissions;
import tech.kayys.andalus.spi.operator.tool.ToolCapabilityOperatorService;
import tech.kayys.andalus.spi.operator.tool.ToolSummary;
import tech.kayys.andalus.tool.Tool;
import tech.kayys.andalus.tool.ToolRegistry;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class DefaultToolCapabilityOperatorService implements ToolCapabilityOperatorService, OperatorService {

    public static final String ID = "operator.service.tool_capability";

    private final CapabilityRegistry capabilityRegistry;
    private final ToolRegistry toolRegistry;
    private final OperatorAuthorization authorization;

    public DefaultToolCapabilityOperatorService(
            CapabilityRegistry capabilityRegistry,
            ToolRegistry toolRegistry) {
        this(capabilityRegistry, toolRegistry, new DenyAllOperatorAuthorization());
    }

    public DefaultToolCapabilityOperatorService(
            CapabilityRegistry capabilityRegistry,
            ToolRegistry toolRegistry,
            OperatorAuthorization authorization) {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "Tool & Capability Operator Service";
    }

    @Override
    public String description() {
        return "Operator control plane for inspecting capabilities and tools";
    }

    @Override
    public OperatorResult<List<CapabilitySummary>> listCapabilities(OperatorContext context) {
        try {
            authorization.require(context, ToolCapabilityOperatorPermissions.CAPABILITIES_READ);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        try {
            List<CapabilitySummary> result = capabilityRegistry.findAll().stream()
                    .map(CapabilitySummaryMapper::map)
                    .toList();
            return OperatorResult.success(result);
        } catch (Exception e) {
            return OperatorResult.failure("CAPABILITY_LIST_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<CapabilitySummary> inspectCapability(OperatorContext context, String capabilityId) {
        try {
            authorization.require(context, ToolCapabilityOperatorPermissions.CAPABILITY_INSPECT);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (capabilityId == null || capabilityId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "capabilityId must not be blank");
        }

        try {
            Optional<Capability> cap = capabilityRegistry.find(capabilityId);
            if (cap.isEmpty()) {
                return OperatorResult.failure("CAPABILITY_NOT_FOUND", "No capability found with ID: " + capabilityId);
            }
            return OperatorResult.success(CapabilitySummaryMapper.map(cap.get()));
        } catch (Exception e) {
            return OperatorResult.failure("CAPABILITY_INSPECT_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<List<CapabilitySummary>> findCapabilitiesByType(OperatorContext context, String type) {
        try {
            authorization.require(context, ToolCapabilityOperatorPermissions.CAPABILITIES_READ);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (type == null || type.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "type must not be blank");
        }

        try {
            String target = type.trim();
            List<CapabilitySummary> result = capabilityRegistry.findAll().stream()
                    .filter(c -> c.type().category().equalsIgnoreCase(target)
                            || c.type().name().equalsIgnoreCase(target)
                            || c.type().qualifiedName().equalsIgnoreCase(target))
                    .map(CapabilitySummaryMapper::map)
                    .toList();
            return OperatorResult.success(result);
        } catch (Exception e) {
            return OperatorResult.failure("CAPABILITY_QUERY_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<List<CapabilitySummary>> findCapabilitiesByTag(OperatorContext context, String tag) {
        try {
            authorization.require(context, ToolCapabilityOperatorPermissions.CAPABILITIES_READ);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (tag == null || tag.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "tag must not be blank");
        }

        try {
            String targetTag = tag.trim();
            List<CapabilitySummary> result = capabilityRegistry.findAll().stream()
                    .filter(c -> {
                        var desc = c.descriptor();
                        if (desc != null && desc.attributes().containsKey("tags")) {
                            Object val = desc.attributes().get("tags");
                            if (val instanceof Collection<?> col) {
                                return col.stream().anyMatch(t -> targetTag.equalsIgnoreCase(String.valueOf(t)));
                            }
                        }
                        return false;
                    })
                    .map(CapabilitySummaryMapper::map)
                    .toList();
            return OperatorResult.success(result);
        } catch (Exception e) {
            return OperatorResult.failure("CAPABILITY_QUERY_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<List<ToolSummary>> listTools(OperatorContext context) {
        try {
            authorization.require(context, ToolCapabilityOperatorPermissions.TOOLS_READ);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        try {
            List<ToolSummary> result = toolRegistry.listTools().stream()
                    .map(ToolSummaryMapper::map)
                    .toList();
            return OperatorResult.success(result);
        } catch (Exception e) {
            return OperatorResult.failure("TOOL_LIST_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<ToolSummary> inspectTool(OperatorContext context, String toolName) {
        try {
            authorization.require(context, ToolCapabilityOperatorPermissions.TOOL_INSPECT);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (toolName == null || toolName.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "toolName must not be blank");
        }

        try {
            Optional<Tool> tool = toolRegistry.findByName(toolName.trim());
            if (tool.isEmpty()) {
                return OperatorResult.failure("TOOL_NOT_FOUND", "No tool found with name: " + toolName);
            }
            return OperatorResult.success(ToolSummaryMapper.map(tool.get()));
        } catch (Exception e) {
            return OperatorResult.failure("TOOL_INSPECT_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<List<ToolSummary>> findToolsByCapability(OperatorContext context, String capabilityId) {
        try {
            authorization.require(context, ToolCapabilityOperatorPermissions.TOOLS_READ);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (capabilityId == null || capabilityId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "capabilityId must not be blank");
        }

        try {
            String cap = capabilityId.trim();
            List<ToolSummary> result = toolRegistry.listTools().stream()
                    .filter(t -> (t.descriptor() != null && t.descriptor().capabilityKeys() != null && t.descriptor().capabilityKeys().contains(cap))
                            || (t.toolCapabilities() != null && t.toolCapabilities().stream().anyMatch(c -> c.id().equalsIgnoreCase(cap))))
                    .map(ToolSummaryMapper::map)
                    .toList();
            return OperatorResult.success(result);
        } catch (Exception e) {
            return OperatorResult.failure("TOOL_QUERY_FAILED", e.getMessage());
        }
    }
}
