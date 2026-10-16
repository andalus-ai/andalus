package tech.kayys.andalus.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import tech.kayys.andalus.client.AndalusClient;
import tech.kayys.andalus.client.AndalusWorkbenchModel;
import tech.kayys.andalus.workbench.WorkbenchCommandDiscovery;
import tech.kayys.andalus.workbench.WorkbenchCommandQuery;

import java.util.concurrent.Callable;

final class AndalusWorkbenchCommands {

    private AndalusWorkbenchCommands() {
    }

    @Command(name = "commands", aliases = "actions", description = "List SDK-owned workbench commands for agent shells.")
    static final class CommandsCommand implements Callable<Integer> {
        @ParentCommand
        AndalusAqliCli parent;

        @Mixin
        AndalusCommandQueryOptions queryOptions = new AndalusCommandQueryOptions();

        @Option(names = "--index", description = "Render only discovery metadata, categories, and command ids.")
        boolean index;

        @Option(names = "--json", description = "Render commands as compact JSON.")
        boolean json;

        @Override
        public Integer call() {
            try {
                AndalusCliContext context = parent.context();
                AndalusClient client = context.client();
                WorkbenchCommandQuery query = queryOptions.toQuery();
                WorkbenchCommandDiscovery discovery = client.commands().discover(query);
                AndalusCliRender.jsonOrText(
                        context.out(),
                        json,
                        () -> index
                                ? client.commands().indexJson(discovery)
                                : client.commands().discoveryJson(discovery),
                        () -> index
                                ? AndalusCommandTextFormat.indexText(client.productName(), discovery)
                                : AndalusCommandTextFormat.text(query, discovery.commands()));
                return 0;
            } catch (RuntimeException e) {
                return parent.context().commandFailure(e);
            }
        }
    }

    @Command(
            name = "workbench",
            aliases = "dashboard",
            description = "Render the SDK-owned agent workbench model without opening a TUI.")
    static final class WorkbenchCommand implements Callable<Integer> {
        @ParentCommand
        AndalusAqliCli parent;

        @Option(names = "--json", description = "Render workbench model as compact JSON.")
        boolean json;

        @Mixin
        AndalusCommandQueryOptions queryOptions = new AndalusCommandQueryOptions();

        @Override
        public Integer call() {
            try {
                AndalusCliContext context = parent.context();
                AndalusClient client = context.client();
                WorkbenchCommandQuery query = queryOptions.toQuery();
                AndalusWorkbenchModel workbench = client.commands().workbench(query);
                AndalusCliRender.jsonOrText(
                        context.out(),
                        json,
                        () -> client.commands().workbenchJson(workbench, query),
                        () -> {
                            var workspace = client.contexts().workspace(".", 200, false);
                            return new PlainWorkbenchRenderer().render(workbench, workspace);
                        });
                return 0;
            } catch (RuntimeException e) {
                return parent.context().commandFailure(e);
            }
        }
    }
}
