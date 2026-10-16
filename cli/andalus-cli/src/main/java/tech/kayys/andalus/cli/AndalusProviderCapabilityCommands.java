package tech.kayys.andalus.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;
import tech.kayys.andalus.client.AndalusClient;
import tech.kayys.andalus.client.AndalusProviderApi;
import tech.kayys.andalus.capability.AndalusProviderCapabilityDescriptor;
import tech.kayys.andalus.capability.AndalusProviderCapabilityDiscovery;
import tech.kayys.andalus.capability.AndalusProviderCapabilityQuery;

import java.util.concurrent.Callable;

final class AndalusProviderCapabilityCommands {

    private AndalusProviderCapabilityCommands() {
    }

    @Command(
            name = "providers",
            aliases = "provider-capabilities",
            description = "Discover provider capabilities across skills, MCP, RAG, storage, standards, and commerce.",
            mixinStandardHelpOptions = true,
            subcommands = {
                    ProvidersCommand.ListCommand.class,
                    ProvidersCommand.InspectCommand.class,
                    ProvidersCommand.SearchCommand.class
            })
    static final class ProvidersCommand implements Callable<Integer> {
        @ParentCommand
        AndalusAqliCli parent;

        @Option(names = "--json", description = "Render provider capabilities as compact JSON.")
        boolean json;

        @Mixin
        AndalusProviderCapabilityQueryOptions query = new AndalusProviderCapabilityQueryOptions();

        @Override
        public Integer call() {
            return renderList(query, "", json);
        }

        private Integer renderList(AndalusProviderCapabilityQueryOptions options, String search, boolean json) {
            try {
                AndalusCliContext context = parent.context();
                AndalusProviderCapabilityQuery capabilityQuery = options.toQuery(null);
                AndalusClient client = context.client();
                AndalusProviderApi providers = client.providers();
                AndalusProviderCapabilityDiscovery discovery = providers.discover(capabilityQuery, search);
                AndalusCliRender.jsonOrText(
                        context.out(),
                        json,
                        () -> providers.discoveryJson(discovery),
                        () -> AndalusProviderCapabilityTextFormat.text(client.productName(), discovery));
                return 0;
            } catch (RuntimeException e) {
                return parent.context().commandFailure(e);
            }
        }

        @Command(name = "list", description = "List provider capabilities.")
        static final class ListCommand implements Callable<Integer> {
            @ParentCommand
            ProvidersCommand parent;

            @Option(names = "--json", description = "Render provider capabilities as compact JSON.")
            boolean json;

            @Mixin
            AndalusProviderCapabilityQueryOptions query = new AndalusProviderCapabilityQueryOptions();

            @Override
            public Integer call() {
                return parent.renderList(query, "", json);
            }
        }

        @Command(name = "inspect", description = "Show one provider capability by id.")
        static final class InspectCommand implements Callable<Integer> {
            @ParentCommand
            ProvidersCommand parent;

            @Parameters(index = "0", description = "Provider capability id to inspect.")
            String capabilityId;

            @Option(names = "--json", description = "Render provider capability as compact JSON.")
            boolean json;

            @Override
            public Integer call() {
                try {
                    AndalusCliContext context = parent.parent.context();
                    AndalusClient client = context.client();
                    AndalusProviderApi providers = client.providers();
                    AndalusProviderCapabilityDescriptor capability = providers.get(capabilityId);
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            () -> providers.detailJson(capability),
                            () -> AndalusProviderCapabilityTextFormat.detailText(client.productName(), capability));
                    return 0;
                } catch (RuntimeException e) {
                    return parent.parent.context().commandFailure(e);
                }
            }
        }

        @Command(name = "search", description = "Search provider capabilities.")
        static final class SearchCommand implements Callable<Integer> {
            @ParentCommand
            ProvidersCommand parent;

            @Parameters(index = "0", description = "Search term matched against provider capability metadata.")
            String term;

            @Option(names = "--json", description = "Render matching provider capabilities as compact JSON.")
            boolean json;

            @Mixin
            AndalusProviderCapabilityQueryOptions query = new AndalusProviderCapabilityQueryOptions();

            @Override
            public Integer call() {
                return parent.renderList(query, term, json);
            }
        }
    }
}
