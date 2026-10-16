package tech.kayys.andalus.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import tech.kayys.andalus.agent.run.AgentRunPreview;
import tech.kayys.andalus.client.AndalusProductCatalog;
import tech.kayys.andalus.client.AndalusSpecApi;

import java.util.concurrent.Callable;

/**
 * CLI command module for portable Andalus run specs.
 *
 * <p>The module handles command parsing and text output while delegating spec
 * templates, validation, JSON envelopes, and persistence to {@link AndalusSpecApi}.</p>
 */
final class AndalusSpecCommands {

    private AndalusSpecCommands() {
    }

    @Command(
            name = "spec",
            description = "Validate and template portable Andalus run specs.",
            mixinStandardHelpOptions = true,
            subcommands = {
                    SpecCommand.ValidateCommand.class,
                    SpecCommand.TemplateCommand.class
            })
    static final class SpecCommand implements Runnable {
        @ParentCommand
        AndalusAqliCli parent;

        @Spec
        CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }

        AndalusCliContext context() {
            return parent.context();
        }

        @Command(name = "validate", description = "Validate a run spec without submitting a run.")
        static final class ValidateCommand implements Callable<Integer> {
            @ParentCommand
            SpecCommand parent;

            @Option(names = {"-p", "--path"}, required = true, description = "Run spec path.")
            String path;

            @Option(names = "--json", description = "Render validation as compact JSON.")
            boolean json;

            @Override
            public Integer call() {
                try {
                    AndalusCliContext context = parent.context();
                    AndalusSpecApi specs = context.client().specs();
                    AgentRunPreview preview = specs.validate(path);
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            () -> specs.validationJson(path, preview),
                            () -> AndalusSpecTextFormat.validationText(path, preview));
                    return preview.ready() ? 0 : 1;
                } catch (RuntimeException e) {
                    return parent.context().commandFailure(e);
                }
            }
        }

        @Command(name = "template", description = "Print a starter run spec for a product surface.")
        static final class TemplateCommand implements Callable<Integer> {
            @ParentCommand
            SpecCommand parent;

            @Option(
                    names = "--surface",
                    description = "Product surface id for the template.",
                    defaultValue = AndalusProductCatalog.DEFAULT_SURFACE_ID)
            String surfaceId;

            @Option(names = "--profile", description = "Product profile id for the template.")
            String profileId;

            @Option(
                    names = {"-o", "--output"},
                    paramLabel = "<path>",
                    description = "Write template to a UTF-8 file instead of stdout.")
            String outputPath;

            @Option(names = "--force", description = "Allow --output to overwrite an existing file.")
            boolean forceOutput;

            @Override
            public Integer call() {
                try {
                    AndalusCliContext context = parent.context();
                    AndalusSpecApi specs = context.client().specs();
                    AndalusCliOutputTarget output = AndalusCliOutputTarget.of(outputPath, forceOutput);
                    String template = profileId == null || profileId.isBlank()
                            ? specs.templateProperties(surfaceId)
                            : specs.profileTemplateProperties(profileId);
                    output.writeOrPrint(context.out(), specs::writeProperties, "Andalus run spec template", template);
                    return 0;
                } catch (RuntimeException e) {
                    return parent.context().commandFailure(e);
                }
            }
        }
    }
}
