package tech.kayys.andalus.operator.runtime.plugin;

import tech.kayys.andalus.operator.runtime.DenyAllOperatorAuthorization;
import tech.kayys.andalus.spi.operator.OperatorAuthorization;
import tech.kayys.andalus.spi.operator.OperatorAuthorizationException;
import tech.kayys.andalus.spi.operator.OperatorContext;
import tech.kayys.andalus.spi.operator.OperatorResult;
import tech.kayys.andalus.spi.operator.OperatorService;
import tech.kayys.andalus.spi.operator.plugin.PluginOperatorPermissions;
import tech.kayys.andalus.spi.operator.plugin.PluginOperatorService;
import tech.kayys.andalus.spi.operator.plugin.PluginSummary;
import tech.kayys.andalus.spi.plugin.Plugin;
import tech.kayys.andalus.spi.plugin.PluginManager;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class DefaultPluginOperatorService implements PluginOperatorService, OperatorService {

    public static final String ID = "operator.service.plugin";

    private final PluginManager pluginManager;
    private final OperatorAuthorization authorization;

    public DefaultPluginOperatorService(PluginManager pluginManager) {
        this(pluginManager, new DenyAllOperatorAuthorization());
    }

    public DefaultPluginOperatorService(PluginManager pluginManager, OperatorAuthorization authorization) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager must not be null");
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "Plugin Operator Service";
    }

    @Override
    public String description() {
        return "Operator control plane for managing and inspecting Andalus plugins";
    }

    @Override
    public OperatorResult<List<PluginSummary>> list(OperatorContext context) {
        try {
            authorization.requirePlatform(context, PluginOperatorPermissions.READ);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        try {
            List<Plugin> plugins = pluginManager.getPlugins();
            List<PluginSummary> summaries = plugins.stream()
                    .map(PluginSummaryMapper::map)
                    .toList();
            return OperatorResult.success(summaries);
        } catch (Exception e) {
            return OperatorResult.failure("PLUGIN_LIST_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<PluginSummary> inspect(OperatorContext context, String pluginId) {
        try {
            authorization.requirePlatform(context, PluginOperatorPermissions.READ);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (pluginId == null || pluginId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "pluginId must not be blank");
        }
        try {
            Optional<Plugin> plugin = pluginManager.getPlugin(pluginId);
            if (plugin.isEmpty()) {
                return OperatorResult.failure("PLUGIN_NOT_FOUND", "No plugin found with ID: " + pluginId);
            }
            return OperatorResult.success(PluginSummaryMapper.map(plugin.get()));
        } catch (Exception e) {
            return OperatorResult.failure("PLUGIN_INSPECT_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<Void> enable(OperatorContext context, String pluginId) {
        try {
            authorization.requirePlatform(context, PluginOperatorPermissions.ENABLE);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (pluginId == null || pluginId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "pluginId must not be blank");
        }
        try {
            pluginManager.enablePlugin(pluginId);
            return OperatorResult.success(null);
        } catch (Exception e) {
            return OperatorResult.failure("PLUGIN_ENABLE_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<Void> disable(OperatorContext context, String pluginId) {
        try {
            authorization.requirePlatform(context, PluginOperatorPermissions.DISABLE);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (pluginId == null || pluginId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "pluginId must not be blank");
        }
        try {
            pluginManager.disablePlugin(pluginId);
            return OperatorResult.success(null);
        } catch (Exception e) {
            return OperatorResult.failure("PLUGIN_DISABLE_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<Void> unload(OperatorContext context, String pluginId) {
        try {
            authorization.requirePlatform(context, PluginOperatorPermissions.UNLOAD);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (pluginId == null || pluginId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "pluginId must not be blank");
        }
        try {
            pluginManager.unloadPlugin(pluginId);
            return OperatorResult.success(null);
        } catch (Exception e) {
            return OperatorResult.failure("PLUGIN_UNLOAD_FAILED", e.getMessage());
        }
    }
}
