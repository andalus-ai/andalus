package tech.kayys.andalus.cli.contract;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import tech.kayys.andalus.cli.AndalusCliContext;
import tech.kayys.andalus.cli.AndalusCliRender;
import tech.kayys.andalus.cli.AndalusAqliCli;
import tech.kayys.andalus.client.AndalusClient;
import tech.kayys.andalus.contract.AndalusContractApi;
import tech.kayys.andalus.contract.AndalusContractDescriptor;
import tech.kayys.andalus.contract.AndalusContractDiscovery;
import tech.kayys.andalus.contract.AndalusContractCommandCoverageReport;
import tech.kayys.andalus.contract.AndalusContractIntegrityReport;
import tech.kayys.andalus.contract.AndalusContractQuery;

import java.util.concurrent.Callable;

final public class AndalusContractCommands {

    private AndalusContractCommands() {
    }

    @Command(name = "contracts", aliases = "schemas", description = "List SDK-owned JSON contracts for product shells.")
    public static final class ContractsCommand implements Callable<Integer> {
        @ParentCommand
        AndalusAqliCli parent;

        @Mixin
        AndalusContractQueryOptions queryOptions = new AndalusContractQueryOptions();

        @Option(names = "--json", description = "Render contract catalog as compact JSON.")
        boolean json;

        @Option(names = "--index", description = "Render only discovery metadata, facets, and command ids.")
        boolean index;

        @Option(names = "--schema-json", description = "Render JSON Schema for one matching contract envelope.")
        boolean schemaJson;

        @Option(names = "--schema-bundle-json", description = "Render JSON Schema documents for all matching contracts.")
        boolean schemaBundleJson;

        @Option(names = {"--check", "--validate"}, description = "Validate command/contract catalog links.")
        boolean check;

        @Option(names = "--coverage", description = "Render command coverage for all SDK-owned JSON contracts.")
        boolean coverage;

        @Override
        public Integer call() {
            try {
                AndalusCliContext context = parent.context();
                AndalusClient client = context.client();
                AndalusContractApi contracts = client.contracts();
                String productName = client.productName();
                if (check && coverage) {
                    throw new IllegalArgumentException(
                            "Use only one of --check or --coverage for contract diagnostics.");
                }
                if (check) {
                    AndalusContractIntegrityReport report = contracts.integrity();
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            () -> contracts.integrityJson(report),
                            () -> AndalusContractIntegrityTextFormat.text(productName, report));
                    return report.valid() ? 0 : 1;
                }
                if (coverage) {
                    AndalusContractCommandCoverageReport report = contracts.coverage();
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            () -> contracts.coverageJson(report),
                            () -> AndalusContractCoverageTextFormat.text(productName, report));
                    return report.incompleteContracts() == 0 ? 0 : 1;
                }
                AndalusContractQuery query = queryOptions.toQuery();
                AndalusContractDiscovery discovery = contracts.discover(query);
                if (schemaJson && schemaBundleJson) {
                    throw new IllegalArgumentException(
                            "Use only one of --schema-json or --schema-bundle-json for contract schema export.");
                }
                if (schemaJson) {
                    context.out().println(contracts.schemaJson(singleContractForSchema(discovery)));
                    return 0;
                }
                if (schemaBundleJson) {
                    context.out().println(contracts.schemaBundleJson(discovery));
                    return 0;
                }
                AndalusCliRender.jsonOrText(
                        context.out(),
                        json,
                        () -> index
                                ? contracts.indexJson(discovery)
                                : contracts.catalogJson(discovery),
                        () -> index
                                ? AndalusContractCatalogTextFormat.indexText(productName, discovery)
                                : AndalusContractCatalogTextFormat.text(productName, discovery));
                return 0;
            } catch (RuntimeException e) {
                return parent.context().commandFailure(e);
            }
        }

        private static AndalusContractDescriptor singleContractForSchema(AndalusContractDiscovery discovery) {
            int matchingContracts = discovery == null ? 0 : discovery.matchingContracts();
            if (matchingContracts != 1) {
                throw new IllegalArgumentException("--schema-json requires exactly one matching contract; found "
                        + matchingContracts
                        + ". Add --schema, --envelope, --command-id, --domain, or --json-schema-id to narrow the contract query.");
            }
            return discovery.contracts().get(0);
        }
    }
}
