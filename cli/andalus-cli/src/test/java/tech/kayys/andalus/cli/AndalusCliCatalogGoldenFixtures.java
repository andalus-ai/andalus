package tech.kayys.andalus.cli;

import tech.kayys.andalus.agent.lifecycle.AgentRunLifecycleContract;
import tech.kayys.andalus.command.AndalusCommandDiscoveryContract;
import tech.kayys.andalus.contract.AndalusContractCatalog;
import tech.kayys.andalus.contract.AndalusContractCoverageContract;
import tech.kayys.andalus.contract.AndalusContractDescriptor;
import tech.kayys.andalus.contract.AndalusContractDiscovery;
import tech.kayys.andalus.contract.AndalusContractQuery;
import tech.kayys.andalus.client.AndalusAqliSdk;
import tech.kayys.andalus.client.AndalusPlatformContract;
import tech.kayys.andalus.capability.AndalusProviderCapabilityContract;
import tech.kayys.andalus.readiness.AndalusReadinessContract;
import tech.kayys.andalus.skill.AndalusSkillContract;
import tech.kayys.andalus.alignment.AndalusStandardAlignmentContract;
import tech.kayys.andalus.catalog.AndalusStandardCatalogContract;
import tech.kayys.andalus.workbench.AndalusWorkbenchContract;

import java.util.List;
import java.util.function.Supplier;

