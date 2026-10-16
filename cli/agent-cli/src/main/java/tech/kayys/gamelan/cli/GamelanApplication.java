package tech.kayys.gamelan.cli;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IFactory;
import picocli.CommandLine.Option;

/**
 * Gamelan CLI — agentic command-line interface powered by the Aqli local inference engine.
 *
 * <p>Inspired by Claude Code, Gamelan provides an interactive REPL and a one-shot execution
 * mode for AI-driven development tasks. It discovers and executes skills conforming to
 * the <a href="https://agentskills.io/specification">Agent Skills specification</a>.
 *
 * <pre>
 * Usage examples:
 *   gamelan                        # Start interactive REPL
 *   gamelan "refactor MyClass.java" # One-shot task
 *   gamelan skill list             # List available skills
 *   gamelan skill run read-file    # Run a specific skill
 *   gamelan config set model qwen2 # Configure the LLM
 *   gamelan models                 # List local models
 * </pre>
 */
@QuarkusMain
@Command(
    name = "gamelan",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    description = {
        "@|bold,cyan Gamelan CLI|@ — Agentic AI for local development",
        "Powered by the Aqli inference engine + agentskills.io skills system",
        ""
    },
    subcommands = {
        ChatCommand.class,
        RunCommand.class,
        SkillCommand.class,
        ModelCommand.class,
        ConfigCommand.class,
        ApprovalCommand.class,
        DiagnosticCommand.class
    }
)
public class GamelanApplication implements Runnable, QuarkusApplication {

    @Inject
    IFactory factory;

    @Inject
    ChatCommand chatCommand;

    @Option(names = {"-m", "--model"},
            description = "Model to use (overrides config)", paramLabel = "MODEL")
    String modelOverride;

    @Option(names = {"-v", "--verbose"},
            description = "Verbose output")
    boolean verbose;

    @Option(names = {"--no-color"},
            description = "Disable ANSI color output")
    boolean noColor;

    @Override
    public void run() {
        // When invoked with no subcommand, start interactive REPL
        chatCommand.startRepl(modelOverride, verbose, noColor);
    }

    @Override
    public int run(String... args) throws Exception {
        // Initialize dynamic plugins
        try {
            tech.kayys.andalus.inference.InferenceService.getInstance().reloadProviders();
        } catch (Exception e) {
            // Log and continue if plugin loading fails
            System.err.println("Warning: Failed to load provider plugins: " + e.getMessage());
        }
        
        return new CommandLine(this, factory).execute(args);
    }
}
