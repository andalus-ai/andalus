package tech.kayys.andalus.tool.audio;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import tech.kayys.aqli.spi.audio.AudioPipeline;
import tech.kayys.aqli.spi.audio.TtsRequest;
import tech.kayys.aqli.spi.audio.TtsResult;
import tech.kayys.andalus.descriptor.CapabilityDescriptor;
import tech.kayys.andalus.descriptor.ParameterDescriptor;
import tech.kayys.andalus.extension.Id;
import tech.kayys.andalus.extension.Metadata;
import tech.kayys.andalus.extension.Version;
import tech.kayys.andalus.identity.ResourceId;
import tech.kayys.andalus.resource.ResourceType;
import tech.kayys.andalus.tool.Tool;
import tech.kayys.andalus.tool.ToolContext;
import tech.kayys.andalus.tool.ToolDescriptor;
import tech.kayys.andalus.tool.ToolInvocation;
import tech.kayys.andalus.tool.ToolResult;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Andalus Agent Tool for Text-To-Speech (TTS) synthesis powered by Aqli / Al-Khawarizm audio backends.
 */
@ApplicationScoped
public class TextToSpeechTool implements Tool {

    private final ResourceId.ToolId id = new ResourceId.ToolId(Id.random());
    private final Metadata metadata = Metadata.builder()
            .name("synthesize_speech")
            .description("Synthesizes spoken audio from text (TTS) with customizable voice, language, speed, and pitch.")
            .version(Version.VERSION_1_0_0)
            .build();

    @Inject
    Instance<AudioPipeline> audioPipelines;

    @Override
    public ResourceId id() {
        return id;
    }

    @Override
    public ResourceType type() {
        return new ResourceType.Tool();
    }

    @Override
    public Metadata metadata() {
        return metadata;
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor() {
            @Override public ResourceId id() { return id; }
            @Override public ResourceType type() { return new ResourceType.Tool(); }
            @Override public Metadata metadata() { return metadata; }
            @Override public Set<String> tags() { return Set.of("audio", "tts", "voice", "speech"); }
            @Override public Set<String> categories() { return Set.of("multimodal", "audio"); }
            @Override public Map<String, ParameterDescriptor> inputs() { return Map.of(); }
            @Override public Map<String, ParameterDescriptor> outputs() { return Map.of(); }
            @Override public List<CapabilityDescriptor> capabilities() { return List.of(); }
            @Override public String name() { return "synthesize_speech"; }
            @Override public String description() { return metadata.description(); }
            @Override public String version() { return "1.0.0"; }

            @Override
            public Map<String, Object> inputSchema() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");
                Map<String, Object> props = new HashMap<>();
                props.put("text", Map.of("type", "string", "description", "The text to synthesize into speech"));
                props.put("voice", Map.of("type", "string", "description", "Voice name or speaker identity (e.g. 'aura', 'female-1')"));
                props.put("language", Map.of("type", "string", "description", "Language code (e.g. 'en', 'id', 'es')"));
                props.put("speed", Map.of("type", "number", "description", "Playback speed multiplier (e.g. 1.0, 1.2)"));
                props.put("pitch", Map.of("type", "number", "description", "Voice pitch multiplier (e.g. 1.0)"));
                schema.put("properties", props);
                schema.put("required", List.of("text"));
                return schema;
            }
        };
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolInvocation invocation, ToolContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> args = invocation.arguments();
                String text = (String) args.getOrDefault("text", "");
                String voice = (String) args.getOrDefault("voice", "default");
                String language = (String) args.getOrDefault("language", "en");
                float speed = parseFloat(args.get("speed"), 1.0f);
                float pitch = parseFloat(args.get("pitch"), 1.0f);

                TtsRequest request = TtsRequest.builder()
                    .text(text)
                    .voice(voice)
                    .language(language)
                    .speed(speed)
                    .pitch(pitch)
                    .outputFormat("audio/wav")
                    .build();

                AudioPipeline pipeline = audioPipelines != null && audioPipelines.isResolvable() ? audioPipelines.get() : null;
                if (pipeline == null) {
                    Map<String, Object> fallbackOutputs = new HashMap<>();
                    fallbackOutputs.put("status", "mock_tts_ready");
                    fallbackOutputs.put("text", text);
                    fallbackOutputs.put("voice", voice);
                    fallbackOutputs.put("language", language);
                    fallbackOutputs.put("audio_base64", Base64.getEncoder().encodeToString(new byte[128]));
                    return success(fallbackOutputs);
                }

                TtsResult result = pipeline.synthesize(request).await().indefinitely();
                String base64Audio = Base64.getEncoder().encodeToString(result.audioData());

                Map<String, Object> outputs = new HashMap<>();
                outputs.put("mime_type", result.mimeType());
                outputs.put("duration_ms", result.durationMs());
                outputs.put("model_id", result.modelId());
                outputs.put("audio_base64", base64Audio);

                return success(outputs);

            } catch (Exception e) {
                return failure("Speech synthesis failed: " + e.getMessage());
            }
        });
    }

    private static float parseFloat(Object val, float fallback) {
        if (val instanceof Number n) return n.floatValue();
        if (val instanceof String s) {
            try { return Float.parseFloat(s); } catch (Exception ignored) {}
        }
        return fallback;
    }

    private ToolResult success(Map<String, Object> outputs) {
        return new ToolResult() {
            @Override public ResourceId id() { return id; }
            @Override public ResourceType type() { return new ResourceType.Tool(); }
            @Override public Metadata metadata() { return metadata; }
            @Override public Map<String, Object> getOutputs() { return outputs; }
            @Override public boolean isSuccess() { return true; }
            @Override public String getErrorMessage() { return null; }
        };
    }

    private ToolResult failure(String errorMessage) {
        return new ToolResult() {
            @Override public ResourceId id() { return id; }
            @Override public ResourceType type() { return new ResourceType.Tool(); }
            @Override public Metadata metadata() { return metadata; }
            @Override public Map<String, Object> getOutputs() { return Map.of(); }
            @Override public boolean isSuccess() { return false; }
            @Override public String getErrorMessage() { return errorMessage; }
        };
    }
}