final class AndalusCliCatalogGoldenFixtures {
    private static final Supplier<AndalusAqliSdk> LOCAL_SDK = AndalusAqliSdk::local;
    private static final List<AndalusCliGoldenFixtures.GoldenFixture> ALL = List.of(
            localPayload(
                    "status-json.golden",
                    descriptor(AndalusPlatformContract.SCHEMA, AndalusPlatformContract.PLATFORM_STATUS),
                    "status",
                    "--json"),
            localPayload(
                    "status-readiness-json.golden",
                    descriptor(AndalusReadinessContract.SCHEMA, AndalusReadinessContract.READINESS_AGGREGATE),
                    "status",
                    "--readiness",
                    "--json"),
            localPayload(
                    "status-readiness-profile-json.golden",
                    descriptor(AndalusReadinessContract.SCHEMA, AndalusReadinessContract.READINESS_AGGREGATE),
                    "status",
                    "--readiness-profile",
                    "default",
                    "--json"),
            localPayload(
                    "products-json.golden",
                    descriptor(AndalusPlatformContract.SCHEMA, AndalusPlatformContract.PRODUCT_CATALOG),
                    "products",
                    "--json"),
            localPayload(
                    "profiles-json.golden",
                    descriptor(AndalusPlatformContract.SCHEMA, AndalusPlatformContract.PROFILE_LIST),
                    "profiles",
                    "--json"),
            localPayload(
                    "profiles-surface-json.golden",
                    descriptor(AndalusPlatformContract.SCHEMA, AndalusPlatformContract.PROFILE_LIST),
                    "profiles",
                    "--surface",
                    "assistant-agent",
                    "--json"),
            localPayload(
                    "profiles-inspect-json.golden",
                    descriptor(AndalusPlatformContract.SCHEMA, AndalusPlatformContract.PROFILE_DETAIL),
                    "profiles",
                    "inspect",
                    "assistant-agent",
                    "--json"),
            localPayload(
                    "sdk-boundaries-json.golden",
                    descriptor(AndalusPlatformContract.SCHEMA, AndalusPlatformContract.SDK_BOUNDARY_CATALOG),
                    "sdk-boundaries",
                    "--json"),
            localPayload(
                    "sdk-boundaries-inspect-json.golden",
                    descriptor(AndalusPlatformContract.SCHEMA, AndalusPlatformContract.SDK_BOUNDARY_DETAIL),
                    "sdk-boundaries",
                    "run",
                    "--json"),
            localPayload(
                    "readiness-profiles-json.golden",
                    descriptor(AndalusPlatformContract.SCHEMA, AndalusPlatformContract.READINESS_PROFILE_LIST),
                    "readiness-profiles",
                    "--json"),
            localPayload(
                    "readiness-profiles-inspect-json.golden",
                    descriptor(AndalusPlatformContract.SCHEMA, AndalusPlatformContract.READINESS_PROFILE_DETAIL),
                    "readiness-profiles",
                    "inspect",
                    "minimal",
                    "--json"),
            localPayload(
                    "readiness-profiles-check-json.golden",
                    descriptor(AndalusPlatformContract.SCHEMA, AndalusPlatformContract.READINESS_PROFILE_VALIDATION),
                    "readiness-profiles",
                    "--check",
                    "--json"),
            localPayload(
                    "readiness-profiles-policies-json.golden",
                    descriptor(
                            AndalusPlatformContract.SCHEMA,
                            AndalusPlatformContract.READINESS_PROFILE_VALIDATION_POLICY_LIST),
                    "readiness-profiles",
                    "policies",
                    "--json"),
            localPayload(
                    "readiness-profiles-config-json.golden",
                    descriptor(
                            AndalusPlatformContract.SCHEMA,
                            AndalusPlatformContract.READINESS_PROFILE_REGISTRY_CONFIG_DIAGNOSTICS),
                    "readiness-profiles",
                    "config",
                    "--json"),
            localPayload(
                    "readiness-profiles-sources-json.golden",
                    descriptor(
                            AndalusPlatformContract.SCHEMA,
                            AndalusPlatformContract.READINESS_PROFILE_REGISTRY_RESOLUTION),
                    "readiness-profiles",
                    "sources",
                    "--json"),
            localPayload(
                    "commands-index-json.golden",
                    descriptor(AndalusCommandDiscoveryContract.SCHEMA, AndalusCommandDiscoveryContract.COMMANDS_DISCOVERY),
                    "commands",
                    "--surface",
                    "assistant-agent",
                    "--category",
                    "Runs",
                    "--index",
                    "--json"),
            localPayload(
                    "commands-detail-json.golden",
                    descriptor(AndalusCommandDiscoveryContract.SCHEMA, AndalusCommandDiscoveryContract.COMMANDS_DISCOVERY),
                    List.of("commands-id-json"),
                    "commands",
                    "--id",
                    "run-print-spec-output",
                    "--json"),
            localPayload(
                    "commands-surface-json.golden",
                    descriptor(AndalusCommandDiscoveryContract.SCHEMA, AndalusCommandDiscoveryContract.COMMANDS_DISCOVERY),
                    "commands",
                    "--surface",
                    "assistant-agent",
                    "--json"),
            localPayload(
                    "commands-profile-json.golden",
                    descriptor(AndalusCommandDiscoveryContract.SCHEMA, AndalusCommandDiscoveryContract.COMMANDS_DISCOVERY),
                    "commands",
                    "--profile",
                    "assistant-agent",
                    "--json"),
            localPayload(
                    "commands-contract-json-schema-id-json.golden",
                    descriptor(AndalusCommandDiscoveryContract.SCHEMA, AndalusCommandDiscoveryContract.COMMANDS_DISCOVERY),
                    "commands",
                    "--contract-json-schema-id",
                    jsonSchemaId(AgentRunLifecycleContract.SCHEMA, AgentRunLifecycleContract.RUN_RESULT),
                    "--json"),
            localPayload(
                    "workbench-surface-json.golden",
                    descriptor(AndalusWorkbenchContract.SCHEMA, AndalusWorkbenchContract.WORKBENCH_DISCOVERY),
                    "workbench",
                    "--surface",
                    "assistant-agent",
                    "--json"),
            localPayload(
                    "workbench-profile-json.golden",
                    descriptor(AndalusWorkbenchContract.SCHEMA, AndalusWorkbenchContract.WORKBENCH_DISCOVERY),
                    "workbench",
                    "--profile",
                    "assistant-agent",
                    "--json"),
            localPayload(
                    "workbench-command-json.golden",
                    descriptor(AndalusWorkbenchContract.SCHEMA, AndalusWorkbenchContract.WORKBENCH_DISCOVERY),
                    "workbench",
                    "--surface",
                    "assistant-agent",
                    "--category",
                    "Runs",
                    "--id",
                    "run-session-context",
                    "--json"),
            localPayload(
                    "workbench-contract-json-schema-id-json.golden",
                    descriptor(AndalusWorkbenchContract.SCHEMA, AndalusWorkbenchContract.WORKBENCH_DISCOVERY),
                    "workbench",
                    "--contract-json-schema-id",
                    jsonSchemaId(AgentRunLifecycleContract.SCHEMA, AgentRunLifecycleContract.RUN_RESULT),
                    "--json"),
            localPayload(
                    "skills-list-json.golden",
                    descriptor(AndalusSkillContract.SCHEMA, AndalusSkillContract.SKILL_DISCOVERY),
                    "skills",
                    "list",
                    "--surface",
                    "assistant-agent",
                    "--source",
                    "rag",
                    "--json"),
            localPayload(
                    "skills-list-profile-json.golden",
                    descriptor(AndalusSkillContract.SCHEMA, AndalusSkillContract.SKILL_DISCOVERY),
                    "skills",
                    "list",
                    "--profile",
                    "assistant-agent",
                    "--json"),
            localPayload(
                    "skills-inspect-json.golden",
                    descriptor(AndalusSkillContract.SCHEMA, AndalusSkillContract.SKILL_DETAIL),
                    "skills",
                    "inspect",
                    "rag",
                    "--json"),
            localPayload(
                    "skills-search-json.golden",
                    descriptor(AndalusSkillContract.SCHEMA, AndalusSkillContract.SKILL_DISCOVERY),
                    "skills",
                    "search",
                    "rag",
                    "--surface",
                    "assistant-agent",
                    "--json"),
            localPayload(
                    "skills-search-profile-json.golden",
                    descriptor(AndalusSkillContract.SCHEMA, AndalusSkillContract.SKILL_DISCOVERY),
                    "skills",
                    "search",
                    "gamelan",
                    "--profile",
                    "workflow-agent",
                    "--json"),
            localPayload(
                    "providers-json.golden",
                    descriptor(
                            AndalusProviderCapabilityContract.SCHEMA,
                            AndalusProviderCapabilityContract.PROVIDER_CAPABILITY_DISCOVERY),
                    "providers",
                    "--json"),
            localPayload(
                    "providers-list-json.golden",
                    descriptor(
                            AndalusProviderCapabilityContract.SCHEMA,
                            AndalusProviderCapabilityContract.PROVIDER_CAPABILITY_DISCOVERY),
                    "providers",
                    "list",
                    "--module",
                    "a2ui",
                    "--json"),
            localPayload(
                    "providers-search-json.golden",
                    descriptor(
                            AndalusProviderCapabilityContract.SCHEMA,
                            AndalusProviderCapabilityContract.PROVIDER_CAPABILITY_DISCOVERY),
                    "providers",
                    "search",
                    "lifecycle",
                    "--surface",
                    "coding-agent",
                    "--json"),
            localPayload(
                    "providers-inspect-json.golden",
                    descriptor(
                            AndalusProviderCapabilityContract.SCHEMA,
                            AndalusProviderCapabilityContract.PROVIDER_CAPABILITY_DETAIL),
                    "providers",
                    "inspect",
                    "storage.hybrid-persistence",
                    "--json"),
            localUnvalidated("contracts-json.golden", "contracts", "--json"),
            localUnvalidated("contracts-index-json.golden", "contracts", "--domain", "planning", "--index", "--json"),
            localUnvalidated(
                    "contract-run-preview-schema-json.golden",
                    "contracts",
                    "--envelope",
                    "run-preview",
                    "--schema-json"),
            localUnvalidated(
                    "contract-planning-schema-bundle-json.golden",
                    "contracts",
                    "--domain",
                    "planning",
                    "--schema-bundle-json"),
            localUnvalidated(
                    "contract-provider-schema-bundle-json.golden",
                    "contracts",
                    "--domain",
                    "providers",
                    "--schema-bundle-json"),
            localUnvalidated("contracts-check-json.golden", "contracts", "--check", "--json"),
            localPayload(
                    "contracts-coverage-json.golden",
                    descriptor(
                            AndalusContractCoverageContract.SCHEMA,
                            AndalusContractCoverageContract.CONTRACT_COMMAND_COVERAGE),
                    "contracts",
                    "--coverage",
                    "--json"),
            localPayload(
                    "standards-health-json.golden",
                    descriptor(
                            AndalusStandardAlignmentContract.SCHEMA,
                            AndalusStandardAlignmentContract.STANDARD_ALIGNMENT_HEALTH),
                    "standards",
                    "--json"),
            localPayload(
                    "standards-catalog-json.golden",
                    descriptor(AndalusStandardCatalogContract.SCHEMA, AndalusStandardCatalogContract.STANDARDS_CATALOG),
                    "standards",
                    "--catalog",
                    "--json"));

