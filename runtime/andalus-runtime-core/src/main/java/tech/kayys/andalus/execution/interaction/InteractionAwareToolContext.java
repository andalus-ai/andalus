
package tech.kayys.andalus.execution.interaction;

import tech.kayys.andalus.tool.ToolContext;
import tech.kayys.andalus.tool.ToolContextKeys;
import tech.kayys.andalus.agent.kernel.interaction.InteractionContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InteractionAwareToolContext
        implements ToolContext {

    private final Map<String, Object> attributes;

    public InteractionAwareToolContext(
            Map<String, Object> attributes
    ) {
        Map<String, Object> copy =
                new LinkedHashMap<>();

        if (attributes != null) {
            copy.putAll(attributes);
        }

        this.attributes =
                Map.copyOf(copy);
    }

    public static InteractionAwareToolContext of(
            String runId,
            String executionId,
            InteractionContext interaction
    ) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        if (runId != null) {
            attributes.put(
                    ToolContextKeys.RUN_ID,
                    runId
            );
        }

        if (executionId != null) {
            attributes.put(
                    ToolContextKeys.EXECUTION_ID,
                    executionId
            );
        }

        if (interaction != null) {
            attributes.put(
                    ToolContextKeys.INTERACTION,
                    interaction
            );
        }

        return new InteractionAwareToolContext(
                attributes
        );
    }

    @Override
    public Map<String, Object> attributes() {
        return attributes;
    }

    @Override
    public Optional<Object> getAttribute(
            String key
    ) {
        return Optional.ofNullable(
                attributes.get(key)
        );
    }

    public Optional<InteractionContext> interaction() {
        Object value =
                attributes.get(
                        ToolContextKeys.INTERACTION
                );

        if (value instanceof InteractionContext context) {
            return Optional.of(context);
        }

        return Optional.empty();
    }

    public Optional<String> runId() {
        return textAttribute(
                ToolContextKeys.RUN_ID
        );
    }

    public Optional<String> executionId() {
        return textAttribute(
                ToolContextKeys.EXECUTION_ID
        );
    }

    private Optional<String> textAttribute(
            String key
    ) {
        Object value = attributes.get(key);

        if (value == null) {
            return Optional.empty();
        }

        String text =
                String.valueOf(value).trim();

        return text.isEmpty()
                ? Optional.empty()
                : Optional.of(text);
    }
}
