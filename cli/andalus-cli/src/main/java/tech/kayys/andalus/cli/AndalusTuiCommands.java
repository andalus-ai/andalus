package tech.kayys.andalus.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * CLI command module for opening the Andalus agentic terminal UI.
 *
 * <p>Launches the full agentic REPL/Panel experience backed by the local
 * Aqli/Andalus inference engine. Switches between REPL and Panel layouts
 * on demand via the {@code /panel} and {@code /repl} slash commands.</p>
 */
final class AndalusTuiCommands {

    private AndalusTuiCommands() {
    }

    @Command(name = "tui", aliases = {"agent", "code"}, description = "Open the Andalus agentic terminal UI.")
    static final class TuiCommand implements Callable<Integer> {
        @ParentCommand
        AndalusAqliCli parent;

        @Override
        public Integer call() {
            AndalusCliContext context = parent.context();
            try {
                tech.kayys.andalus.tui.Main.main(new String[0]);
                return 0;
            } catch (Exception e) {
                context.err().println("Unable to open Andalus TUI: " + e.getMessage());
                if (Boolean.getBoolean("andalus.cli.debug")) e.printStackTrace(context.err());
                return 1;
            }
        }
    }
}