    private AndalusCliCatalogGoldenFixtures() {
    }

    static List<AndalusCliGoldenFixtures.GoldenFixture> all() {
        return ALL;
    }

    private static AndalusCliGoldenFixtures.GoldenFixture localPayload(
            String name,
            AndalusContractDescriptor descriptor,
            String... args) {
        return localPayload(name, descriptor, null, args);
    }

    private static AndalusCliGoldenFixtures.GoldenFixture localPayload(
            String name,
            AndalusContractDescriptor descriptor,
            List<String> commandIds,
            String... args) {
        if (commandIds == null) {
            return new AndalusCliGoldenFixtures.GoldenFixture(
                    name,
                    "local",
                    LOCAL_SDK,
                    List.of(args),
                    0,
                    AndalusCliGoldenFixtures.SchemaMode.EXPLICIT,
                    descriptor);
        }
        return new AndalusCliGoldenFixtures.GoldenFixture(
                name,
                "local",
                LOCAL_SDK,
                List.of(args),
                commandIds,
                0,
                AndalusCliGoldenFixtures.SchemaMode.EXPLICIT,
                descriptor);
    }

    private static AndalusCliGoldenFixtures.GoldenFixture localUnvalidated(String name, String... args) {
        return new AndalusCliGoldenFixtures.GoldenFixture(
                name,
                "local",
                LOCAL_SDK,
                List.of(args),
                0,
                AndalusCliGoldenFixtures.SchemaMode.NONE,
                null);
    }

    private static AndalusContractDescriptor descriptor(String schema, String envelope) {
        AndalusContractDiscovery discovery = AndalusContractCatalog.discover(AndalusContractQuery.of(schema, envelope));
        if (discovery.contracts().size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one contract descriptor for " + schema + "/" + envelope
                            + " but found " + discovery.contracts().size());
        }
        return discovery.contracts().get(0);
    }

    private static String jsonSchemaId(String schema, String envelope) {
        return descriptor(schema, envelope).jsonSchemaId();
    }
}
