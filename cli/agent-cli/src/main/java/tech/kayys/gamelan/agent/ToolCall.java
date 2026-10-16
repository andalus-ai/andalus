package tech.kayys.gamelan.agent;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable representation of a tool call emitted by the LLM.
 *
 * <p>Uses Aqli's native tool calling format instead of XML parsing.
 *
 * @param name       the tool name (e.g. "read_file")
 * @param parameters key-value parameters for the tool
 * @param callId     unique identifier for this tool call (from Aqli SDK)
 */
public record ToolCall(String name, Map<String, Object> parameters, String callId) {

    public ToolCall(String name, Map<String, Object> parameters) {
        this(name, parameters, null);
    }

    /**
     * Get a string parameter, returning empty string if not found.
     */
    public String param(String key) {
        Object val = parameters.get(key);
        return val != null ? val.toString() : "";
    }

    /**
     * Get a string parameter with a default fallback.
     */
    public String param(String key, String defaultValue) {
        Object val = parameters.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    /**
     * Get a parameter as its actual object type.
     */
    public Object paramObject(String key) {
        return parameters.get(key);
    }

    /**
     * Get all parameters as an immutable map.
     */
    public Map<String, Object> allParams() {
        return Collections.unmodifiableMap(parameters);
    }

    /**
     * Create a ToolCall from a Aqli SDK InferenceResponse.ToolCall.
     * Note: InferenceResponse.ToolCall has no id field; callId is set to null.
     */
    public static ToolCall fromAqli(tech.kayys.aqli.spi.inference.InferenceResponse.ToolCall gc) {
        return new ToolCall(gc.name(), gc.arguments(), null);
    }

    @Override
    public String toString() {
        return "ToolCall{name='" + name + "', params=" + parameters + "}";
    }
}
