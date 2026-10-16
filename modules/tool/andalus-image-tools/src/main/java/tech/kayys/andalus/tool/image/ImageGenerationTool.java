package tech.kayys.andalus.tool.image;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import tech.kayys.aqli.spi.image.GeneratedImage;
import tech.kayys.aqli.spi.image.ImageGenRequest;
import tech.kayys.aqli.spi.image.ImageGenerationPipeline;
import tech.kayys.andalus.tool.Tool;
import tech.kayys.andalus.tool.ToolContext;
import tech.kayys.andalus.tool.ToolDescriptor;
import tech.kayys.andalus.tool.ToolInvocation;
import tech.kayys.andalus.tool.ToolResult;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Andalus Agent Tool for text-to-image generation powered by Aqli image pipelines (FLUX, SD).
 */
@ApplicationScoped
public class ImageGenerationTool implements Tool {

    @Inject
    Instance<ImageGenerationPipeline> pipelines;

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor() {
            @Override
            public String name() {
                return "generate_image";
            }

            @Override
            public String description() {
                return "Generates high-quality illustrations or artwork from a text prompt using Aqli FLUX / Stable Diffusion pipelines.";
            }

            @Override
            public String version() {
                return "1.0.0";
            }

            @Override
            public Map<String, Object> inputSchema() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");
                Map<String, Object> props = new HashMap<>();
                props.put("prompt", Map.of("type", "string", "description", "Text description of the desired image"));
                props.put("negative_prompt", Map.of("type", "string", "description", "Elements to avoid in the image"));
                props.put("width", Map.of("type", "integer", "description", "Image width in pixels (e.g. 512, 1024)"));
                props.put("height", Map.of("type", "integer", "description", "Image height in pixels (e.g. 512, 1024)"));
                props.put("steps", Map.of("type", "integer", "description", "Denoising steps (e.g. 4 for schnell, 28 for dev)"));
                props.put("guidance_scale", Map.of("type", "number", "description", "CFG guidance scale (e.g. 3.5)"));
                props.put("seed", Map.of("type", "integer", "description", "Random seed for reproducibility"));
                schema.put("properties", props);
                schema.put("required", java.util.List.of("prompt"));
                return schema;
            }
        };
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolInvocation invocation, ToolContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> args = invocation.arguments();
                String prompt = (String) args.getOrDefault("prompt", "");
                String negPrompt = (String) args.getOrDefault("negative_prompt", "");
                int width = parseInteger(args.get("width"), 1024);
                int height = parseInteger(args.get("height"), 1024);
                int steps = parseInteger(args.get("steps"), 20);
                float guidance = parseFloat(args.get("guidance_scale"), 3.5f);
                long seed = parseLong(args.get("seed"), 0L);

                ImageGenRequest request = ImageGenRequest.builder()
                        .prompt(prompt)
                        .negativePrompt(negPrompt)
                        .dimensions(width, height)
                        .steps(steps)
                        .guidanceScale(guidance)
                        .seed(seed)
                        .build();

                ImageGenerationPipeline pipeline = pipelines.isResolvable() ? pipelines.get() : null;
                if (pipeline == null) {
                    return failure("No active ImageGenerationPipeline found in runtime.");
                }

                GeneratedImage img = pipeline.generate(request);
                String base64Image = Base64.getEncoder().encodeToString(img.data());

                Map<String, Object> outputs = new HashMap<>();
                outputs.put("request_id", img.requestId());
                outputs.put("mime_type", img.mimeType());
                outputs.put("width", img.width());
                outputs.put("height", img.height());
                outputs.put("model_id", img.modelId());
                outputs.put("generation_time_ms", img.generationTimeMs());
                outputs.put("image_base64", base64Image);

                return success(outputs);

            } catch (Exception e) {
                return failure("Image generation failed: " + e.getMessage());
            }
        });
    }

    private static int parseInteger(Object val, int fallback) {
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (Exception ignored) {}
        }
        return fallback;
    }

    private static float parseFloat(Object val, float fallback) {
        if (val instanceof Number n) return n.floatValue();
        if (val instanceof String s) {
            try { return Float.parseFloat(s); } catch (Exception ignored) {}
        }
        return fallback;
    }

    private static long parseLong(Object val, long fallback) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s); } catch (Exception ignored) {}
        }
        return fallback;
    }

    private static ToolResult success(Map<String, Object> outputs) {
        return new ToolResult() {
            @Override
            public Map<String, Object> getOutputs() {
                return outputs;
            }

            @Override
            public boolean isSuccess() {
                return true;
            }

            @Override
            public String getErrorMessage() {
                return null;
            }
        };
    }

    private static ToolResult failure(String errorMessage) {
        return new ToolResult() {
            @Override
            public Map<String, Object> getOutputs() {
                return Map.of();
            }

            @Override
            public boolean isSuccess() {
                return false;
            }

            @Override
            public String getErrorMessage() {
                return errorMessage;
            }
        };
    }
}
