package tech.kayys.andalus.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import tech.kayys.andalus.cli.skill.AndalusSkillQueryOptions;
import tech.kayys.andalus.cli.skill.AndalusSkillTextFormat;
import tech.kayys.andalus.agent.skill.AgentSkillDiscovery;
import tech.kayys.andalus.agent.skill.AgentSkillQuery;
import tech.kayys.andalus.skill.RegisteredSkill;
import tech.kayys.andalus.client.AndalusClient;
import tech.kayys.andalus.skill.AndalusSkillApi;

import java.io.PrintStream;
import java.util.concurrent.Callable;

final class AndalusSkillCommands {

    private AndalusSkillCommands() {}

    @Command(
            name = "skills",
            aliases = "capabilities",
            description = "Manage Andalus skills and agent capabilities.",
            mixinStandardHelpOptions = true,
            subcommands = {
                    SkillsCommand.ListCommand.class,
                    SkillsCommand.InspectCommand.class,
                    SkillsCommand.SearchCommand.class
            })
    static final class SkillsCommand implements Callable<Integer> {

        @ParentCommand
        AndalusAqliCli parent;

        @Override
        public Integer call() {
            return new ListCommand().callWithParent(this);
        }

        AndalusCliContext context() {
            return parent.context();
        }

        @Command(name = "list", description = "List discovered skills.", mixinStandardHelpOptions = true)
        static final class ListCommand implements Callable<Integer> {
            @ParentCommand SkillsCommand parent;

            @Mixin
            AndalusSkillQueryOptions queryOptions = new AndalusSkillQueryOptions();

            @Option(names = "--json", description = "Render skills as JSON.")
            boolean json;

            @Override
            public Integer call() {
                return callWithParent(parent);
            }

            Integer callWithParent(SkillsCommand p) {
                AndalusCliContext context = p.context();
                AndalusClient client = context.client();
                AndalusSkillApi skillApi = client.skills();
                AgentSkillQuery query = queryOptions.toQuery(null);
                AgentSkillDiscovery discovery = skillApi.discover(query);
                if (json) {
                    context.out().print(skillApi.discoveryJson(discovery));
                } else {
                    context.out().print(AndalusSkillTextFormat.text(client.productName(), discovery));
                }
                return 0;
            }
        }

        @Command(name = "inspect", description = "Inspect a specific skill.", mixinStandardHelpOptions = true)
        static final class InspectCommand implements Callable<Integer> {
            @ParentCommand SkillsCommand parent;

            @Parameters(index = "0", description = "Skill ID to inspect.")
            String skillId;

            @Option(names = "--json", description = "Render skill detail as JSON.")
            boolean json;

            @Override
            public Integer call() {
                AndalusCliContext context = parent.context();
                AndalusClient client = context.client();
                AndalusSkillApi skillApi = client.skills();
                RegisteredSkill skill = skillApi.get(skillId);
                if (skill == null) {
                    context.err().println("Skill not found: " + skillId);
                    return 1;
                }
                if (json) {
                    context.out().print(skillApi.detailJson(skill));
                } else {
                    context.out().print(AndalusSkillTextFormat.detailText(client.productName(), skill));
                }
                return 0;
            }
        }

        @Command(name = "search", description = "Search for skills.", mixinStandardHelpOptions = true)
        static final class SearchCommand implements Callable<Integer> {
            @ParentCommand SkillsCommand parent;

            @Parameters(index = "0", description = "Search term.")
            String term;

            @Option(names = "--json", description = "Render search results as JSON.")
            boolean json;

            @Override
            public Integer call() {
                AndalusCliContext context = parent.context();
                AndalusClient client = context.client();
                AndalusSkillApi skillApi = client.skills();
                AgentSkillDiscovery discovery = skillApi.discover(new AgentSkillQuery(null, null, null, null, null, term, null, null, null), term);
                if (json) {
                    context.out().print(skillApi.discoveryJson(discovery));
                } else {
                    context.out().print(AndalusSkillTextFormat.text(client.productName(), discovery));
                }
                return 0;
            }
        }
    }
}
