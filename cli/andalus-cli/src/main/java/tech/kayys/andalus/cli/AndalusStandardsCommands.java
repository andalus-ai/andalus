package tech.kayys.andalus.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import tech.kayys.andalus.client.AndalusClient;
import tech.kayys.andalus.catalog.AndalusStandardCatalog;
import tech.kayys.andalus.alignment.AndalusStandardAlignmentHealthReport;
import tech.kayys.andalus.client.AndalusStandardsApi;

import java.util.concurrent.Callable;

final class AndalusStandardsCommands {

    private AndalusStandardsCommands() {
    }

    @Command(
            name = "standards",
            aliases = {"standard-alignment", "alignment"},
            description = "Show SDK standard-alignment health for protocol adapter reports.")
    static final class StandardsCommand implements Callable<Integer> {
        @ParentCommand
        AndalusAqliCli parent;

        @Mixin
        AndalusStandardPolicyOptions policyOptions = new AndalusStandardPolicyOptions();

        @Option(names = "--json", description = "Render the selected standards view as compact JSON.")
        boolean json;

        @Option(
                names = {"--catalog", "--registry"},
                description = "Render the known standards registry instead of readiness health.")
        boolean catalog;

        @Override
        public Integer call() {
            try {
                AndalusCliContext context = parent.context();
                AndalusClient client = context.client();
                AndalusStandardsApi standards = client.standards();
                String productName = client.productName();
                if (catalog) {
                    AndalusStandardCatalog standardsCatalog = standards.catalog();
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            () -> standards.catalogJson(standardsCatalog),
                            () -> AndalusStandardCatalogTextFormat.text(productName, standardsCatalog));
                    return 0;
                }
                AndalusStandardAlignmentHealthReport health =
                        standards.health(policyOptions.toConfig());
                AndalusCliRender.jsonOrText(
                        context.out(),
                        json,
                        () -> standards.healthJson(health),
                        () -> AndalusStandardAlignmentHealthTextFormat.text(productName, health));
                return health.ready() ? 0 : 1;
            } catch (RuntimeException e) {
                return parent.context().commandFailure(e);
            }
        }
    }
}
