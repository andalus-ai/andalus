package tech.kayys.andalus.agent.backend.aqli;

import tech.kayys.aqli.factory.AqliSdkFactory;
import tech.kayys.aqli.sdk.core.AqliSdk;
import tech.kayys.aqli.sdk.exception.SdkException;
import tech.kayys.andalus.agent.spi.BackendProvider;
import tech.kayys.andalus.agent.spi.InferenceBackend;
import tech.kayys.andalus.agent.spi.WorkflowBackend;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * ServiceLoader-based backend provider for Aqli SDK.
 *
 * <p>
 * This provider is automatically discovered when {@code backend-aqli}
 * is on the classpath. It creates AqliBackendAdapter instances
 * for inference and can optionally create workflow adapters.
 * </p>
 *
 * <h3>Auto-Registration:</h3>
 * <p>
 * Create {@code META-INF/services/tech.kayys.andalus.agent.spi.BackendProvider} with:
 * <pre>
 * tech.kayys.andalus.agent.backend.aqli.AqliBackendProvider
 * </pre>
 * </p>
 *
 * @author Andalus Team
 * @version 0.1.0
 * @since 2026-04-06
 */
public class AqliBackendProvider implements BackendProvider {

    private static final String SDK_CLASS = "tech.kayys.aqli.sdk.core.AqliSdk";
    private static final String SDK_FACTORY_CLASS = "tech.kayys.aqli.factory.AqliSdkFactory";
    private static final boolean SDK_AVAILABLE = classAvailable(SDK_CLASS) && classAvailable(SDK_FACTORY_CLASS);

    private static boolean classAvailable(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public String name() {
        return "aqli";
    }

    @Override
    public int priority() {
        return 100;  // High priority - Aqli is the primary backend
    }

    @Override
    public InferenceBackend createInferenceBackend(Map<String, Object> config) {
        if (!SDK_AVAILABLE) {
            throw new IllegalStateException(
                "Aqli SDK not available. Add aqli-sdk to classpath."
            );
        }

        try {
            AqliSdkFactory.Builder builder = AqliSdkFactory.builder();

            stringValue(config, "baseUrl").ifPresent(builder::baseUrl);
            stringValue(config, "apiKey").ifPresent(builder::apiKey);
            stringValue(config, "preferredProvider").ifPresent(builder::preferredProvider);
            durationValue(config, "timeout").ifPresent(builder::requestTimeout);
            intValue(config, "retryAttempts").ifPresent(builder::maxRetries);
            booleanValue(config, "enableMetrics").ifPresent(builder::enableMetrics);

            AqliSdk sdk = builder.build();
            return new AqliBackendAdapter(sdk);
        } catch (SdkException e) {
            throw new IllegalStateException("Failed to create Aqli SDK backend", e);
        }
    }

    private java.util.Optional<String> stringValue(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value instanceof String text && !text.isBlank()
                ? java.util.Optional.of(text)
                : java.util.Optional.empty();
    }

    private java.util.Optional<Duration> durationValue(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof Duration duration) {
            return java.util.Optional.of(duration);
        }
        if (value instanceof Number seconds) {
            return java.util.Optional.of(Duration.ofSeconds(seconds.longValue()));
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<Integer> intValue(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value instanceof Number number
                ? java.util.Optional.of(number.intValue())
                : java.util.Optional.empty();
    }

    private java.util.Optional<Boolean> booleanValue(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof Boolean bool) {
            return java.util.Optional.of(bool);
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                return java.util.Optional.of(Boolean.parseBoolean(text));
            }
        }
        return java.util.Optional.empty();
    }

    @Override
    public WorkflowBackend createWorkflowBackend(Map<String, Object> config) {
        // Aqli is inference-only, no workflow backend
        return null;
    }

    @Override
    public boolean isAvailable() {
        return SDK_AVAILABLE;
    }

    @Override
    public List<String> supportedBackends() {
        return List.of("inference");
    }
}
