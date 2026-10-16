package tech.kayys.andalus.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import tech.kayys.andalus.harness.HarnessPlan;
import tech.kayys.andalus.harness.HarnessPlanRequest;
import tech.kayys.andalus.context.AndalusContextApi;
import tech.kayys.andalus.client.WorkspaceInspectionRequest;
import tech.kayys.andalus.client.WorkspaceSnapshot;

import java.util.concurrent.Callable;

public final class AndalusContextCommands {

    private AndalusContextCommands() {
    }

    @Command(name = "workspace", aliases = "inspect", description = "Inspect workspace context for coding-agent runs.")
    public static final class WorkspaceCommand implements Callable<Integer> {
        @ParentCommand
        public AndalusAqliCli parent;

        @Option(names = {"-p", "--path"}, description = "Workspace path.", defaultValue = ".")
        public String path;

        @Option(names = "--max-entries", description = "Maximum top-level paths to show.", defaultValue = "80")
        public int maxEntries;

        @Option(names = "--include-hidden", description = "Include hidden top-level paths.")
        public boolean includeHidden;

        @Option(names = "--json", description = "Render workspace as compact JSON.")
        public boolean json;

        @Override
        public Integer call() {
            try {
                AndalusCliContext context = parent.context();
                AndalusContextApi contexts = context.client().contexts();
                WorkspaceSnapshot snapshot = contexts.workspace(new WorkspaceInspectionRequest(
                        path,
                        maxEntries,
                        includeHidden));
                AndalusCliRender.jsonOrText(
                        context.out(),
                        json,
                        () -> contexts.workspaceJson(snapshot),
                        () -> AndalusWorkspaceTextFormat.text(snapshot));
                return 0;
            } catch (RuntimeException e) {
                return parent.context().commandFailure(e);
            }
        }
    }

    @Command(name = "harness", aliases = "checks", description = "Plan workspace verification checks for agentic changes.")
    public static final class HarnessCommand implements Callable<Integer> {
        @ParentCommand
        public AndalusAqliCli parent;

        @Option(names = {"-p", "--path"}, description = "Workspace path.", defaultValue = ".")
        public String path;

        @Option(names = "--max-checks", description = "Maximum planned checks to show.", defaultValue = "8")
        public int maxChecks;

        @Option(names = "--required-only", description = "Hide optional checks whose project scripts may not exist.")
        public boolean requiredOnly;

        @Option(names = "--json", description = "Render planned checks as compact JSON.")
        public boolean json;

        @Override
        public Integer call() {
            try {
                AndalusCliContext context = parent.context();
                AndalusContextApi contexts = context.client().contexts();
                HarnessPlan plan = contexts.harness(new HarnessPlanRequest(
                        path,
                        maxChecks,
                        !requiredOnly));
                AndalusCliRender.jsonOrText(
                        context.out(),
                        json,
                        () -> contexts.harnessJson(plan),
                        () -> AndalusHarnessTextFormat.text(plan));
                return 0;
            } catch (RuntimeException e) {
                return parent.context().commandFailure(e);
            }
        }
    }
}
