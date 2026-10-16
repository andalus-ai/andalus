package tech.kayys.gamelan.agent;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.agent.skill.Skill;
import tech.kayys.gamelan.agent.skill.SkillRegistry;
import tech.kayys.gamelan.config.GamelanConfig;
import tech.kayys.gamelan.session.ConversationSession;
import tech.kayys.gamelan.tool.BuiltInTools;
import tech.kayys.gamelan.tool.ToolExecutor;
import tech.kayys.gamelan.tool.ToolResult;
import tech.kayys.aqli.sdk.core.AqliSdk;
import tech.kayys.aqli.sdk.exception.SdkException;
import tech.kayys.aqli.spi.Message;
import tech.kayys.aqli.spi.inference.InferenceRequest;
import tech.kayys.aqli.spi.inference.InferenceResponse;
import tech.kayys.aqli.spi.inference.StreamingInferenceChunk;
import tech.kayys.aqli.spi.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * The core agentic loop — orchestrates skill selection, LLM inference with native tool calling,
 * tool execution, and multi-turn reasoning.
 */
@ApplicationScoped
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final int MAX_ITERATIONS = 10;

    @Inject AqliSdk sdk;
    @Inject SkillRegistry skillRegistry;
    @Inject ToolExecutor toolExecutor;
    @Inject PromptBuilder promptBuilder;
    @Inject GamelanConfig config;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public AgentResponse process(String userMessage, ConversationSession session,
                                  String model, boolean streamToStdout) {
        cancelled.set(false);

        List<Skill> relevantSkills = selectSkills(userMessage);
        String systemPrompt = promptBuilder.buildSystemPrompt(relevantSkills);

        StringBuilder fullResponse = new StringBuilder();
        List<ToolResult> toolResults = new ArrayList<>();
        List<String> intermediateMessages = new ArrayList<>();

        List<Message> messages = new ArrayList<>(session.toMessages().stream()
                .map(m -> new Message(Message.Role.valueOf(m.role().toUpperCase()), m.content()))
                .toList());
        messages.add(Message.user(userMessage));

        InferenceResponse lastResponse = null;

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            if (cancelled.get()) break;

            log.debug("Agent loop iteration {}/{}", iteration + 1, MAX_ITERATIONS);

            List<ToolDefinition> toolDefs = builtInToolDefinitions();
            InferenceRequest request = buildRequest(model, systemPrompt, messages, toolDefs);

            InferenceResponse response = streamToStdout
                    ? streamAndCollect(request)
                    : blockAndCollect(request);
            lastResponse = response;

            String iterationText = response.getContent();
            fullResponse.append(iterationText);

            if (!response.hasToolCalls()) break;

            List<InferenceResponse.ToolCall> toolCalls = response.getToolCalls();
            log.info("Executing {} tool call(s): {}", toolCalls.size(),
                    toolCalls.stream().map(InferenceResponse.ToolCall::name).collect(Collectors.joining(", ")));

            for (InferenceResponse.ToolCall call : toolCalls) {
                if (cancelled.get()) break;
                try {
                    ToolResult result = toolExecutor.execute(call.name(), call.arguments());
                    toolResults.add(result);
                    messages.add(Message.tool(call.name(), result.output()));
                } catch (Exception e) {
                    log.error("Tool execution failed: {}", call.name(), e);
                    ToolResult err = ToolResult.error(call.name(), "ERROR: " + e.getMessage());
                    toolResults.add(err);
                    messages.add(Message.tool(call.name(), err.output()));
                }
            }
            intermediateMessages.add(iterationText);
        }

        int inputTokens = lastResponse != null ? lastResponse.getInputTokens() : 0;
        int outputTokens = lastResponse != null ? lastResponse.getOutputTokens() : 0;

        return AgentResponse.builder()
                .text(fullResponse.toString())
                .skillsUsed(relevantSkills.stream().map(Skill::name).toList())
                .toolResults(toolResults)
                .intermediateMessages(intermediateMessages)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .build();
    }

    public void cancelCurrentTask() { cancelled.set(true); }
    public SkillRegistry getSkillRegistry() { return skillRegistry; }
    public AqliSdk getSdk() { return sdk; }

    // ── Internal helpers ──────────────────────────────────────────────────

    private List<Skill> selectSkills(String userMessage) {
        try {
            List<Skill> allSkills = skillRegistry.listEnabled();
            if (allSkills.isEmpty()) return List.of();
            String lower = userMessage.toLowerCase();
            return allSkills.stream()
                    .filter(skill -> {
                        if (lower.contains(skill.name().toLowerCase())) return true;
                        if (skill.description() != null && lower.contains(skill.description().toLowerCase())) return true;
                        return skill.keywords() != null && skill.keywords().stream()
                                .anyMatch(kw -> lower.contains(kw.toLowerCase()));
                    })
                    .limit(5)
                    .toList();
        } catch (Exception e) {
            log.error("Error selecting skills", e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<ToolDefinition> builtInToolDefinitions() {
        // BuiltInTools provides descriptions for the prompt; return empty list for native tool calling
        return List.of();
    }

    private InferenceRequest buildRequest(String model, String systemPrompt,
                                           List<Message> messages, List<ToolDefinition> tools) {
        List<Message> enriched = new ArrayList<>();
        if (messages.isEmpty() || messages.get(0).getRole() != Message.Role.SYSTEM) {
            enriched.add(Message.system(systemPrompt));
        }
        enriched.addAll(messages);
        return InferenceRequest.builder()
                .model(model)
                .messages(enriched)
                .tools(tools)
                .temperature(config.temperature())
                .maxTokens(config.maxTokens())
                .streaming(true)
                .build();
    }

    private InferenceResponse streamAndCollect(InferenceRequest request) {
        StringBuilder sb = new StringBuilder();
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean hasError = new AtomicBoolean(false);
        try {
            Multi<StreamingInferenceChunk> stream = sdk.streamCompletion(request);
            stream.subscribe().with(
                chunk -> { if (chunk.delta() != null && !cancelled.get()) { System.out.print(chunk.delta()); System.out.flush(); sb.append(chunk.delta()); } },
                err -> { log.error("Streaming error: {}", err.getMessage()); hasError.set(true); completed.set(true); },
                () -> { System.out.println(); completed.set(true); }
            );
            int waitMs = 0;
            while (!completed.get() && waitMs < 300_000) { Thread.sleep(100); waitMs += 100; }
        } catch (Exception e) {
            log.error("Stream failed: {}", e.getMessage(), e);
            return InferenceResponse.builder().requestId(request.getRequestId())
                    .content("[Error: " + e.getMessage() + "]").model(request.getModel())
                    .finishReason(InferenceResponse.FinishReason.ERROR).build();
        }
        return InferenceResponse.builder().requestId(request.getRequestId())
                .content(sb.toString()).model(request.getModel())
                .finishReason(InferenceResponse.FinishReason.STOP).build();
    }

    private InferenceResponse blockAndCollect(InferenceRequest request) {
        try {
            return sdk.createCompletion(request);
        } catch (SdkException e) {
            log.error("Inference failed: {}", e.getMessage(), e);
            return InferenceResponse.builder().requestId(request.getRequestId())
                    .content("[Error: " + e.getMessage() + "]").model(request.getModel())
                    .finishReason(InferenceResponse.FinishReason.ERROR).build();
        }
    }
}
