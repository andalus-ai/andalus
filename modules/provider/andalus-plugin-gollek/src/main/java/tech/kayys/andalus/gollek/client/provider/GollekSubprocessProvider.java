package tech.kayys.andalus.aqli.client.provider;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import tech.kayys.andalus.embedding.EmbeddingRequest;
import tech.kayys.andalus.embedding.EmbeddingResponse;
import tech.kayys.andalus.inference.InferenceRequest;
import tech.kayys.andalus.inference.InferenceResponse;
import tech.kayys.andalus.inference.StreamingInferenceChunk;
import tech.kayys.andalus.provider.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Aqli SDK inference provider that delegates to {@code aqli run} subprocess.
 *
 * <p>This provider has a public no-arg constructor and is registered via Java SPI
 * ({@code META-INF/services/tech.kayys.andalus.provider.LLMProvider}), so it
 * is discovered by {@link java.util.ServiceLoader} in the standalone/manual-init
 * path (i.e., when CDI is unavailable in the shaded CLI JAR).
 *
 * <p>It acts as a catch-all: {@link #supports} returns {@code true} for any model,
 * and inference is performed via {@code aqli run --no-banner}. Aqli telemetry
 * lines are filtered from stdout so that only the model's text response is returned.
 */
public class AqliSubprocessProvider implements StreamingProvider {

    private static final String PROVIDER_ID = "aqli-subprocess";

    /** No-arg constructor required for {@link java.util.ServiceLoader} discovery. */
    public AqliSubprocessProvider() {}

    // ── LLMProvider ──────────────────────────────────────────────────────────

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String name() {
        return "Aqli Subprocess Provider (aqli run)";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean supports(String modelId, ProviderRequest request) {
        // Accept any model; aqli itself validates whether the model exists.
        return true;
    }

    @Override
    public ProviderMetadata metadata() {
        return ProviderMetadata.builder()
                .providerId(PROVIDER_ID)
                .name(name())
                .version(version())
                .build();
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.builder()
                .streaming(true)
                .build();
    }

    @Override
    public Uni<ProviderHealth> health() {
        return Uni.createFrom().item(ProviderHealth.healthy());
    }

    @Override
    public Optional<ProviderMetrics> metrics() {
        return Optional.of(new ProviderMetrics());
    }

    @Override
    public void shutdown() {
        // No-op for subprocess provider
    }

    @Override
    public void initialize(ProviderConfig config) {
        // No-op for subprocess provider
    }

    @Override
    public Uni<InferenceResponse> infer(ProviderRequest request) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                StringBuilder sb = new StringBuilder();
                runAqli(request, line -> sb.append(line).append('\n'));
                emitter.complete(InferenceResponse.builder()
                        .requestId(UUID.randomUUID().toString())
                        .content(sb.toString().trim())
                        .build());
            } catch (Exception e) {
                emitter.fail(e);
            }
        });
    }

    @Override
    public Multi<StreamingInferenceChunk> inferStream(ProviderRequest request) {
        return Multi.createFrom().emitter(emitter -> {
            try {
                int[] idx = {0};
                runAqli(request, line -> emitter.emit(StreamingInferenceChunk.textDelta(
                        UUID.randomUUID().toString(), idx[0]++, line + "\n")));
                emitter.complete();
            } catch (Exception e) {
                emitter.fail(e);
            }
        });
    }

    @Override
    public Uni<EmbeddingResponse> embed(EmbeddingRequest request) {
        return Uni.createFrom().failure(
                new UnsupportedOperationException("Embeddings not supported via aqli run"));
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /** Runs {@code aqli run} and passes filtered model-output lines to {@code sink}. */
    private void runAqli(ProviderRequest request, java.util.function.Consumer<String> sink)
            throws Exception {
        String modelId = request.getModel();
        String prompt = extractPrompt(request);
        int maxTokens = getIntParam(request, "max_tokens", 2048);
        double temperature = getDoubleParam(request, "temperature", 0.7);

        ProcessBuilder pb = new ProcessBuilder(
                "aqli", "run", "--no-banner",
                "--model", modelId,
                "--prompt", prompt,
                "--max-tokens", String.valueOf(maxTokens),
                "--temperature", String.valueOf(temperature)
        );
        
        String quantize = System.getProperty("andalus.model.quantize");
        if (quantize != null && !quantize.isBlank()) {
            pb.command().add("--quantize");
            pb.command().add(quantize.trim());
        }
        
        pb.redirectErrorStream(false);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("NO_COLOR", "1");
        pb.environment().put("AQLI_MAX_DIRECT_MEMORY", "12g");
        // Expand the GGUF fast-run context window to 16384 tokens so the full tool+history prompt fits.
        // AQLI_GGUF_FAST_DAEMON=false forces one-shot mode; the daemon reuses its context size from
        // when it was first started, so we must bypass it to honour our new context setting.
        pb.environment().put("AQLI_JAVA_OPTS", "-Daqli.gguf.fast_run.context=16384");
        pb.environment().put("AQLI_GGUF_FAST_DAEMON", "false");

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {

            boolean inModelOutput = false;
            String line;

            while ((line = reader.readLine()) != null) {
                if (!inModelOutput) {
                    // The "---..." separator marks the start of model output
                    if (line.startsWith("---")) {
                        inModelOutput = true;
                    }
                    continue; // skip all pre-separator telemetry
                }

                // Skip known telemetry lines inside the output region
                if (isAqliTelemetry(line)) {
                    continue;
                }

                // Post-output telemetry — stop
                if (line.startsWith("[Fast GGUF") || line.startsWith("[GGUF")
                        || line.startsWith("Performance Metrics:")
                        || line.startsWith("[Stream updates:")) {
                    break;
                }

                sink.accept(line);
            }

            // Drain remaining stdout silently
            while (reader.readLine() != null) { }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("aqli run exited with code " + exitCode);
        }
    }

    private static boolean isAqliTelemetry(String line) {
        if (line == null) return false;
        return line.startsWith("Using llama.cpp")
                || line.startsWith("Using safetensor")
                || line.startsWith("Resolved local model")
                || line.startsWith("Model: ")
                || line.startsWith("Provider: ")
                || line.startsWith("Execution route:")
                || line.startsWith("Quantization:")
                || line.startsWith("KV cache quantization:")
                || line.startsWith("Platform:")
                || line.startsWith("✓ GPU acceleration")
                || line.startsWith("⚠️  Running on CPU")
                || line.startsWith("--------------------------------------------------")
                || line.startsWith("[Fast GGUF")
                || line.startsWith("[GGUF")
                || line.startsWith("Performance Metrics:")
                || line.startsWith("  load time")
                || line.startsWith("  prompt eval")
                || line.startsWith("  decode")
                || line.startsWith("  latency")
                || line.startsWith("  engine ttft")
                || line.startsWith("  profile:")
                || line.startsWith("    prefill")
                || line.startsWith("    decode")
                || line.startsWith("    sampling")
                || line.startsWith("    engine ttft")
                || line.startsWith("    attention")
                || line.startsWith("    ffn")
                || line.startsWith("    logits")
                || line.startsWith("    bottleneck")
                || line.startsWith("    profile advice")
                || line.startsWith("    host load")
                || line.startsWith("    benchmark note")
                || line.startsWith("    benchmark advice")
                || line.startsWith("  open time")
                || line.startsWith("  generate call")
                || line.startsWith("  generation")
                || line.startsWith("  token latency")
                || line.startsWith("  native ")
                || line.startsWith("Duration:")
                || line.startsWith("Speed:");
    }

    private static String extractPrompt(ProviderRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        
        // Extract system prompt (tools are injected here)
        for (tech.kayys.andalus.Message msg : request.getMessages()) {
            if (msg.getRole() == tech.kayys.andalus.Message.Role.SYSTEM && msg.getContent() != null) {
                sb.append(msg.getContent()).append("\n\n");
            }
        }
        
        // Extract conversation history
        for (tech.kayys.andalus.Message msg : request.getMessages()) {
            if (msg.getContent() != null) {
                if (msg.getRole() == tech.kayys.andalus.Message.Role.USER) {
                    sb.append("User: ").append(msg.getContent()).append("\n");
                } else if (msg.getRole() == tech.kayys.andalus.Message.Role.ASSISTANT) {
                    sb.append("Assistant: ").append(msg.getContent()).append("\n");
                }
            }
        }
        
        // Prompt the model to respond
        sb.append("Assistant:");
        
        return sb.toString();
    }

    private static int getIntParam(ProviderRequest r, String key, int def) {
        Object v = r.getParameters() != null ? r.getParameters().get(key) : null;
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    private static double getDoubleParam(ProviderRequest r, String key, double def) {
        Object v = r.getParameters() != null ? r.getParameters().get(key) : null;
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }
}
