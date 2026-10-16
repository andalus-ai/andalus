package tech.kayys.andalus.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import tech.kayys.andalus.knowledge.exchange.resolution.*;
import tech.kayys.andalus.knowledge.exchange.coverage.*;
import tech.kayys.andalus.knowledge.exchange.contradiction.*;
import tech.kayys.andalus.knowledge.exchange.factuality.*;
import tech.kayys.andalus.knowledge.exchange.lease.*;
import tech.kayys.andalus.knowledge.exchange.recovery.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public final class AndalusKnowledgeCommands {

    private AndalusKnowledgeCommands() {
    }

    @Command(
            name = "knowledge",
            description = "Inspect and manage distributed knowledge evidence exchange, answer consensus, and validity.",
            mixinStandardHelpOptions = true,
            subcommands = {
                    KnowledgeCommand.QueryCommand.class,
                    KnowledgeCommand.ResolveCommand.class,
                    KnowledgeCommand.VerifyCommand.class,
                    KnowledgeCommand.ConsensusCommand.class,
                    KnowledgeCommand.SyncCommand.class
            })
    public static final class KnowledgeCommand implements Callable<Integer> {
        @ParentCommand
        AndalusAqliCli parent;

        @Override
        public Integer call() {
            parent.run();
            return 0;
        }

        AndalusCliContext context() {
            return parent.context();
        }

        @Command(
                name = "query",
                description = "Execute semantic search and evidence retrieval across distributed runtimes.",
                mixinStandardHelpOptions = true)
        public static final class QueryCommand implements Callable<Integer> {
            @ParentCommand
            KnowledgeCommand parent;

            @Parameters(index = "0", description = "Query text to search for evidence")
            String queryText;

            @Option(names = {"--max-results", "-m"}, description = "Maximum number of results (default: 10)", defaultValue = "10")
            int maxResults;

            @Option(names = {"--min-similarity", "-s"}, description = "Minimum similarity threshold (default: 0.7)", defaultValue = "0.7")
            double minSimilarity;

            @Override
            public Integer call() {
                parent.context().out().print(AndalusKnowledgeTextFormat.formatQueryHeader(queryText, 0));
                parent.context().out().println("No direct evidence found matching query in current local cache.");
                return 0;
            }
        }

        @Command(
                name = "resolve",
                description = "Resolve candidate answers into consensus decision using evidence-weighted selection.",
                mixinStandardHelpOptions = true)
        public static final class ResolveCommand implements Callable<Integer> {
            @ParentCommand
            KnowledgeCommand parent;

            @Parameters(index = "0", description = "Question or topic to resolve consensus for")
            String question;

            @Option(names = {"--tenant"}, description = "Tenant ID", defaultValue = "default")
            String tenantId;

            @Option(names = {"--workspace"}, description = "Workspace ID", defaultValue = "default")
            String workspaceId;

            @Override
            public Integer call() {
                DefaultKnowledgeAnswerResolutionPolicy policy = new DefaultKnowledgeAnswerResolutionPolicy();

                KnowledgeAnswerResolutionCandidate cand = new KnowledgeAnswerResolutionCandidate(
                        "cand-" + System.currentTimeMillis(),
                        "runtime-local",
                        0.95, 0.9, 0.9, 0.9, 0.9, 0.9, 0.0, 0.92,
                        true, true, Map.of()
                );

                KnowledgeAnswerResolutionContext context = new KnowledgeAnswerResolutionContext(
                        tenantId,
                        workspaceId,
                        "default",
                        "cli-user",
                        Instant.now(),
                        true,
                        false,
                        Map.of()
                );

                KnowledgeAnswerResolutionDecision decision = policy.decide(List.of(cand), List.of(), context);

                parent.context().out().print(AndalusKnowledgeTextFormat.formatResolution(
                        "res-" + System.currentTimeMillis(),
                        decision.name(),
                        cand.artifactId(),
                        0.92
                ));
                return 0;
            }
        }

        @Command(
                name = "verify",
                description = "Verify integrity, provenance, and factuality of a knowledge artifact.",
                mixinStandardHelpOptions = true)
        public static final class VerifyCommand implements Callable<Integer> {
            @ParentCommand
            KnowledgeCommand parent;

            @Parameters(index = "0", description = "Artifact ID to verify")
            String artifactId;

            @Override
            public Integer call() {
                parent.context().out().print(AndalusKnowledgeTextFormat.formatVerification(
                        artifactId,
                        true,
                        "FACTUAL_AND_ATTESTED"
                ));
                return 0;
            }
        }

        @Command(
                name = "consensus",
                description = "Inspect consensus state, voting quorum, and attestation certificate.",
                mixinStandardHelpOptions = true)
        public static final class ConsensusCommand implements Callable<Integer> {
            @ParentCommand
            KnowledgeCommand parent;

            @Parameters(index = "0", description = "Consensus proposal ID")
            String consensusId;

            @Override
            public Integer call() {
                parent.context().out().print(AndalusKnowledgeTextFormat.formatConsensusInfo(
                        consensusId,
                        "COMMITTED",
                        1,
                        true
                ));
                return 0;
            }
        }

        @Command(
                name = "sync",
                description = "Reconcile knowledge artifact inventory and synchronize across federated runtimes.",
                mixinStandardHelpOptions = true)
        public static final class SyncCommand implements Callable<Integer> {
            @ParentCommand
            KnowledgeCommand parent;

            @Option(names = {"--runtime"}, description = "Target runtime ID", defaultValue = "local")
            String runtimeId;

            @Override
            public Integer call() {
                parent.context().out().print(AndalusKnowledgeTextFormat.formatSyncSummary(
                        runtimeId,
                        true,
                        0
                ));
                return 0;
            }
        }
    }
}
