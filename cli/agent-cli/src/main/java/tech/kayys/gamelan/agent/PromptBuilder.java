package tech.kayys.gamelan.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.agent.skill.Skill;
import tech.kayys.gamelan.tool.BuiltInTools;

import java.time.LocalDate;
import java.util.List;

/**
 * Builds the system prompt for each agent invocation.
 *
 * <p>The system prompt has three layers:
 * <ol>
 *   <li><strong>Core identity</strong> — who Gamelan is, what it can do</li>
 *   <li><strong>Tool protocol</strong> — available built-in tools with descriptions</li>
 *   <li><strong>Skill context</strong> — instructions from selected skills, injected verbatim</li>
 * </ol>
 */
@ApplicationScoped
public class PromptBuilder {

    @Inject
    BuiltInTools builtInTools;

    private static final String CORE_IDENTITY = """
        You are Gamelan, an expert AI software development assistant running locally via the Aqli inference engine.
        You have deep expertise in software engineering, architecture, debugging, testing, and refactoring.
        Today is %s.

        You operate in a local development environment. You can read and write files, execute shell commands,
        search codebases, and run scripts — always with the developer's explicit intent in mind.

        ## Principles
        - Be concise but complete. Show code, not just descriptions.
        - When uncertain, ask a clarifying question before taking action.
        - Prefer small, focused changes over sweeping rewrites.
        - Explain your reasoning when making non-obvious decisions.
        - Respect existing code style and patterns in the codebase.
        - Use tools to gather information before making changes.
        """;

    private static final String TOOL_PROTOCOL_HEADER = """
        ## Available Tools
        You have access to the following tools. Use them when needed to accomplish tasks.
        Each tool call should include the tool name and required parameters.

        """;

    /**
     * Assembles the complete system prompt for a given set of skills.
     *
     * @param relevantSkills skills selected for this turn
     * @return the system prompt string
     */
    public String buildSystemPrompt(List<Skill> relevantSkills) {
        StringBuilder sb = new StringBuilder();

        // 1. Core identity
        sb.append(String.format(CORE_IDENTITY, LocalDate.now()));
        sb.append("\n\n");

        // 2. Tool descriptions
        sb.append(TOOL_PROTOCOL_HEADER);
        sb.append(builtInTools.describeAll());
        sb.append("\n\n");

        // 3. Injected skills
        if (!relevantSkills.isEmpty()) {
            sb.append("## Activated Skills\n");
            sb.append("The following skill instructions are active for this request:\n\n");

            for (Skill skill : relevantSkills) {
                sb.append("### Skill: ").append(skill.name()).append("\n");
                sb.append(skill.instructions());
                sb.append("\n\n");

                // Append any reference files the skill declares
                skill.references().forEach((name, content) -> {
                    sb.append("**Reference — ").append(name).append(":**\n");
                    sb.append("```\n").append(content).append("\n```\n\n");
                });
            }
        }

        return sb.toString();
    }

    /**
     * Builds a minimal prompt for direct tool invocations (no skill context needed).
     */
    public String buildToolOnlyPrompt() {
        return String.format(CORE_IDENTITY, LocalDate.now())
                + "\n\n"
                + TOOL_PROTOCOL_HEADER
                + builtInTools.describeAll();
    }
}
