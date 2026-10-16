package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tech.kayys.andalus.cli.AndalusAqliCli;
import tech.kayys.andalus.agent.run.AgentRunHandle;
import tech.kayys.andalus.agent.run.AgentRunState;
import tech.kayys.andalus.agent.run.AgentRunStatus;
import tech.kayys.andalus.agent.store.AgentRunStore;
import tech.kayys.andalus.client.LocalAndalusAqliSdk;
import tech.kayys.andalus.client.AndalusAqliSdk;
import tech.kayys.andalus.client.AndalusAqliSdkConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AndalusAqliCliTest {

    @Test
    void statusShowsProductBoundary() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "status");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus")
                .contains("Andalus is the agentic platform")
                .contains("Aqli")
                .contains("Gamelan");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void statusCanRenderCompactJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "status",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("\"product\":\"Andalus\"")
                .contains("\"aqli\":\"external\"");
    }

    @Test
    void statusCanRenderReadinessJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "status",
                "--readiness",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"readinessId\":\"andalus.platform.readiness\"")
                .contains("\"ready\":true")
                .contains("\"readinessProfileId\":\"default\"")
                .contains("\"componentReadinessIds\":[")
                .contains("\"andalus.storage.readiness\"")
                .contains("\"andalus.contract.integrity.readiness\"")
                .contains("\"andalus.contract.coverage.readiness\"")
                .contains("\"andalus.skill-catalog.readiness\"")
                .contains("\"andalus.provider-capability.readiness\"")
                .contains("\"andalus.standard-alignment.readiness\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void statusCanRenderReadinessProfileJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "status",
                "--readiness-profile",
                "minimal",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"readinessProfileId\":\"minimal\"")
                .contains("\"readinessProfileComponentIds\":[\"andalus.storage.readiness\",\"andalus.contract.integrity.readiness\"]")
                .contains("\"componentCount\":2")
                .contains("\"componentReadinessIds\":[\"andalus.storage.readiness\",\"andalus.contract.integrity.readiness\"]")
                .doesNotContain("\"andalus.contract.coverage.readiness\"")
                .doesNotContain("\"andalus.skill-catalog.readiness\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void statusCanRenderReadinessText() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "status",
                "--readiness");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus readiness")
                .contains("readinessId: andalus.platform.readiness")
                .contains("ready: yes")
                .contains("profile: default")
                .contains("Components:")
                .contains("andalus.storage.readiness")
                .contains("andalus.skill-catalog.readiness")
                .contains("andalus.provider-capability.readiness")
                .contains("andalus.contract.coverage.readiness");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void statusReadinessProfileImpliesReadinessText() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "status",
                "--readiness-profile",
                "contracts");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus readiness")
                .contains("profile: contracts")
                .contains("andalus.contract.integrity.readiness")
                .contains("andalus.contract.coverage.readiness")
                .contains("andalus.standard-alignment.readiness")
                .doesNotContain("Aqli");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void statusRejectsUnknownReadinessProfile() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "status",
                "--readiness-profile",
                "future",
                "--json");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err())
                .contains("Unknown platform readiness profile 'future'.")
                .contains("Available profiles: default, production, minimal, contracts, catalogs.");
    }

    @Test
    void readinessProfilesCanRenderText() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "readiness-profiles");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus readiness profiles")
                .contains("totalProfiles: 5")
                .contains("default")
                .contains("production")
                .contains("minimal")
                .contains("components: 2")
                .contains("andalus.storage.readiness")
                .contains("andalus.contract.integrity.readiness");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void readinessProfilesCanRenderJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "readiness-profiles",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"totalProfiles\":5")
                .contains("\"defaultProfileId\":\"default\"")
                .contains("\"productionProfileId\":\"production\"")
                .contains("\"profileIds\":[\"default\",\"production\",\"minimal\",\"contracts\",\"catalogs\"]")
                .contains("\"profileId\":\"minimal\"")
                .contains("\"componentCount\":2")
                .contains("\"readinessIds\":[\"andalus.storage.readiness\",\"andalus.contract.integrity.readiness\"]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void readinessProfilesCanValidateProfilesText() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "readiness-profiles",
                "--check");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus readiness profile validation")
                .contains("valid: true")
                .contains("issues: 0")
                .contains("profiles: 5")
                .contains("profileIds: default, production, minimal, contracts, catalogs")
                .contains("validationPolicy: policyId=strict, strict=true, knownReadinessCount=6")
                .contains("requireDefaultProfile=true")
                .contains("requireProductionProfile=true")
                .contains("requireFullReadinessCoverage=true")
                .contains("defaultProfiles: 1 [default]")
                .contains("productionProfiles: 1 [production]")
                .contains("knownReadinessIds:")
                .contains("coveredReadinessIds:")
                .contains("uncoveredReadinessIds:");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void readinessProfilesCanValidateProfilesJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "readiness-profiles",
                "--check",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"valid\":true")
                .contains("\"issueCount\":0")
                .contains("\"totalProfiles\":5")
                .contains("\"profileIds\":[\"default\",\"production\",\"minimal\",\"contracts\",\"catalogs\"]")
                .contains("\"validationPolicy\":{\"policyId\":\"strict\",\"strict\":true,\"knownReadinessCount\":6")
                .contains("\"requireDefaultProfile\":true")
                .contains("\"requireProductionProfile\":true")
                .contains("\"requireFullReadinessCoverage\":true")
                .contains("\"defaultProfileCount\":1")
                .contains("\"defaultProfileIds\":[\"default\"]")
                .contains("\"productionProfileCount\":1")
                .contains("\"productionProfileIds\":[\"production\"]")
                .contains("\"knownReadinessIds\":[")
                .contains("\"coveredReadinessCount\":6")
                .contains("\"coveredReadinessIds\":[")
                .contains("\"uncoveredReadinessCount\":0")
                .contains("\"uncoveredReadinessIds\":[]")
                .contains("\"issues\":[]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void readinessProfilesCanValidateProfilesWithSelectedPolicy() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "readiness-profiles",
                "--check",
                "--validation-policy",
                "relaxed",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"valid\":true")
                .contains("\"validationPolicy\":{\"policyId\":\"relaxed\",\"strict\":false,\"knownReadinessCount\":6")
                .contains("\"requireDefaultProfile\":false")
                .contains("\"requireProductionProfile\":false")
                .contains("\"requireFullReadinessCoverage\":false")
                .contains("\"issues\":[]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void readinessProfilesCheckRejectsUnknownValidationPolicy() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "readiness-profiles",
                "--check",
                "--validation-policy",
                "future",
                "--json");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err())
                .contains("Unknown platform readiness profile validation policy 'future'.")
                .contains(
                        "Available policies: strict, relaxed, strict-without-profile-roles, "
                                + "strict-without-full-coverage, relaxed-with-full-coverage.");
    }

    @Test
    void readinessProfilesCanListValidationPoliciesText() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "readiness-profiles",
                "policies");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus readiness profile validation policies")
                .contains("totalPolicies: 5")
                .contains("strict")
                .contains("  default: yes")
                .contains("relaxed")
                .contains("strict-without-profile-roles")
                .contains("strict-without-full-coverage")
                .contains("relaxed-with-full-coverage");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void readinessProfilesCanListValidationPoliciesJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "readiness-profiles",
                "policies",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"totalPolicies\":5")
                .contains("\"defaultPolicyId\":\"strict\"")
                .contains("\"policyIds\":[\"strict\",\"relaxed\",\"strict-without-profile-roles\"")
                .contains("\"policyId\":\"strict\"")
                .contains("\"defaultPolicy\":true")
                .contains("\"requireFullReadinessCoverage\":true")
                .contains("\"policyId\":\"relaxed\"")
                .contains("\"strict\":false");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void readinessProfilesCanInspectRegistryConfigText() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "readiness-profiles",
                "config");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus readiness profile registry config")
                .contains("valid: true")
                .contains("issues: 0")
                .contains("mode: builtin")
                .contains("fallbackToBuiltIn: no")
                .contains("validationPolicy: strict");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void readinessProfilesCanInspectInvalidRegistryConfigJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                null,
                console.outStream(),
                console.errStream(),
                "--readiness-profile-validation-policy",
                "future",
                "readiness-profiles",
                "config",
                "--json");

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .startsWith("{")
                .contains("\"product\":\"Andalus\"")
                .contains("\"valid\":false")
                .contains("\"issueCount\":1")
                .contains("\"validationPolicyId\":\"future\"")
                .contains("\"code\":\"readiness_profile_validation_policy_unknown\"")
                .contains("\"field\":\"validationPolicyId\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void readinessProfilesCanInspectRegistrySourcesText() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "readiness-profiles",
                "sources");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus readiness profile sources")
                .contains("valid: true")
                .contains("activeSource: builtin (builtin)")
                .contains("fallbackUsed: no")
                .contains("sources: 1")
                .contains("profiles: 5")
                .contains("profileIds: default, production, minimal, contracts, catalogs")
                .contains("validationPolicy: strict")
                .contains("selected: yes")
                .contains("available: yes");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void readinessProfilesCanInspectRegistrySourcesJsonWithFileFallback(@TempDir Path tempDir) {
        TestConsole console = new TestConsole();
        Path missing = tempDir.resolve("missing-readiness-profiles.properties");

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "readiness-profiles",
                "sources",
                "--file",
                missing.toString(),
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"valid\":true")
                .contains("\"activeSourceId\":\"builtin\"")
                .contains("\"activeSourceType\":\"builtin\"")
                .contains("\"fallbackUsed\":true")
                .contains("\"sourceCount\":2")
                .contains("\"sourceId\":\"file:" + missing)
                .contains("\"sourceType\":\"file\"")
                .contains("\"selected\":false")
                .contains("\"available\":false")
                .contains("\"message\":\"Readiness profile file does not exist:")
                .contains("\"sourceId\":\"builtin\"")
                .contains("\"selected\":true")
                .contains("\"totalProfiles\":5")
                .contains("\"validation\":{\"product\":\"Andalus\",\"valid\":true");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void readinessProfilesCanInspectProfileText() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "readiness-profiles",
                "inspect",
                "contracts");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus readiness profile")
                .contains("contracts")
                .contains("components: 3")
                .contains("andalus.contract.integrity.readiness")
                .contains("andalus.contract.coverage.readiness")
                .contains("andalus.standard-alignment.readiness")
                .doesNotContain("totalProfiles:");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void readinessProfilesCanInspectProfileJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "readiness-profiles",
                "inspect",
                "minimal",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"profileId\":\"minimal\"")
                .contains("\"profile\":{\"profileId\":\"minimal\"")
                .contains("\"componentCount\":2")
                .contains("\"readinessIds\":[\"andalus.storage.readiness\",\"andalus.contract.integrity.readiness\"]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void readinessProfilesInspectRejectsUnknownProfile() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "readiness-profiles",
                "inspect",
                "future",
                "--json");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err())
                .contains("Unknown platform readiness profile 'future'.")
                .contains("Available profiles: default, production, minimal, contracts, catalogs.");
    }

    @Test
    void runCommandFormatsPreparedAgentRun() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "draft a workflow",
                "--tenant",
                "tenant-a",
                "--model",
                "qwen",
                "--workflow",
                "planner",
                "--surface",
                "assistant-agent",
                "--skill",
                "rag",
                "--max-steps",
                "3");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus run")
                .contains("draft a workflow")
                .contains("state: completed")
                .contains("outcome: terminal")
                .contains("andalus-agent-over-aqli")
                .contains("tenant-a")
                .contains("qwen")
                .contains("planner")
                .contains("readiness: yes")
                .contains("surface ready: yes")
                .contains("skills ready: yes")
                .contains("surface=assistant-agent");
    }

    @Test
    void runCommandTextSurfacesReadinessProblems() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "answer with docs",
                "--surface",
                "assistant-agent",
                "--skill",
                "repo",
                "--skill",
                "missing");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("readiness: no")
                .contains("surface ready: yes")
                .contains("skills ready: no")
                .contains("unknown skills:")
                .contains("- missing")
                .contains("surface-incompatible skills:")
                .contains("- repo.context")
                .contains("skill recommendations:")
                .contains("Register or remove unknown skills: missing.")
                .contains("Choose skills that support surface 'assistant-agent': repo.context.");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandRequireReadyBlocksUnreadyRuns() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "answer with docs",
                "--surface",
                "assistant-agent",
                "--skill",
                "repo",
                "--skill",
                "missing",
                "--require-ready");

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .contains("Andalus run preflight")
                .contains("ready: no")
                .contains("surface ready: yes")
                .contains("skills ready: no")
                .contains("unknown skills:")
                .contains("- missing")
                .contains("surface-incompatible skills:")
                .contains("- repo.context")
                .doesNotContain("strategy:")
                .doesNotContain("Prepared Andalus agent run");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandRequireReadyAllowsReadyRuns() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "answer with docs",
                "--surface",
                "assistant-agent",
                "--skill",
                "rag",
                "--require-ready");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus run")
                .contains("Prepared Andalus agent run")
                .contains("readiness: yes")
                .contains("skills ready: yes");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandRequireReadyBlocksUnreadyRunsAsJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "answer with docs",
                "--surface",
                "assistant-agent",
                "--skill",
                "missing",
                "--require-ready",
                "--json");

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .startsWith("{")
                .contains("\"ready\":false")
                .contains("\"skillAssessment\":")
                .contains("\"unknownSkills\":[\"missing\"]")
                .doesNotContain("\"runId\":")
                .doesNotContain("\"successful\":true");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandRequireReadyCanComeFromSpec(@TempDir Path workspace) throws Exception {
        Path spec = workspace.resolve("run.properties");
        Files.writeString(spec, """
                prompt=answer with docs
                surfaceId=assistant-agent
                skills=repo,missing
                requireReady=true
                """);
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "--spec",
                spec.toString());

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .contains("Andalus run preflight")
                .contains("ready: no")
                .contains("unknown skills:")
                .contains("- missing")
                .doesNotContain("Prepared Andalus agent run");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandPrintSpecIncludesRequireReadyWhenEnabled() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "answer with docs",
                "--surface",
                "assistant-agent",
                "--skill",
                "rag",
                "--require-ready",
                "--print-spec");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("specVersion=1")
                .contains("prompt=answer with docs")
                .contains("surfaceId=assistant-agent")
                .contains("skills=rag")
                .contains("requireReady=true");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandCanPreflightProductProfile() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "--profile",
                "low-code-agent",
                "--preflight");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus run preflight")
                .contains("surface: workflow-platform")
                .contains("ready: yes")
                .contains("- workflowId")
                .contains("requested skills:")
                .contains("- workflow")
                .contains("- hitl")
                .contains("- observability");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void productsCommandListsReusableAgentEngineSurfaces() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "products");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Coding Agent")
                .contains("Assistant Agent")
                .contains("Low-Code Agentic Workflow")
                .contains("Gemini CLI")
                .contains("Claude Code")
                .contains("n8n-style")
                .contains("policy: memory, workspace, harness")
                .contains("routing: inspect-workspace, plan-harness, prefer-tool-use")
                .contains("Andalus product profiles")
                .contains("OpenClaw Coding Agent")
                .contains("Low-Code Agentic Workflow")
                .contains("profiles: coding-agent, openclaw-agent");
    }

    @Test
    void productsCommandCanRenderJsonCatalog() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "products",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"product\":\"Andalus\"")
                .contains("\"surfaces\":[")
                .contains("\"id\":\"coding-agent\"")
                .contains("\"policy\":")
                .contains("\"profiles\":[\"coding-agent\",\"openclaw-agent\"]")
                .contains("\"profiles\":[")
                .contains("\"id\":\"openclaw-agent\"")
                .contains("\"surfaceId\":\"coding-agent\"")
                .contains("\"requireReady\":true")
                .contains("\"workspacePreferred\":true")
                .contains("\"routingHints\":[\"inspect-workspace\",\"plan-harness\",\"prefer-tool-use\"]")
                .contains("\"id\":\"assistant-agent\"")
                .contains("\"suggestedSkills\":[\"memory\",\"rag\",\"mcp\"]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void profilesCommandListsReusableProductProfiles() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "profiles");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus product profiles")
                .contains("totalProfiles: 6")
                .contains("OpenClaw Coding Agent")
                .contains("openclaw-agent")
                .contains("surface: coding-agent")
                .contains("defaults: memory, workspace, harness, require-ready, max-steps=16");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void profilesCommandFiltersBySurfaceAsJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "profiles",
                "--surface",
                "workflow-platform",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"surfaceId\":\"workflow-platform\"")
                .contains("\"totalProfiles\":2")
                .contains("\"id\":\"workflow-agent\"")
                .contains("\"id\":\"low-code-agent\"")
                .doesNotContain("\"id\":\"openclaw-agent\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void profilesCommandInspectsOneProfile() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "profiles",
                "inspect",
                "low-code-agent");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus product profile")
                .contains("Low-Code Agentic Workflow")
                .contains("workflow: gamelan-low-code-workflow")
                .contains("skills: workflow, hitl, observability")
                .contains("context: andalus.profile=low-code-agent, andalus.surface=workflow-platform");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void workbenchCommandRendersHeadlessAgentWorkbench() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "workbench");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus Workbench")
                .contains("Command Palette")
                .contains("Next Actions")
                .contains("coding-agent")
                .contains("Tamboui");
    }

    @Test
    void workbenchCommandCanRenderJsonModel() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "workbench",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"product\":\"Andalus\"")
                .contains("\"status\":")
                .contains("\"catalog\":")
                .contains("\"surfaces\":[")
                .contains("\"id\":\"coding-agent\"")
                .contains("\"policy\":")
                .contains("\"commandQuery\":")
                .contains("\"filtered\":false")
                .contains("\"commandPalette\":")
                .contains("\"commands\":[")
                .contains("\"id\":\"run-print-spec-output\"")
                .contains("\"category\":\"Run Specs\"")
                .contains("\"nextActions\":")
                .contains("Tamboui");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void workbenchCommandCanFilterCommandsBySurface() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "workbench",
                "--surface",
                "assistant-agent",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("\"commandQuery\":{\"surfaceId\":\"assistant-agent\"")
                .contains("\"profileId\":null")
                .contains("\"resolvedSurfaceId\":\"assistant-agent\"")
                .contains("\"filtered\":true")
                .contains("\"id\":\"run-assistant-surface\"")
                .contains("\"id\":\"run-session-context\"")
                .contains("\"id\":\"tui\"")
                .doesNotContain("\"id\":\"workspace-inspect\"")
                .doesNotContain("\"id\":\"harness-plan\"")
                .doesNotContain("\"id\":\"run-workflow-skill\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void workbenchCommandCanFilterCommandsByProfile() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "workbench",
                "--profile",
                "low-code-agent",
                "--category",
                "Runs",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("\"commandQuery\":{\"surfaceId\":null,\"profileId\":\"low-code-agent\",\"resolvedSurfaceId\":\"workflow-platform\",\"category\":\"Runs\"")
                .contains("\"filtered\":true")
                .contains("\"id\":\"run-profile\"")
                .contains("\"id\":\"run-workflow-skill\"")
                .doesNotContain("\"id\":\"run-assistant-surface\"")
                .doesNotContain("\"id\":\"workspace-inspect\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void workbenchCommandCanFilterCommandsByCategoryAndId() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "workbench",
                "--surface",
                "assistant-agent",
                "--category",
                "Runs",
                "--id",
                "run-session-context",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("\"commandQuery\":{\"surfaceId\":\"assistant-agent\",\"profileId\":null,\"resolvedSurfaceId\":\"assistant-agent\",\"category\":\"Runs\",\"commandId\":\"run-session-context\",\"contractJsonSchemaId\":null,\"filtered\":true}")
                .contains("\"commandPalette\":[\"run <task> --session <id> --user <id> --context rag.collection=<name>\"]")
                .contains("\"id\":\"run-session-context\"")
                .doesNotContain("\"id\":\"run-assistant-surface\"")
                .doesNotContain("\"id\":\"run-print-spec-output\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void workbenchCommandCanFilterCommandsByContractJsonSchemaId() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "workbench",
                "--contract-json-schema-id",
                "urn:andalus:contract:andalus.run.planning:v1:run-preview",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("\"commandQuery\":{\"surfaceId\":null,\"profileId\":null,\"resolvedSurfaceId\":null,\"category\":null,\"commandId\":null,\"contractJsonSchemaId\":\"urn:andalus:contract:andalus.run.planning:v1:run-preview\",\"filtered\":true}")
                .contains("\"commandPalette\":[\"run <task> --dry-run --json\",\"run --spec <file> --dry-run --json\"]")
                .contains("\"id\":\"run-dry-json\"")
                .contains("\"id\":\"run-spec-dry-json\"")
                .contains("\"jsonSchemaId\":\"urn:andalus:contract:andalus.run.planning:v1:run-preview\"")
                .doesNotContain("\"id\":\"run-preflight-json\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void workbenchCommandCombinesFiltersBeforeCommandIdLookup() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "workbench",
                "--surface",
                "assistant-agent",
                "--id",
                "workspace-inspect");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err())
                .contains("Unknown Andalus command id 'workspace-inspect'")
                .contains("run-assistant-surface")
                .doesNotContain("workspace-inspect,");
    }

    @Test
    void workbenchCommandRejectsUnknownSurfaceFilter() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "workbench",
                "--surface",
                "future-agent");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err())
                .contains("Unknown Andalus product surface 'future-agent'")
                .contains("coding-agent")
                .contains("assistant-agent");
    }

    @Test
    void commandsCommandRendersCommandCatalog() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus commands")
                .contains("Platform")
                .contains("Run Specs")
                .contains("status-readiness-json: status --readiness --json")
                .contains("status-readiness-profile-json: status --readiness-profile <profile-id> --json")
                .contains("contracts: andalus.readiness/readiness-aggregate")
                .contains("readiness-profiles-json: readiness-profiles --json")
                .contains("readiness-profiles-inspect-json: readiness-profiles inspect <profile-id> --json")
                .contains("readiness-profiles-check-json: readiness-profiles --check --json")
                .contains("readiness-profiles-policies-json: readiness-profiles policies --json")
                .contains("readiness-profiles-config-json: readiness-profiles config --json")
                .contains("readiness-profiles-sources-json: readiness-profiles sources --json")
                .contains("profiles-list: profiles")
                .contains("profiles-json: profiles --json")
                .contains("profiles-surface-json: profiles --surface <surface-id> --json")
                .contains("profiles-inspect-json: profiles inspect <profile-id> --json")
                .contains("spec-template-profile: spec template --profile openclaw-agent")
                .contains("run-print-spec-output: run <task> --print-spec --output <file>")
                .contains("run-profile: run <task> --profile <profile-id>")
                .contains("run-profile-print-spec: run <task> --profile <profile-id> --print-spec")
                .contains("workbench-profile-json: workbench --profile <profile-id> --json")
                .contains("commands-surface-json: commands --surface <surface-id> --json")
                .contains("commands-profile-json: commands --profile <profile-id> --json")
                .contains("workbench-command-json: workbench --surface assistant-agent --category Runs --id run-session-context --json")
                .contains("workbench-contract-json-schema-id-json: workbench --contract-json-schema-id <schema-id> --json")
                .contains("commands-index-json: commands --index --json")
                .contains("commands-category: commands --category \"Run Specs\"")
                .contains("commands-id-json: commands --id run-print-spec-output --json")
                .contains("commands-contract-json-schema-id-json: commands --contract-json-schema-id <schema-id> --json")
                .contains("contracts-json: contracts --json")
                .contains("contracts-index-json: contracts --index --json")
                .contains("contracts-envelope-schema-json: contracts --envelope <envelope> --schema-json")
                .contains("contracts-schema-bundle-json: contracts --schema-bundle-json")
                .contains("contracts-check-json: contracts --check --json")
                .contains("contracts-coverage-json: contracts --coverage --json")
                .contains("contracts-command-json: contracts --command-id <command-id> --json")
                .contains("contracts-json-schema-id-json: contracts --json-schema-id <schema-id> --json")
                .contains("contracts-domain-json: contracts --domain <domain> --json")
                .contains("contracts-schema-json: contracts --schema <schema-id> --json")
                .contains("contracts-envelope-json: contracts --envelope <envelope> --json")
                .contains("standards-health-json: standards --json")
                .contains("contracts: andalus.standard.alignment/standard-alignment-health")
                .contains("standards-catalog-json: standards --catalog --json")
                .contains("contracts: andalus.standard.catalog/standards-catalog")
                .contains("run-result-json: run <task> --json")
                .contains("contracts: andalus.run.lifecycle/run-result")
                .contains("run-preflight-json: run <task> --preflight --json")
                .contains("contracts: andalus.run.planning/run-preflight")
                .contains("run-status-json: run status <run-id> --json")
                .contains("contracts: andalus.run.lifecycle/run-status")
                .contains("run-inspect-json: run inspect <run-id> --json")
                .contains("run-events-json: run events <run-id> --json")
                .contains("run-events-filter-json: run events <run-id> --state completed --limit 20 --json")
                .contains("run-events-cursor-json: run events <run-id> --after-sequence 10 --limit 20 --json")
                .contains("run-events-follow-json: run events <run-id> --follow --json")
                .contains("run-events-follow-result-json: run events <run-id> --follow --follow-result --json")
                .contains("run-events-follow-result-only-json: run events <run-id> --follow --follow-result-only --json")
                .contains("run-events-follow-result-only-stats-json: run events <run-id> --follow --follow-result-only --stats --json")
                .contains("run-events-stats-json: run events <run-id> --stats --json")
                .contains("run-list-json: run list --state completed --limit 10 --json")
                .contains("run-list-page-json: run list --offset 10 --limit 10 --json")
                .contains("run-stats-json: run stats --state completed --json")
                .contains("run-list-filter-json: run list --tenant <id> --surface assistant-agent --json")
                .contains("run-list-profile-json: run list --profile <profile-id> --json")
                .contains("run-stats-profile-json: run stats --profile <profile-id> --json")
                .contains("run-wait-json: run wait <run-id> --timeout-seconds 30 --json")
                .contains("run-cancel-json: run cancel <run-id> --reason <text> --json")
                .contains("run-forget-json: run forget <run-id> --json")
                .contains("skills-list-json: skills list --surface assistant-agent --json")
                .contains("skills-list-profile-json: skills list --profile <profile-id> --json")
                .contains("skills-inspect-json: skills inspect rag.retrieve --json")
                .contains("skills-search-json: skills search rag --surface assistant-agent --json")
                .contains("skills-search-profile-json: skills search gamelan --profile <profile-id> --json")
                .contains("tui: tui")
                .contains("local only");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void commandsCommandCanRenderSurfaceJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--surface",
                "assistant-agent",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"surfaceId\":\"assistant-agent\"")
                .contains("\"profileId\":null")
                .contains("\"resolvedSurfaceId\":\"assistant-agent\"")
                .contains("\"query\":{\"surfaceId\":\"assistant-agent\",\"profileId\":null,\"resolvedSurfaceId\":\"assistant-agent\",\"category\":null,\"commandId\":null,\"contractJsonSchemaId\":null,\"filtered\":true}")
                .contains("\"totalCommands\":")
                .contains("\"matchingCommands\":")
                .contains("\"categories\":[")
                .contains("\"categoryCounts\":")
                .contains("\"categorySummaries\":[")
                .contains("\"commandIds\":[")
                .contains("\"id\":\"run-assistant-surface\"")
                .contains("\"id\":\"run-session-context\"")
                .contains("\"id\":\"tui\"")
                .doesNotContain("\"id\":\"workspace-inspect\"")
                .doesNotContain("\"id\":\"run-workflow-skill\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void contractsCommandListsJsonContractCatalog() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "contracts",
                "--schema",
                "andalus.run.planning",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"schema\":\"andalus.run.planning\"")
                .contains("\"matchingContracts\":2")
                .contains("\"domains\":[\"planning\"]")
                .contains("\"domainCounts\":{\"planning\":2}")
                .contains("\"schemaSummaries\":[{\"name\":\"andalus.run.planning\",\"count\":2")
                .contains("\"domainSummaries\":[{\"name\":\"planning\",\"count\":2")
                .contains("\"envelopeSummaries\":[{\"name\":\"run-preflight\",\"count\":1")
                .contains("\"jsonSchemaIds\":[\"urn:andalus:contract:andalus.run.planning:v1:run-preflight\",\"urn:andalus:contract:andalus.run.planning:v1:run-preview\"]")
                .contains("\"envelopes\":[\"run-preflight\",\"run-preview\"]")
                .contains("\"commandIds\":[\"run-dry-json\",\"run-spec-dry-json\"]")
                .contains("\"jsonSchemaId\":\"urn:andalus:contract:andalus.run.planning:v1:run-preview\"")
                .contains("\"commands\":[\"run <prompt> --dry-run --json\",\"run --spec <file> --dry-run --json\"]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void contractsCommandCanValidateCatalogJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "contracts",
                "--check",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"valid\":true")
                .contains("\"issueCount\":0")
                .contains("\"totalContracts\":40")
                .contains("\"totalCommands\":96")
                .contains("\"contractCommandLinks\":64")
                .contains("\"commandContractLinks\":64")
                .contains("\"issues\":[]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void contractsCommandCanRenderCoverageJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "contracts",
                "--coverage",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"totalContracts\":40")
                .contains("\"totalCommands\":96")
                .contains("\"commandLinkedContracts\":39")
                .contains("\"commandlessContracts\":1")
                .contains("\"incompleteContracts\":0")
                .contains("\"commandContractLinks\":64")
                .contains("\"commandlessEntries\":[{\"schema\":\"andalus.readiness\"")
                .contains("\"envelope\":\"readiness-report\"")
                .contains("\"linkedCommandIds\":[]")
                .contains("\"complete\":true")
                .doesNotContain("\"envelope\":\"readiness-aggregate\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void contractsCommandCanFilterByCommandId() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "contracts",
                "--command-id",
                "run-dry-json",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"commandId\":\"run-dry-json\"")
                .contains("\"matchingContracts\":1")
                .contains("\"domains\":[\"planning\"]")
                .contains("\"envelopes\":[\"run-preview\"]")
                .contains("\"commandIdCounts\":{\"run-dry-json\":1,\"run-spec-dry-json\":1}")
                .contains("\"commandIds\":[\"run-dry-json\",\"run-spec-dry-json\"]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void contractsCommandCanFilterByJsonSchemaId() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "contracts",
                "--json-schema-id",
                "urn:andalus:contract:andalus.run.planning:v1:run-preview",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"jsonSchemaId\":\"urn:andalus:contract:andalus.run.planning:v1:run-preview\"")
                .contains("\"query\":{\"schema\":null,\"envelope\":null,\"commandId\":null,\"domain\":null,\"jsonSchemaId\":\"urn:andalus:contract:andalus.run.planning:v1:run-preview\",\"filtered\":true}")
                .contains("\"matchingContracts\":1")
                .contains("\"schemas\":[\"andalus.run.planning\"]")
                .contains("\"domains\":[\"planning\"]")
                .contains("\"envelopes\":[\"run-preview\"]")
                .contains("\"commandIds\":[\"run-dry-json\",\"run-spec-dry-json\"]")
                .contains("\"commands\":[\"run <prompt> --dry-run --json\",\"run --spec <file> --dry-run --json\"]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void contractsCommandCanFilterByDomain() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "contracts",
                "--domain",
                "lifecycle",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"domain\":\"lifecycle\"")
                .contains("\"matchingContracts\":15")
                .contains("\"domains\":[\"lifecycle\"]")
                .contains("\"domainCounts\":{\"lifecycle\":15}")
                .contains("\"schemas\":[\"andalus.run.lifecycle\"]")
                .doesNotContain("\"andalus.run.planning\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void contractsCommandCanRenderIndexJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "contracts",
                "--domain",
                "planning",
                "--index",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"domain\":\"planning\"")
                .contains("\"query\":{\"schema\":null,\"envelope\":null,\"commandId\":null,\"domain\":\"planning\",\"jsonSchemaId\":null,\"filtered\":true}")
                .contains("\"matchingContracts\":2")
                .contains("\"schemas\":[\"andalus.run.planning\"]")
                .contains("\"schemaCounts\":{\"andalus.run.planning\":2}")
                .contains("\"domains\":[\"planning\"]")
                .contains("\"domainCounts\":{\"planning\":2}")
                .contains("\"schemaSummaries\":[{\"name\":\"andalus.run.planning\",\"count\":2")
                .contains("\"domainSummaries\":[{\"name\":\"planning\",\"count\":2")
                .contains("\"envelopeSummaries\":[{\"name\":\"run-preflight\",\"count\":1")
                .contains("\"jsonSchemaIds\":[\"urn:andalus:contract:andalus.run.planning:v1:run-preflight\",\"urn:andalus:contract:andalus.run.planning:v1:run-preview\"]")
                .contains("\"envelopes\":[\"run-preflight\",\"run-preview\"]")
                .contains("\"commandIds\":[\"run-preflight-json\",\"run-dry-json\",\"run-spec-dry-json\"]")
                .doesNotContain("\"contracts\":[")
                .doesNotContain("\"description\":");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void contractsCommandCanRenderSchemaJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "contracts",
                "--envelope",
                "run-preview",
                "--schema-json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"")
                .contains("\"$id\":\"urn:andalus:contract:andalus.run.planning:v1:run-preview\"")
                .contains("\"required\":[\"contract\",\"requestId\",\"tenantId\",\"modelId\",\"workflowId\",\"surfaceId\"")
                .contains("\"schema\":{\"const\":\"andalus.run.planning\"}")
                .contains("\"version\":{\"const\":1}")
                .contains("\"envelope\":{\"const\":\"run-preview\"}")
                .contains("\"promptCharacters\":{\"type\":\"integer\",\"minimum\":0}")
                .contains("\"context\":{\"type\":\"object\",\"additionalProperties\":true}")
                .contains("\"surfacePolicyAssessment\":{\"type\":\"object\",\"required\":[\"surfaceId\",\"ready\",\"satisfiedContextKeys\"")
                .contains("\"skillAssessment\":{\"type\":\"object\",\"required\":[\"surfaceId\",\"ready\",\"requestedSkills\"")
                .contains("\"x-andalus-domain\":\"planning\"")
                .contains("\"x-andalus-commandIds\":[\"run-dry-json\",\"run-spec-dry-json\"]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void contractsCommandCanRenderLifecycleSchemaJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "contracts",
                "--json-schema-id",
                "urn:andalus:contract:andalus.run.lifecycle:v1:run-events-follow",
                "--schema-json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"$id\":\"urn:andalus:contract:andalus.run.lifecycle:v1:run-events-follow\"")
                .contains("\"required\":[\"contract\",\"runId\",\"successful\",\"outcome\",\"terminal\"")
                .contains("\"schema\":{\"const\":\"andalus.run.lifecycle\"}")
                .contains("\"envelope\":{\"const\":\"run-events-follow\"}")
                .contains("\"terminalState\":{\"type\":[\"string\",\"null\"]}")
                .contains("\"initialQuery\":{\"type\":\"object\",\"required\":[\"state\",\"type\",\"afterSequence\",\"limit\",\"filtered\"]")
                .contains("\"lastEvents\":{\"oneOf\":[")
                .contains("\"envelope\":{\"const\":\"run-events\"}")
                .contains("\"envelope\":{\"const\":\"run-events-stats\"}")
                .contains("\"x-andalus-domain\":\"lifecycle\"")
                .contains("\"x-andalus-commandIds\":[\"run-events-follow-result-json\",\"run-events-follow-result-only-json\",\"run-events-follow-result-only-stats-json\"]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void contractsCommandCanRenderSchemaBundleJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "contracts",
                "--domain",
                "planning",
                "--schema-bundle-json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"domain\":\"planning\"")
                .contains("\"query\":{\"schema\":null,\"envelope\":null,\"commandId\":null,\"domain\":\"planning\",\"jsonSchemaId\":null,\"filtered\":true}")
                .contains("\"matchingContracts\":2")
                .contains("\"schemas\":[\"andalus.run.planning\"]")
                .contains("\"schemaCount\":2")
                .contains("\"schemaIds\":[\"urn:andalus:contract:andalus.run.planning:v1:run-preflight\",\"urn:andalus:contract:andalus.run.planning:v1:run-preview\"]")
                .contains("\"schemaDocumentsById\":{\"urn:andalus:contract:andalus.run.planning:v1:run-preflight\"")
                .contains("\"schemaDocuments\":[")
                .contains("\"jsonSchemaIds\":[\"urn:andalus:contract:andalus.run.planning:v1:run-preflight\",\"urn:andalus:contract:andalus.run.planning:v1:run-preview\"]")
                .contains("\"id\":\"urn:andalus:contract:andalus.run.planning:v1:run-preview\"")
                .contains("\"envelope\":\"run-preview\"")
                .contains("\"document\":{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"")
                .contains("\"x-andalus-envelope\":\"run-preview\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void contractsCommandRejectsCompetingSchemaExportModes() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "contracts",
                "--envelope",
                "run-preview",
                "--schema-json",
                "--schema-bundle-json");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err())
                .contains("Use only one of --schema-json or --schema-bundle-json");
    }

    @Test
    void contractsCommandRequiresOneMatchForSchemaJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "contracts",
                "--domain",
                "planning",
                "--schema-json");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err())
                .contains("--schema-json requires exactly one matching contract; found 2")
                .contains("--envelope")
                .contains("--json-schema-id");
    }

    @Test
    void commandsCommandRendersCommandContractLinks() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--id",
                "run-status-json",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"commandId\":\"run-status-json\"")
                .contains("\"categoryCounts\":{\"Runs\":1}")
                .contains("\"contracts\":[{\"schema\":\"andalus.run.lifecycle\",\"version\":1,\"envelope\":\"run-status\",\"jsonSchemaId\":\"urn:andalus:contract:andalus.run.lifecycle:v1:run-status\"}]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void commandsCommandCanFilterByContractJsonSchemaId() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--contract-json-schema-id",
                "urn:andalus:contract:andalus.run.planning:v1:run-preview",
                "--index",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"contractJsonSchemaId\":\"urn:andalus:contract:andalus.run.planning:v1:run-preview\"")
                .contains("\"query\":{\"surfaceId\":null,\"profileId\":null,\"resolvedSurfaceId\":null,\"category\":null,\"commandId\":null,\"contractJsonSchemaId\":\"urn:andalus:contract:andalus.run.planning:v1:run-preview\",\"filtered\":true}")
                .contains("\"matchingCommands\":2")
                .contains("\"categories\":[\"Runs\",\"Run Specs\"]")
                .contains("\"categoryCounts\":{\"Runs\":1,\"Run Specs\":1}")
                .contains("\"contractJsonSchemaIds\":[\"urn:andalus:contract:andalus.run.planning:v1:run-preview\"]")
                .contains("\"contractJsonSchemaIdCounts\":{\"urn:andalus:contract:andalus.run.planning:v1:run-preview\":2}")
                .contains("\"contractSummaries\":[{\"jsonSchemaId\":\"urn:andalus:contract:andalus.run.planning:v1:run-preview\",\"schema\":\"andalus.run.planning\",\"version\":1,\"envelope\":\"run-preview\",\"count\":2,\"commandIds\":[\"run-dry-json\",\"run-spec-dry-json\"]}]")
                .contains("\"commandIds\":[\"run-dry-json\",\"run-spec-dry-json\"]")
                .doesNotContain("\"commands\":[");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void commandsCommandCanFilterByCommandDiscoveryContractJsonSchemaId() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--contract-json-schema-id",
                "urn:andalus:contract:andalus.command.discovery:v1:commands-discovery",
                "--index",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"matchingCommands\":5")
                .contains("\"categories\":[\"Workbench\"]")
                .contains("\"contractJsonSchemaIds\":[\"urn:andalus:contract:andalus.command.discovery:v1:commands-discovery\"]")
                .contains("\"commandIds\":[\"commands-surface-json\",\"commands-profile-json\",\"commands-index-json\",\"commands-id-json\",\"commands-contract-json-schema-id-json\"]")
                .doesNotContain("\"commands\":[");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void commandsCommandCanFilterByWorkbenchContractJsonSchemaId() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--contract-json-schema-id",
                "urn:andalus:contract:andalus.workbench.discovery:v1:workbench-discovery",
                "--index",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"matchingCommands\":4")
                .contains("\"categories\":[\"Workbench\"]")
                .contains("\"contractJsonSchemaIds\":[\"urn:andalus:contract:andalus.workbench.discovery:v1:workbench-discovery\"]")
                .contains("\"commandIds\":[\"workbench-surface-json\",\"workbench-profile-json\",\"workbench-command-json\",\"workbench-contract-json-schema-id-json\"]")
                .doesNotContain("\"commands\":[");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void commandsCommandCanFilterByPlatformContractJsonSchemaId() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--contract-json-schema-id",
                "urn:andalus:contract:andalus.platform.catalog:v1:profile-list",
                "--index",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"matchingCommands\":2")
                .contains("\"categories\":[\"Platform\"]")
                .contains("\"contractJsonSchemaIds\":[\"urn:andalus:contract:andalus.platform.catalog:v1:profile-list\"]")
                .contains("\"commandIds\":[\"profiles-json\",\"profiles-surface-json\"]")
                .doesNotContain("\"commands\":[");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void commandsCommandCanFilterBySkillContractJsonSchemaId() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--contract-json-schema-id",
                "urn:andalus:contract:andalus.skill.catalog:v1:skill-discovery",
                "--index",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"matchingCommands\":4")
                .contains("\"categories\":[\"Skills\"]")
                .contains("\"contractJsonSchemaIds\":[\"urn:andalus:contract:andalus.skill.catalog:v1:skill-discovery\"]")
                .contains("\"commandIds\":[\"skills-list-json\",\"skills-list-profile-json\",\"skills-search-json\",\"skills-search-profile-json\"]")
                .doesNotContain("\"commands\":[");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void commandsCommandCanRenderIndexJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--surface",
                "assistant-agent",
                "--category",
                "Runs",
                "--index",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"query\":{\"surfaceId\":\"assistant-agent\",\"profileId\":null,\"resolvedSurfaceId\":\"assistant-agent\",\"category\":\"Runs\",\"commandId\":null,\"contractJsonSchemaId\":null,\"filtered\":true}")
                .contains("\"categories\":[\"Runs\"]")
                .contains("\"categoryCounts\":{\"Runs\":31}")
                .contains("\"categorySummaries\":[{\"name\":\"Runs\",\"count\":31,\"commandIds\":[")
                .contains("\"commandIds\":[")
                .contains("\"run-result-json\"")
                .contains("\"run-preflight-json\"")
                .contains("\"run-profile\"")
                .contains("\"run-session-context\"")
                .contains("\"run-inspect-json\"")
                .contains("\"run-store-json\"")
                .contains("\"run-store-verify-json\"")
                .contains("\"run-store-compact-dry-run-json\"")
                .contains("\"run-store-compact-apply-json\"")
                .contains("\"run-events-json\"")
                .contains("\"run-events-filter-json\"")
                .contains("\"run-events-cursor-json\"")
                .contains("\"run-events-follow-json\"")
                .contains("\"run-events-follow-result-json\"")
                .contains("\"run-events-follow-result-only-json\"")
                .contains("\"run-events-follow-result-only-stats-json\"")
                .contains("\"run-events-stats-json\"")
                .contains("\"run-list-json\"")
                .contains("\"run-list-page-json\"")
                .contains("\"run-stats-json\"")
                .contains("\"run-list-filter-json\"")
                .contains("\"run-list-profile-json\"")
                .contains("\"run-stats-profile-json\"")
                .contains("\"run-wait-json\"")
                .contains("\"run-cancel-json\"")
                .contains("\"run-forget-json\"")
                .doesNotContain("\"commands\":[")
                .doesNotContain("\"description\":");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void commandsCommandCanRenderContractsIndexJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--category",
                "Contracts",
                "--index",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"category\":\"Contracts\"")
                .contains("\"matchingCommands\":11")
                .contains("\"categories\":[\"Contracts\"]")
                .contains("\"categoryCounts\":{\"Contracts\":11}")
                .contains("\"commandIds\":[\"contracts-json\",\"contracts-index-json\",\"contracts-envelope-schema-json\",\"contracts-schema-bundle-json\",\"contracts-check-json\",\"contracts-coverage-json\",\"contracts-command-json\",\"contracts-json-schema-id-json\",\"contracts-domain-json\",\"contracts-schema-json\",\"contracts-envelope-json\"]")
                .doesNotContain("\"commands\":[");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void commandsCommandCanFilterByProfileAsIndexJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--profile",
                "low-code-agent",
                "--category",
                "Runs",
                "--index",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"surfaceId\":null")
                .contains("\"profileId\":\"low-code-agent\"")
                .contains("\"resolvedSurfaceId\":\"workflow-platform\"")
                .contains("\"query\":{\"surfaceId\":null,\"profileId\":\"low-code-agent\",\"resolvedSurfaceId\":\"workflow-platform\",\"category\":\"Runs\",\"commandId\":null,\"contractJsonSchemaId\":null,\"filtered\":true}")
                .contains("\"categoryCounts\":{\"Runs\":30}")
                .contains("\"run-result-json\"")
                .contains("\"run-preflight-json\"")
                .contains("\"run-profile\"")
                .contains("\"run-workflow-skill\"")
                .contains("\"run-store-json\"")
                .contains("\"run-store-verify-json\"")
                .contains("\"run-store-compact-dry-run-json\"")
                .contains("\"run-store-compact-apply-json\"")
                .contains("\"run-events-follow-json\"")
                .contains("\"run-events-follow-result-json\"")
                .contains("\"run-events-follow-result-only-json\"")
                .contains("\"run-events-follow-result-only-stats-json\"")
                .contains("\"run-list-profile-json\"")
                .contains("\"run-stats-profile-json\"")
                .doesNotContain("\"run-assistant-surface\"")
                .doesNotContain("\"workspace-inspect\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void skillsCommandRendersSkillCatalog() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "skills");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus skills")
                .contains("product: Andalus")
                .contains("RAG Retrieval")
                .contains("rag.retrieve: RAG Retrieval [active]")
                .contains("source: rag")
                .contains("surfaces: assistant-agent, workflow-platform, platform-admin")
                .contains("aliases: rag")
                .contains("MCP Bridge")
                .contains("Skill Management");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void skillsCommandCanRenderFilteredJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "skills",
                "list",
                "--surface",
                "assistant-agent",
                "--source",
                "rag",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"query\":{\"surfaceId\":\"assistant-agent\",\"profileId\":null,\"resolvedSurfaceId\":\"assistant-agent\",\"category\":null,\"source\":\"rag\"")
                .contains("\"totalSkills\":12")
                .contains("\"matchingSkills\":1")
                .contains("\"skillIds\":[\"rag.retrieve\"]")
                .contains("\"aliases\":[\"rag\"]")
                .doesNotContain("\"id\":\"mcp.bridge\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void skillsCommandCanFilterByProfileAsJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "skills",
                "list",
                "--profile",
                "low-code-agent",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"query\":{\"surfaceId\":null,\"profileId\":\"low-code-agent\",\"resolvedSurfaceId\":\"workflow-platform\"")
                .contains("\"matchingSkills\":3")
                .contains("\"skillIds\":[\"workflow.gamelan\",\"hitl.approval\",\"observability.traces\"]")
                .contains("\"id\":\"workflow.gamelan\"")
                .contains("\"id\":\"hitl.approval\"")
                .contains("\"id\":\"observability.traces\"")
                .doesNotContain("\"id\":\"rag.retrieve\"")
                .doesNotContain("\"id\":\"tools.runtime\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void skillsCommandCanSearchWithinProfileAsJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "skills",
                "search",
                "gamelan",
                "--profile",
                "low-code-agent",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"search\":\"gamelan\"")
                .contains("\"profileId\":\"low-code-agent\"")
                .contains("\"resolvedSurfaceId\":\"workflow-platform\"")
                .contains("\"matchingSkills\":1")
                .contains("\"skillIds\":[\"workflow.gamelan\"]")
                .doesNotContain("\"id\":\"hitl.approval\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void skillsCommandRejectsProfileSurfaceConflicts() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "skills",
                "list",
                "--surface",
                "assistant-agent",
                "--profile",
                "low-code-agent");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err())
                .contains("Andalus product profile 'low-code-agent'")
                .contains("belongs to surface 'workflow-platform'")
                .contains("not 'assistant-agent'");
    }

    @Test
    void skillsInspectCanUseAlias() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "skills",
                "inspect",
                "rag",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"skillId\":\"rag.retrieve\"")
                .contains("\"id\":\"rag.retrieve\"")
                .contains("\"source\":\"rag\"")
                .contains("\"inputKeys\":[\"query\",\"collection\",\"filters\"]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void skillsSearchHonorsSurfaceFilter() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "skills",
                "search",
                "rag",
                "--surface",
                "assistant-agent",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("\"search\":\"rag\"")
                .contains("\"matchingSkills\":1")
                .contains("\"id\":\"rag.retrieve\"")
                .doesNotContain("\"id\":\"rag.admin\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void skillsCommandRejectsUnknownSurfaceFilter() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "skills",
                "list",
                "--surface",
                "future-agent");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err())
                .contains("Unknown Andalus product surface 'future-agent'")
                .contains("coding-agent")
                .contains("assistant-agent");
    }

    @Test
    void commandsCommandCanRenderTextIndex() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--category",
                "Run Specs",
                "--index");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus command index")
                .contains("category: Run Specs")
                .contains("totalCommands:")
                .contains("matchingCommands: 8")
                .contains("Categories")
                .contains("  - Run Specs (8)")
                .contains("    ids: spec-validate, spec-template, spec-template-output, spec-template-profile, run-spec-dry-json, run-print-spec, run-print-spec-output, run-profile-print-spec")
                .contains("Command IDs")
                .contains("  - run-print-spec-output")
                .doesNotContain("Write the resolved run request");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void commandsCommandCanFilterByCategory() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--category",
                "run specs");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("category: run specs")
                .contains("Run Specs")
                .contains("spec-template-output: spec template --surface coding-agent --output <file>")
                .contains("spec-template-profile: spec template --profile openclaw-agent")
                .contains("run-print-spec-output: run <task> --print-spec --output <file>")
                .contains("run-profile-print-spec: run <task> --profile <profile-id> --print-spec")
                .doesNotContain("workspace-inspect")
                .doesNotContain("tui: tui");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runStatusCommandReturnsUnknownLifecycleSnapshot() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "status",
                "run-unknown");

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .contains("Andalus run run-unknown")
                .contains("state: unknown")
                .contains("outcome: unknown")
                .contains("known: false")
                .contains("No run status is recorded");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runStatusCommandCanReadStatusFromSharedSdkInstance() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole runConsole = new TestConsole();

        int runExitCode = AndalusAqliCli.execute(
                sdk,
                runConsole.outStream(),
                runConsole.errStream(),
                "run",
                "record local lifecycle",
                "--tenant",
                "tenant-a");

        String runId = runConsole.out()
                .lines()
                .filter(line -> line.startsWith("Andalus run "))
                .map(line -> line.substring("Andalus run ".length()))
                .findFirst()
                .orElseThrow();
        TestConsole statusConsole = new TestConsole();

        int statusExitCode = AndalusAqliCli.execute(
                sdk,
                statusConsole.outStream(),
                statusConsole.errStream(),
                "run",
                "status",
                runId);

        assertThat(runExitCode).isZero();
        assertThat(statusExitCode).isZero();
        assertThat(statusConsole.out())
                .contains("Andalus run " + runId)
                .contains("state: completed")
                .contains("known: true")
                .contains("Run state is completed.")
                .contains("tenant=tenant-a")
                .contains("successful=true");
        assertThat(runConsole.err()).isEmpty();
        assertThat(statusConsole.err()).isEmpty();
    }

    @Test
    void runEventsCommandCanReadEventsFromSharedSdkInstance() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole runConsole = new TestConsole();

        int runExitCode = AndalusAqliCli.execute(
                sdk,
                runConsole.outStream(),
                runConsole.errStream(),
                "run",
                "record local event timeline",
                "--tenant",
                "tenant-a");

        String runId = runIdFromTextOutput(runConsole.out());
        TestConsole eventsConsole = new TestConsole();

        int eventsExitCode = AndalusAqliCli.execute(
                sdk,
                eventsConsole.outStream(),
                eventsConsole.errStream(),
                "run",
                "events",
                runId,
                "--json");

        assertThat(runExitCode).isZero();
        assertThat(eventsExitCode).isZero();
        assertThat(eventsConsole.out())
                .startsWith("{")
                .contains("\"runId\":\"" + runId + "\"")
                .contains("\"query\":{\"state\":null,\"type\":null,\"afterSequence\":0,\"limit\":50,\"filtered\":false}")
                .contains("\"cursor\":{\"afterSequence\":0,\"firstSequence\":1,\"lastSequence\":1,\"nextAfterSequence\":1,\"limit\":50,\"totalEvents\":1,\"returnedEvents\":1,\"remainingEvents\":0,\"advanced\":true,\"truncated\":false,\"empty\":false}")
                .contains("\"summary\":{\"totalEvents\":1,\"returnedEvents\":1,\"stateCounts\":{\"completed\":1},\"typeCounts\":{\"run.completed\":1},\"stateSummaries\":[{\"name\":\"completed\",\"count\":1}],\"typeSummaries\":[{\"name\":\"run.completed\",\"count\":1}],\"empty\":false}")
                .contains("\"totalEvents\":1")
                .contains("\"returnedEvents\":1")
                .contains("\"firstSequence\":1")
                .contains("\"lastSequence\":1")
                .contains("\"nextAfterSequence\":1")
                .contains("\"truncated\":false")
                .contains("\"stateCounts\":{\"completed\":1}")
                .contains("\"typeCounts\":{\"run.completed\":1}")
                .contains("\"stateSummaries\":[{\"name\":\"completed\",\"count\":1}]")
                .contains("\"typeSummaries\":[{\"name\":\"run.completed\",\"count\":1}]")
                .contains("\"empty\":false")
                .contains("\"type\":\"run.completed\"")
                .contains("\"state\":\"COMPLETED\"")
                .contains("\"tenant\":\"tenant-a\"");
        assertThat(runConsole.err()).isEmpty();
        assertThat(eventsConsole.err()).isEmpty();
    }

    @Test
    void runInspectCommandCanReadStatusAndEventsFromSharedSdkInstance() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole runConsole = new TestConsole();

        int runExitCode = AndalusAqliCli.execute(
                sdk,
                runConsole.outStream(),
                runConsole.errStream(),
                "run",
                "inspect local lifecycle",
                "--tenant",
                "tenant-a");

        String runId = runIdFromTextOutput(runConsole.out());
        TestConsole inspectConsole = new TestConsole();

        int inspectExitCode = AndalusAqliCli.execute(
                sdk,
                inspectConsole.outStream(),
                inspectConsole.errStream(),
                "run",
                "inspect",
                runId,
                "--state",
                "completed",
                "--limit",
                "5",
                "--json");

        assertThat(runExitCode).isZero();
        assertThat(inspectExitCode).isZero();
        assertThat(inspectConsole.out())
                .startsWith("{")
                .contains("\"runId\":\"" + runId + "\"")
                .contains("\"known\":true")
                .contains("\"status\":{\"handle\":{\"runId\":\"" + runId + "\",\"state\":\"COMPLETED\"")
                .contains("\"events\":{\"contract\":{\"schema\":\"andalus.run.lifecycle\",\"version\":1,\"envelope\":\"run-events\"},\"runId\":\"" + runId + "\"")
                .contains("\"query\":{\"state\":\"COMPLETED\",\"type\":null,\"afterSequence\":0,\"limit\":5,\"filtered\":true}")
                .contains("\"cursor\":{\"afterSequence\":0,\"firstSequence\":1,\"lastSequence\":1,\"nextAfterSequence\":1,\"limit\":5,\"totalEvents\":1,\"returnedEvents\":1,\"remainingEvents\":0,\"advanced\":true,\"truncated\":false,\"empty\":false}")
                .contains("\"summary\":{\"totalEvents\":1,\"returnedEvents\":1,\"stateCounts\":{\"completed\":1},\"typeCounts\":{\"run.completed\":1},\"stateSummaries\":[{\"name\":\"completed\",\"count\":1}],\"typeSummaries\":[{\"name\":\"run.completed\",\"count\":1}],\"empty\":false}")
                .contains("\"nextAfterSequence\":1")
                .contains("\"stateSummaries\":[{\"name\":\"completed\",\"count\":1}]")
                .contains("\"stateCounts\":{\"completed\":1}");
        assertThat(runConsole.err()).isEmpty();
        assertThat(inspectConsole.err()).isEmpty();
    }

    @Test
    void runEventsCommandCanFilterTimelineFromSharedSdkInstance() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole runConsole = new TestConsole();
        int runExitCode = AndalusAqliCli.execute(
                sdk,
                runConsole.outStream(),
                runConsole.errStream(),
                "run",
                "record filtered event timeline",
                "--tenant",
                "tenant-a");
        String runId = runIdFromTextOutput(runConsole.out());
        TestConsole eventsConsole = new TestConsole();

        int eventsExitCode = AndalusAqliCli.execute(
                sdk,
                eventsConsole.outStream(),
                eventsConsole.errStream(),
                "run",
                "events",
                runId,
                "--after-sequence",
                "0",
                "--state",
                "completed",
                "--type",
                "run.completed",
                "--limit",
                "1",
                "--json");

        assertThat(runExitCode).isZero();
        assertThat(eventsExitCode).isZero();
        assertThat(eventsConsole.out())
                .contains("\"query\":{\"state\":\"COMPLETED\",\"type\":\"run.completed\",\"afterSequence\":0,\"limit\":1,\"filtered\":true}")
                .contains("\"cursor\":{\"afterSequence\":0,\"firstSequence\":1,\"lastSequence\":1,\"nextAfterSequence\":1,\"limit\":1,\"totalEvents\":1,\"returnedEvents\":1,\"remainingEvents\":0,\"advanced\":true,\"truncated\":false,\"empty\":false}")
                .contains("\"summary\":{\"totalEvents\":1,\"returnedEvents\":1,\"stateCounts\":{\"completed\":1},\"typeCounts\":{\"run.completed\":1},\"stateSummaries\":[{\"name\":\"completed\",\"count\":1}],\"typeSummaries\":[{\"name\":\"run.completed\",\"count\":1}],\"empty\":false}")
                .contains("\"totalEvents\":1")
                .contains("\"returnedEvents\":1")
                .contains("\"nextAfterSequence\":1")
                .contains("\"stateCounts\":{\"completed\":1}")
                .contains("\"typeCounts\":{\"run.completed\":1}")
                .contains("\"stateSummaries\":[{\"name\":\"completed\",\"count\":1}]")
                .contains("\"typeSummaries\":[{\"name\":\"run.completed\",\"count\":1}]")
                .contains("\"state\":\"COMPLETED\"");
        assertThat(runConsole.err()).isEmpty();
        assertThat(eventsConsole.err()).isEmpty();
    }

    @Test
    void runEventsCommandCanRenderStatsWithoutRows() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole runConsole = new TestConsole();
        int runExitCode = AndalusAqliCli.execute(
                sdk,
                runConsole.outStream(),
                runConsole.errStream(),
                "run",
                "record event stats",
                "--tenant",
                "tenant-a");
        String runId = runIdFromTextOutput(runConsole.out());
        TestConsole statsConsole = new TestConsole();

        int statsExitCode = AndalusAqliCli.execute(
                sdk,
                statsConsole.outStream(),
                statsConsole.errStream(),
                "run",
                "events",
                runId,
                "--stats",
                "--json");

        assertThat(runExitCode).isZero();
        assertThat(statsExitCode).isZero();
        assertThat(statsConsole.out())
                .contains("\"runId\":\"" + runId + "\"")
                .contains("\"cursor\":{\"afterSequence\":0,\"firstSequence\":1,\"lastSequence\":1,\"nextAfterSequence\":1,\"limit\":50,\"totalEvents\":1,\"returnedEvents\":1,\"remainingEvents\":0,\"advanced\":true,\"truncated\":false,\"empty\":false}")
                .contains("\"summary\":{\"totalEvents\":1,\"returnedEvents\":1,\"stateCounts\":{\"completed\":1},\"typeCounts\":{\"run.completed\":1},\"stateSummaries\":[{\"name\":\"completed\",\"count\":1}],\"typeSummaries\":[{\"name\":\"run.completed\",\"count\":1}],\"empty\":false}")
                .contains("\"stateCounts\":{\"completed\":1}")
                .contains("\"stateSummaries\":[{\"name\":\"completed\",\"count\":1}]")
                .doesNotContain("\"events\":[")
                .doesNotContain("\"tenant\":\"tenant-a\"");
        assertThat(runConsole.err()).isEmpty();
        assertThat(statsConsole.err()).isEmpty();
    }

    @Test
    void runEventsCommandCanUseSequenceCursor() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole runConsole = new TestConsole();
        int runExitCode = AndalusAqliCli.execute(
                sdk,
                runConsole.outStream(),
                runConsole.errStream(),
                "run",
                "record cursor event timeline",
                "--tenant",
                "tenant-a");
        String runId = runIdFromTextOutput(runConsole.out());
        TestConsole eventsConsole = new TestConsole();

        int eventsExitCode = AndalusAqliCli.execute(
                sdk,
                eventsConsole.outStream(),
                eventsConsole.errStream(),
                "run",
                "events",
                runId,
                "--after-sequence",
                "1",
                "--limit",
                "10",
                "--json");

        assertThat(runExitCode).isZero();
        assertThat(eventsExitCode).isEqualTo(1);
        assertThat(eventsConsole.out())
                .contains("\"query\":{\"state\":null,\"type\":null,\"afterSequence\":1,\"limit\":10,\"filtered\":true}")
                .contains("\"cursor\":{\"afterSequence\":1,\"firstSequence\":0,\"lastSequence\":0,\"nextAfterSequence\":1,\"limit\":10,\"totalEvents\":0,\"returnedEvents\":0,\"remainingEvents\":0,\"advanced\":false,\"truncated\":false,\"empty\":true}")
                .contains("\"summary\":{\"totalEvents\":0,\"returnedEvents\":0,\"stateCounts\":{},\"typeCounts\":{},\"stateSummaries\":[],\"typeSummaries\":[],\"empty\":true}")
                .contains("\"totalEvents\":0")
                .contains("\"returnedEvents\":0")
                .contains("\"firstSequence\":0")
                .contains("\"lastSequence\":0")
                .contains("\"nextAfterSequence\":1")
                .contains("\"truncated\":false")
                .contains("\"stateCounts\":{}")
                .contains("\"typeCounts\":{}")
                .contains("\"stateSummaries\":[]")
                .contains("\"typeSummaries\":[]")
                .contains("\"empty\":true");
        assertThat(runConsole.err()).isEmpty();
        assertThat(eventsConsole.err()).isEmpty();
    }

    @Test
    void runEventsCommandCanFollowUntilTerminalEvent() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole runConsole = new TestConsole();
        int runExitCode = AndalusAqliCli.execute(
                sdk,
                runConsole.outStream(),
                runConsole.errStream(),
                "run",
                "follow terminal event timeline",
                "--tenant",
                "tenant-a");
        String runId = runIdFromTextOutput(runConsole.out());
        TestConsole eventsConsole = new TestConsole();

        int eventsExitCode = AndalusAqliCli.execute(
                sdk,
                eventsConsole.outStream(),
                eventsConsole.errStream(),
                "run",
                "events",
                runId,
                "--follow",
                "--follow-result",
                "--max-polls",
                "3",
                "--poll-millis",
                "1",
                "--json");

        assertThat(runExitCode).isZero();
        assertThat(eventsExitCode).isZero();
        assertThat(eventsConsole.out())
                .startsWith("{")
                .contains("\"contract\":{\"schema\":\"andalus.run.lifecycle\",\"version\":1,\"envelope\":\"run-events\"}")
                .contains("\"runId\":\"" + runId + "\"")
                .contains("\"query\":{\"state\":null,\"type\":null,\"afterSequence\":0,\"limit\":50,\"filtered\":false}")
                .contains("\"cursor\":{\"afterSequence\":0,\"firstSequence\":1,\"lastSequence\":1,\"nextAfterSequence\":1,\"limit\":50,\"totalEvents\":1,\"returnedEvents\":1,\"remainingEvents\":0,\"advanced\":true,\"truncated\":false,\"empty\":false}")
                .contains("\"type\":\"run.completed\"")
                .contains("\"state\":\"COMPLETED\"")
                .contains("\"tenant\":\"tenant-a\"")
                .contains("\"contract\":{\"schema\":\"andalus.run.lifecycle\",\"version\":1,\"envelope\":\"run-events-follow\"}")
                .contains("\"successful\":true")
                .contains("\"outcome\":\"terminal\"")
                .contains("\"terminal\":true")
                .contains("\"terminalState\":\"completed\"")
                .contains("\"terminalEventType\":\"run.completed\"")
                .contains("\"terminalSequence\":1")
                .contains("\"maxPollsReached\":false")
                .contains("\"polls\":1")
                .contains("\"initialQuery\":{\"state\":null,\"type\":null,\"afterSequence\":0,\"limit\":50,\"filtered\":false}")
                .contains("\"nextQuery\":{\"state\":null,\"type\":null,\"afterSequence\":1,\"limit\":50,\"filtered\":true}")
                .contains("\"lastEvents\":{\"contract\":{\"schema\":\"andalus.run.lifecycle\",\"version\":1,\"envelope\":\"run-events\"}");
        assertThat(runConsole.err()).isEmpty();
        assertThat(eventsConsole.err()).isEmpty();
    }

    @Test
    void runEventsCommandCanRenderOnlyFollowResult() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole runConsole = new TestConsole();
        int runExitCode = AndalusAqliCli.execute(
                sdk,
                runConsole.outStream(),
                runConsole.errStream(),
                "run",
                "follow result only event timeline",
                "--tenant",
                "tenant-a");
        String runId = runIdFromTextOutput(runConsole.out());
        TestConsole eventsConsole = new TestConsole();

        int eventsExitCode = AndalusAqliCli.execute(
                sdk,
                eventsConsole.outStream(),
                eventsConsole.errStream(),
                "run",
                "events",
                runId,
                "--follow",
                "--follow-result-only",
                "--max-polls",
                "3",
                "--poll-millis",
                "1",
                "--json");

        assertThat(runExitCode).isZero();
        assertThat(eventsExitCode).isZero();
        assertThat(eventsConsole.out().trim().split("\\R")).hasSize(1);
        assertThat(eventsConsole.out())
                .startsWith("{\"contract\":{\"schema\":\"andalus.run.lifecycle\",\"version\":1,\"envelope\":\"run-events-follow\"}")
                .contains("\"successful\":true")
                .contains("\"outcome\":\"terminal\"")
                .contains("\"terminal\":true")
                .contains("\"terminalState\":\"completed\"")
                .contains("\"terminalEventType\":\"run.completed\"")
                .contains("\"terminalSequence\":1")
                .contains("\"lastEvents\":{\"contract\":{\"schema\":\"andalus.run.lifecycle\",\"version\":1,\"envelope\":\"run-events\"}")
                .contains("\"tenant\":\"tenant-a\"");
        assertThat(runConsole.err()).isEmpty();
        assertThat(eventsConsole.err()).isEmpty();
    }

    @Test
    void runEventsCommandCanRenderOnlyFollowStats() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole runConsole = new TestConsole();
        int runExitCode = AndalusAqliCli.execute(
                sdk,
                runConsole.outStream(),
                runConsole.errStream(),
                "run",
                "follow result only stats event timeline",
                "--tenant",
                "tenant-a");
        String runId = runIdFromTextOutput(runConsole.out());
        TestConsole eventsConsole = new TestConsole();

        int eventsExitCode = AndalusAqliCli.execute(
                sdk,
                eventsConsole.outStream(),
                eventsConsole.errStream(),
                "run",
                "events",
                runId,
                "--follow",
                "--follow-result-only",
                "--stats",
                "--max-polls",
                "3",
                "--poll-millis",
                "1",
                "--json");

        assertThat(runExitCode).isZero();
        assertThat(eventsExitCode).isZero();
        assertThat(eventsConsole.out().trim().split("\\R")).hasSize(1);
        assertThat(eventsConsole.out())
                .startsWith("{\"contract\":{\"schema\":\"andalus.run.lifecycle\",\"version\":1,\"envelope\":\"run-events-follow\"}")
                .contains("\"outcome\":\"terminal\"")
                .contains("\"terminalState\":\"completed\"")
                .contains("\"terminalSequence\":1")
                .contains("\"lastEvents\":{\"contract\":{\"schema\":\"andalus.run.lifecycle\",\"version\":1,\"envelope\":\"run-events-stats\"}")
                .contains("\"stateCounts\":{\"completed\":1}")
                .doesNotContain("\"events\":[");
        assertThat(runConsole.err()).isEmpty();
        assertThat(eventsConsole.err()).isEmpty();
    }

    @Test
    void runEventsCommandReportsMaxPollsFollowOutcome() {
        AgentRunStore store = AgentRunStore.memory();
        store.save(new AgentRunStatus(
                new AgentRunHandle("run-cli-running-1", AgentRunState.RUNNING, "strategy-a"),
                true,
                "Run is still running.",
                Map.of("tenant", "tenant-a")));
        AndalusAqliSdk sdk = new LocalAndalusAqliSdk(AndalusAqliSdkConfig.local(), store);
        TestConsole eventsConsole = new TestConsole();

        int eventsExitCode = AndalusAqliCli.execute(
                sdk,
                eventsConsole.outStream(),
                eventsConsole.errStream(),
                "run",
                "events",
                "run-cli-running-1",
                "--follow",
                "--follow-result-only",
                "--max-polls",
                "1",
                "--poll-millis",
                "1",
                "--json");

        assertThat(eventsExitCode).isEqualTo(1);
        assertThat(eventsConsole.out().trim().split("\\R")).hasSize(1);
        assertThat(eventsConsole.out())
                .startsWith("{\"contract\":{\"schema\":\"andalus.run.lifecycle\",\"version\":1,\"envelope\":\"run-events-follow\"}")
                .contains("\"successful\":false")
                .contains("\"outcome\":\"max-polls\"")
                .contains("\"terminal\":false")
                .contains("\"terminalState\":null")
                .contains("\"terminalEventType\":null")
                .contains("\"terminalSequence\":0")
                .contains("\"maxPollsReached\":true")
                .contains("\"polls\":1")
                .contains("\"type\":\"run.running\"")
                .contains("\"state\":\"RUNNING\"");
        assertThat(eventsConsole.err()).isEmpty();
    }

    @Test
    void runEventsCommandRejectsFollowResultWithoutFollow() {
        TestConsole eventsConsole = new TestConsole();

        int eventsExitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                eventsConsole.outStream(),
                eventsConsole.errStream(),
                "run",
                "events",
                "run-1",
                "--follow-result");

        assertThat(eventsExitCode).isEqualTo(2);
        assertThat(eventsConsole.out()).isEmpty();
        assertThat(eventsConsole.err()).contains("--follow-result is only supported with --follow.");
    }

    @Test
    void runEventsCommandRejectsFollowResultOnlyWithoutFollow() {
        TestConsole eventsConsole = new TestConsole();

        int eventsExitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                eventsConsole.outStream(),
                eventsConsole.errStream(),
                "run",
                "events",
                "run-1",
                "--follow-result-only");

        assertThat(eventsExitCode).isEqualTo(2);
        assertThat(eventsConsole.out()).isEmpty();
        assertThat(eventsConsole.err()).contains("--follow-result-only is only supported with --follow.");
    }

    @Test
    void runListCommandCanReadHistoryFromSharedSdkInstance() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole firstRun = new TestConsole();
        TestConsole secondRun = new TestConsole();

        AndalusAqliCli.execute(
                sdk,
                firstRun.outStream(),
                firstRun.errStream(),
                "run",
                "first lifecycle run",
                "--tenant",
                "tenant-a");
        AndalusAqliCli.execute(
                sdk,
                secondRun.outStream(),
                secondRun.errStream(),
                "run",
                "second lifecycle run",
                "--tenant",
                "tenant-b");
        TestConsole listConsole = new TestConsole();

        int listExitCode = AndalusAqliCli.execute(
                sdk,
                listConsole.outStream(),
                listConsole.errStream(),
                "run",
                "list",
                "--json");

        assertThat(listExitCode).isZero();
        assertThat(listConsole.out())
                .startsWith("{")
                .contains("\"totalRuns\":2")
                .contains("\"returnedRuns\":2")
                .contains("\"offset\":0")
                .contains("\"nextOffset\":2")
                .contains("\"hasMore\":false")
                .contains("\"truncated\":false")
                .contains("\"stateCounts\":{\"completed\":2}")
                .contains("\"surfaceCounts\":{\"coding-agent\":2}")
                .contains("\"profileCounts\":{}")
                .contains("\"strategyCounts\":{\"andalus-agent-over-aqli\":2}")
                .contains("\"stateSummaries\":[{\"name\":\"completed\",\"count\":2}]")
                .contains("\"surfaceSummaries\":[{\"name\":\"coding-agent\",\"count\":2}]")
                .contains("\"profileSummaries\":[]")
                .contains("\"strategySummaries\":[{\"name\":\"andalus-agent-over-aqli\",\"count\":2}]")
                .contains("\"empty\":false")
                .contains("\"message\":\"Recorded run statuses.\"")
                .contains("\"query\":{\"state\":null,\"limit\":50,\"offset\":0,\"tenantId\":null,\"sessionId\":null,\"surfaceId\":null,\"profileId\":null,\"filtered\":false}")
                .contains("\"state\":\"COMPLETED\"")
                .contains("\"tenant\":\"tenant-a\"")
                .contains("\"tenant\":\"tenant-b\"");
        assertThat(firstRun.err()).isEmpty();
        assertThat(secondRun.err()).isEmpty();
        assertThat(listConsole.err()).isEmpty();
    }

    @Test
    void runListCommandCanFilterHistoryByStateAndLimit() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole firstRun = new TestConsole();
        TestConsole secondRun = new TestConsole();
        AndalusAqliCli.execute(sdk, firstRun.outStream(), firstRun.errStream(), "run", "first", "--tenant", "tenant-a");
        AndalusAqliCli.execute(sdk, secondRun.outStream(), secondRun.errStream(), "run", "second", "--tenant", "tenant-b");
        TestConsole listConsole = new TestConsole();

        int listExitCode = AndalusAqliCli.execute(
                sdk,
                listConsole.outStream(),
                listConsole.errStream(),
                "run",
                "list",
                "--state",
                "completed",
                "--limit",
                "1",
                "--offset",
                "1",
                "--json");

        assertThat(listExitCode).isZero();
        assertThat(listConsole.out())
                .contains("\"query\":{\"state\":\"COMPLETED\",\"limit\":1,\"offset\":1,\"tenantId\":null,\"sessionId\":null,\"surfaceId\":null,\"profileId\":null,\"filtered\":true}")
                .contains("\"page\":{\"totalRuns\":2,\"returnedRuns\":1,\"pageSize\":1,\"offset\":1,\"windowStart\":2,\"windowEnd\":2,\"previousOffset\":0,\"hasPrevious\":true,\"nextOffset\":2,\"hasMore\":false,\"truncated\":true,\"empty\":false}")
                .contains("\"summary\":{\"totalRuns\":2,\"returnedRuns\":1,\"stateCounts\":{\"completed\":1},\"surfaceCounts\":{\"coding-agent\":1},\"profileCounts\":{},\"strategyCounts\":{\"andalus-agent-over-aqli\":1},\"stateSummaries\":[{\"name\":\"completed\",\"count\":1}],\"surfaceSummaries\":[{\"name\":\"coding-agent\",\"count\":1}],\"profileSummaries\":[],\"strategySummaries\":[{\"name\":\"andalus-agent-over-aqli\",\"count\":1}],\"empty\":false}")
                .contains("\"totalRuns\":2")
                .contains("\"returnedRuns\":1")
                .contains("\"pageSize\":1")
                .contains("\"offset\":1")
                .contains("\"windowStart\":2")
                .contains("\"windowEnd\":2")
                .contains("\"previousOffset\":0")
                .contains("\"hasPrevious\":true")
                .contains("\"nextOffset\":2")
                .contains("\"hasMore\":false")
                .contains("\"truncated\":true")
                .contains("\"stateCounts\":{\"completed\":1}")
                .contains("\"tenant\":\"tenant-b\"")
                .doesNotContain("\"tenant\":\"tenant-a\"");
        assertThat(firstRun.err()).isEmpty();
        assertThat(secondRun.err()).isEmpty();
        assertThat(listConsole.err()).isEmpty();
    }

    @Test
    void runStatsCommandCanSummarizeHistoryWithoutRows() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole firstRun = new TestConsole();
        TestConsole secondRun = new TestConsole();
        AndalusAqliCli.execute(
                sdk,
                firstRun.outStream(),
                firstRun.errStream(),
                "run",
                "first stats lifecycle",
                "--tenant",
                "tenant-a",
                "--surface",
                "assistant-agent");
        AndalusAqliCli.execute(
                sdk,
                secondRun.outStream(),
                secondRun.errStream(),
                "run",
                "second stats lifecycle",
                "--tenant",
                "tenant-b",
                "--surface",
                "coding-agent");
        TestConsole statsConsole = new TestConsole();

        int statsExitCode = AndalusAqliCli.execute(
                sdk,
                statsConsole.outStream(),
                statsConsole.errStream(),
                "run",
                "stats",
                "--limit",
                "1",
                "--offset",
                "1",
                "--json");

        assertThat(statsExitCode).isZero();
        assertThat(statsConsole.out())
                .contains("\"query\":{\"state\":null,\"limit\":1,\"offset\":1,\"tenantId\":null,\"sessionId\":null,\"surfaceId\":null,\"profileId\":null,\"filtered\":true}")
                .contains("\"page\":{\"totalRuns\":2,\"returnedRuns\":1,\"pageSize\":1,\"offset\":1,\"windowStart\":2,\"windowEnd\":2,\"previousOffset\":0,\"hasPrevious\":true,\"nextOffset\":2,\"hasMore\":false,\"truncated\":true,\"empty\":false}")
                .contains("\"summary\":{\"totalRuns\":2,\"returnedRuns\":1,\"stateCounts\":{\"completed\":1},\"surfaceCounts\":{\"coding-agent\":1},\"profileCounts\":{},\"strategyCounts\":{\"andalus-agent-over-aqli\":1},\"stateSummaries\":[{\"name\":\"completed\",\"count\":1}],\"surfaceSummaries\":[{\"name\":\"coding-agent\",\"count\":1}],\"profileSummaries\":[],\"strategySummaries\":[{\"name\":\"andalus-agent-over-aqli\",\"count\":1}],\"empty\":false}")
                .contains("\"surfaceCounts\":{\"coding-agent\":1}")
                .doesNotContain("\"runs\":[")
                .doesNotContain("\"tenant\":\"tenant-a\"")
                .doesNotContain("\"tenant\":\"tenant-b\"");
        assertThat(firstRun.err()).isEmpty();
        assertThat(secondRun.err()).isEmpty();
        assertThat(statsConsole.err()).isEmpty();
    }

    @Test
    void runListCommandCanFilterHistoryByTenantSessionAndSurface() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole firstRun = new TestConsole();
        TestConsole secondRun = new TestConsole();
        AndalusAqliCli.execute(
                sdk,
                firstRun.outStream(),
                firstRun.errStream(),
                "run",
                "first filtered lifecycle",
                "--tenant",
                "tenant-a",
                "--session",
                "session-a",
                "--surface",
                "assistant-agent");
        AndalusAqliCli.execute(
                sdk,
                secondRun.outStream(),
                secondRun.errStream(),
                "run",
                "second filtered lifecycle",
                "--tenant",
                "tenant-b",
                "--session",
                "session-b",
                "--surface",
                "coding-agent");
        TestConsole listConsole = new TestConsole();

        int listExitCode = AndalusAqliCli.execute(
                sdk,
                listConsole.outStream(),
                listConsole.errStream(),
                "run",
                "list",
                "--tenant",
                "tenant-a",
                "--session",
                "session-a",
                "--surface",
                "assistant-agent",
                "--json");

        assertThat(listExitCode).isZero();
        assertThat(listConsole.out())
                .contains("\"query\":{\"state\":null,\"limit\":50,\"offset\":0,\"tenantId\":\"tenant-a\",\"sessionId\":\"session-a\",\"surfaceId\":\"assistant-agent\",\"profileId\":null,\"filtered\":true}")
                .contains("\"totalRuns\":1")
                .contains("\"returnedRuns\":1")
                .contains("\"hasMore\":false")
                .contains("\"surfaceCounts\":{\"assistant-agent\":1}")
                .contains("\"tenant\":\"tenant-a\"")
                .contains("\"session\":\"session-a\"")
                .contains("\"surface\":\"assistant-agent\"")
                .doesNotContain("\"tenant\":\"tenant-b\"");
        assertThat(firstRun.err()).isEmpty();
        assertThat(secondRun.err()).isEmpty();
        assertThat(listConsole.err()).isEmpty();
    }

    @Test
    void runListCommandCanFilterHistoryByProductProfile() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole profileRun = new TestConsole();
        TestConsole defaultRun = new TestConsole();
        int profileRunExitCode = AndalusAqliCli.execute(
                sdk,
                profileRun.outStream(),
                profileRun.errStream(),
                "run",
                "profile filtered lifecycle",
                "--profile",
                "low-code-agent");
        int defaultRunExitCode = AndalusAqliCli.execute(
                sdk,
                defaultRun.outStream(),
                defaultRun.errStream(),
                "run",
                "default lifecycle");
        TestConsole listConsole = new TestConsole();

        int listExitCode = AndalusAqliCli.execute(
                sdk,
                listConsole.outStream(),
                listConsole.errStream(),
                "run",
                "list",
                "--profile",
                "low-code-agent",
                "--json");

        assertThat(profileRunExitCode).isZero();
        assertThat(defaultRunExitCode).isZero();
        assertThat(listExitCode).isZero();
        assertThat(listConsole.out())
                .contains("\"query\":{\"state\":null,\"limit\":50,\"offset\":0,\"tenantId\":null,\"sessionId\":null,\"surfaceId\":null,\"profileId\":\"low-code-agent\",\"filtered\":true}")
                .contains("\"totalRuns\":1")
                .contains("\"returnedRuns\":1")
                .contains("\"surfaceCounts\":{\"workflow-platform\":1}")
                .contains("\"profileCounts\":{\"low-code-agent\":1}")
                .contains("\"profileSummaries\":[{\"name\":\"low-code-agent\",\"count\":1}]")
                .contains("\"profile\":\"low-code-agent\"")
                .contains("\"profileId\":\"low-code-agent\"")
                .contains("\"surface\":\"workflow-platform\"")
                .doesNotContain("\"surface\":\"coding-agent\"");
        assertThat(profileRun.err()).isEmpty();
        assertThat(defaultRun.err()).isEmpty();
        assertThat(listConsole.err()).isEmpty();
    }

    @Test
    void runListCommandCanReadPersistentStoreAcrossCliInvocations(@TempDir Path directory) {
        Path storePath = directory.resolve("runs.properties");
        TestConsole runConsole = new TestConsole();

        int runExitCode = AndalusAqliCli.execute(
                null,
                runConsole.outStream(),
                runConsole.errStream(),
                "--run-store",
                storePath.toString(),
                "run",
                "persist this lifecycle",
                "--tenant",
                "tenant-file");
        TestConsole listConsole = new TestConsole();

        int listExitCode = AndalusAqliCli.execute(
                null,
                listConsole.outStream(),
                listConsole.errStream(),
                "--run-store",
                storePath.toString(),
                "run",
                "list",
                "--state",
                "completed",
                "--json");

        assertThat(runExitCode).isZero();
        assertThat(listExitCode).isZero();
        assertThat(listConsole.out())
                .contains("\"query\":{\"state\":\"COMPLETED\",\"limit\":50,\"offset\":0,\"tenantId\":null,\"sessionId\":null,\"surfaceId\":null,\"profileId\":null,\"filtered\":true}")
                .contains("\"totalRuns\":1")
                .contains("\"returnedRuns\":1")
                .contains("\"nextOffset\":1")
                .contains("\"surfaceCounts\":{\"coding-agent\":1}")
                .contains("\"tenant\":\"tenant-file\"");
        assertThat(runConsole.err()).isEmpty();
        assertThat(listConsole.err()).isEmpty();
    }

    @Test
    void runWaitCommandCanReadTerminalStatusFromSharedSdkInstance() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole runConsole = new TestConsole();

        int runExitCode = AndalusAqliCli.execute(
                sdk,
                runConsole.outStream(),
                runConsole.errStream(),
                "run",
                "wait local lifecycle",
                "--tenant",
                "tenant-a");
        String runId = runIdFromTextOutput(runConsole.out());
        TestConsole waitConsole = new TestConsole();

        int waitExitCode = AndalusAqliCli.execute(
                sdk,
                waitConsole.outStream(),
                waitConsole.errStream(),
                "run",
                "wait",
                runId,
                "--timeout-seconds",
                "0",
                "--poll-millis",
                "1",
                "--json");

        assertThat(runExitCode).isZero();
        assertThat(waitExitCode).isZero();
        assertThat(waitConsole.out())
                .startsWith("{")
                .contains("\"runId\":\"" + runId + "\"")
                .contains("\"outcome\":\"terminal\"")
                .contains("\"terminal\":true")
                .contains("\"timedOut\":false")
                .contains("\"attempts\":1")
                .contains("\"state\":\"COMPLETED\"");
        assertThat(runConsole.err()).isEmpty();
        assertThat(waitConsole.err()).isEmpty();
    }

    @Test
    void runWaitCommandReturnsUnknownForMissingRuns() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "wait",
                "run-missing",
                "--timeout-seconds",
                "0",
                "--json");

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .startsWith("{")
                .contains("\"runId\":\"run-missing\"")
                .contains("\"outcome\":\"unknown\"")
                .contains("\"terminal\":false")
                .contains("\"timedOut\":false")
                .contains("\"known\":false")
                .contains("No run status is recorded");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runWaitCommandReportsTimeoutOutcomeForNonTerminalRun() {
        AgentRunStore store = AgentRunStore.memory();
        store.save(new AgentRunStatus(
                new AgentRunHandle("run-wait-running-1", AgentRunState.RUNNING, "strategy-a"),
                true,
                "Run is still running.",
                Map.of("tenant", "tenant-a")));
        AndalusAqliSdk sdk = new LocalAndalusAqliSdk(AndalusAqliSdkConfig.local(), store);
        TestConsole waitConsole = new TestConsole();

        int waitExitCode = AndalusAqliCli.execute(
                sdk,
                waitConsole.outStream(),
                waitConsole.errStream(),
                "run",
                "wait",
                "run-wait-running-1",
                "--timeout-seconds",
                "0",
                "--poll-millis",
                "1",
                "--json");

        assertThat(waitExitCode).isEqualTo(1);
        assertThat(waitConsole.out())
                .startsWith("{")
                .contains("\"runId\":\"run-wait-running-1\"")
                .contains("\"outcome\":\"timeout\"")
                .contains("\"terminal\":false")
                .contains("\"timedOut\":true")
                .contains("\"state\":\"RUNNING\"")
                .contains("before timeout");
        assertThat(waitConsole.err()).isEmpty();
    }

    @Test
    void runCancelCommandReportsTerminalLocalRunAsNotCancellable() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole runConsole = new TestConsole();

        int runExitCode = AndalusAqliCli.execute(
                sdk,
                runConsole.outStream(),
                runConsole.errStream(),
                "run",
                "terminal cancel lifecycle",
                "--tenant",
                "tenant-a");
        String runId = runIdFromTextOutput(runConsole.out());
        TestConsole cancelConsole = new TestConsole();

        int cancelExitCode = AndalusAqliCli.execute(
                sdk,
                cancelConsole.outStream(),
                cancelConsole.errStream(),
                "run",
                "cancel",
                runId,
                "--reason",
                "user stop",
                "--json");

        assertThat(runExitCode).isZero();
        assertThat(cancelExitCode).isEqualTo(1);
        assertThat(cancelConsole.out())
                .startsWith("{")
                .contains("\"runId\":\"" + runId + "\"")
                .contains("\"cancelled\":false")
                .contains("\"outcome\":\"not-cancellable\"")
                .contains("\"state\":\"COMPLETED\"")
                .contains("\"message\":\"Run state is completed and cannot be cancelled.\"");
        assertThat(runConsole.err()).isEmpty();
        assertThat(cancelConsole.err()).isEmpty();
    }

    @Test
    void runCancelCommandReportsMissingRunAsNotFound() {
        TestConsole cancelConsole = new TestConsole();

        int cancelExitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                cancelConsole.outStream(),
                cancelConsole.errStream(),
                "run",
                "cancel",
                "run-missing",
                "--json");

        assertThat(cancelExitCode).isEqualTo(1);
        assertThat(cancelConsole.out())
                .startsWith("{")
                .contains("\"runId\":\"run-missing\"")
                .contains("\"cancelled\":false")
                .contains("\"outcome\":\"not-found\"")
                .contains("\"state\":\"UNKNOWN\"")
                .contains("No run status is recorded");
        assertThat(cancelConsole.err()).isEmpty();
    }

    @Test
    void runForgetCommandCanForgetStatusFromSharedSdkInstance() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        TestConsole runConsole = new TestConsole();

        int runExitCode = AndalusAqliCli.execute(
                sdk,
                runConsole.outStream(),
                runConsole.errStream(),
                "run",
                "forget local lifecycle",
                "--tenant",
                "tenant-a");
        String runId = runIdFromTextOutput(runConsole.out());
        TestConsole forgetConsole = new TestConsole();

        int forgetExitCode = AndalusAqliCli.execute(
                sdk,
                forgetConsole.outStream(),
                forgetConsole.errStream(),
                "run",
                "forget",
                runId,
                "--json");
        TestConsole statusConsole = new TestConsole();
        int statusExitCode = AndalusAqliCli.execute(
                sdk,
                statusConsole.outStream(),
                statusConsole.errStream(),
                "run",
                "status",
                runId,
                "--json");

        assertThat(runExitCode).isZero();
        assertThat(forgetExitCode).isZero();
        assertThat(statusExitCode).isEqualTo(1);
        assertThat(forgetConsole.out())
                .startsWith("{")
                .contains("\"runId\":\"" + runId + "\"")
                .contains("\"forgotten\":true")
                .contains("\"outcome\":\"forgotten\"")
                .contains("\"message\":\"Forgot Andalus run status.\"")
                .contains("\"state\":\"COMPLETED\"");
        assertThat(statusConsole.out())
                .contains("\"runId\":\"" + runId + "\"")
                .contains("\"known\":false");
        assertThat(runConsole.err()).isEmpty();
        assertThat(forgetConsole.err()).isEmpty();
        assertThat(statusConsole.err()).isEmpty();
    }

    @Test
    void runForgetCommandCanMaintainPersistentStoreAcrossCliInvocations(@TempDir Path directory) {
        Path storePath = directory.resolve("runs.properties");
        TestConsole runConsole = new TestConsole();

        int runExitCode = AndalusAqliCli.execute(
                null,
                runConsole.outStream(),
                runConsole.errStream(),
                "--run-store",
                storePath.toString(),
                "run",
                "persist then forget",
                "--tenant",
                "tenant-file");
        String runId = runIdFromTextOutput(runConsole.out());
        TestConsole forgetConsole = new TestConsole();

        int forgetExitCode = AndalusAqliCli.execute(
                null,
                forgetConsole.outStream(),
                forgetConsole.errStream(),
                "--run-store",
                storePath.toString(),
                "run",
                "forget",
                runId,
                "--json");
        TestConsole listConsole = new TestConsole();
        int listExitCode = AndalusAqliCli.execute(
                null,
                listConsole.outStream(),
                listConsole.errStream(),
                "--run-store",
                storePath.toString(),
                "run",
                "list",
                "--json");

        assertThat(runExitCode).isZero();
        assertThat(forgetExitCode).isZero();
        assertThat(listExitCode).isZero();
        assertThat(forgetConsole.out())
                .contains("\"runId\":\"" + runId + "\"")
                .contains("\"forgotten\":true")
                .contains("\"outcome\":\"forgotten\"");
        assertThat(listConsole.out())
                .contains("\"totalRuns\":0")
                .contains("\"returnedRuns\":0")
                .contains("\"stateCounts\":{}")
                .contains("\"surfaceCounts\":{}")
                .contains("\"profileCounts\":{}")
                .contains("\"strategyCounts\":{}")
                .contains("\"empty\":true");
        assertThat(runConsole.err()).isEmpty();
        assertThat(forgetConsole.err()).isEmpty();
        assertThat(listConsole.err()).isEmpty();
    }

    @Test
    void runForgetCommandReportsMissingRunAsNotFound() {
        TestConsole forgetConsole = new TestConsole();

        int forgetExitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                forgetConsole.outStream(),
                forgetConsole.errStream(),
                "run",
                "forget",
                "run-missing",
                "--json");

        assertThat(forgetExitCode).isEqualTo(1);
        assertThat(forgetConsole.out())
                .startsWith("{")
                .contains("\"runId\":\"run-missing\"")
                .contains("\"forgotten\":false")
                .contains("\"outcome\":\"not-found\"")
                .contains("No run status is recorded");
        assertThat(forgetConsole.err()).isEmpty();
    }

    @Test
    void runStatusCommandCanRenderJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "status",
                "run-unknown",
                "--json");

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .startsWith("{")
                .contains("\"handle\":{\"runId\":\"run-unknown\",\"state\":\"UNKNOWN\",\"strategy\":\"unknown\",\"terminal\":false}")
                .contains("\"outcome\":\"unknown\"")
                .contains("\"known\":false")
                .contains("\"message\":\"No run status is recorded for this run id.\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void commandsCommandCanShowOneCommandById() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--id",
                "run-print-spec-output",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"commandId\":\"run-print-spec-output\"")
                .contains("\"query\":{\"surfaceId\":null,\"profileId\":null,\"resolvedSurfaceId\":null,\"category\":null,\"commandId\":\"run-print-spec-output\",\"contractJsonSchemaId\":null,\"filtered\":true}")
                .contains("\"matchingCommands\":1")
                .contains("\"categories\":[\"Run Specs\"]")
                .contains("\"categoryCounts\":{\"Run Specs\":1}")
                .contains("\"commandIds\":[\"run-print-spec-output\"]")
                .contains("\"id\":\"run-print-spec-output\"")
                .contains("\"command\":\"run <task> --print-spec --output <file>\"")
                .doesNotContain("\"id\":\"run-session-context\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void commandsCommandCombinesFiltersBeforeCommandIdLookup() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--surface",
                "assistant-agent",
                "--id",
                "workspace-inspect");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err())
                .contains("Unknown Andalus command id 'workspace-inspect'")
                .contains("run-assistant-surface")
                .doesNotContain("workspace-inspect,");
    }

    @Test
    void commandsCommandRejectsProfileSurfaceConflicts() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--surface",
                "assistant-agent",
                "--profile",
                "low-code-agent");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err())
                .contains("Andalus product profile 'low-code-agent'")
                .contains("belongs to surface 'workflow-platform'")
                .contains("not 'assistant-agent'");
    }

    @Test
    void commandsCommandRejectsUnknownCategoryFilter() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--category",
                "Future");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err())
                .contains("Unknown Andalus command category 'Future'")
                .contains("Run Specs")
                .contains("Workbench");
    }

    @Test
    void commandsCommandRejectsUnknownSurfaceFilter() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "commands",
                "--surface",
                "future-agent");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err())
                .contains("Unknown Andalus product surface 'future-agent'")
                .contains("coding-agent")
                .contains("assistant-agent");
    }

    @Test
    void workspaceCommandRendersLocalWorkspaceContext(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "workspace",
                "--path",
                workspace.toString());

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus workspace")
                .contains("pom.xml")
                .contains("maven")
                .contains("Workspace inspected locally");
    }

    @Test
    void workspaceCommandCanRenderJson(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "workspace",
                "--path",
                workspace.toString(),
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"rootPath\":\"" + workspace.toAbsolutePath().normalize() + "\"")
                .contains("\"buildFiles\":[\"pom.xml\"]")
                .contains("\"packageManagers\":[\"maven\"]");
    }

    @Test
    void runCommandCanAttachWorkspaceContext(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "draft repo plan",
                "--workspace",
                workspace.toString(),
                "--workspace-max-entries",
                "20");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("draft repo plan")
                .contains("workspace={")
                .contains("pom.xml")
                .contains("Inspect workspace and attach compact context");
    }

    @Test
    void harnessCommandRendersPlannedChecks(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "harness",
                "--path",
                workspace.toString());

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus harness")
                .contains("maven-compile")
                .contains("mvn -q compile -DskipTests")
                .contains("Harness plan generated locally");
    }

    @Test
    void harnessCommandCanRenderJson(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "harness",
                "--path",
                workspace.toString(),
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"workspace\":")
                .contains("\"checks\":[")
                .contains("\"id\":\"maven-compile\"")
                .contains("\"command\":[\"mvn\",\"-q\",\"compile\",\"-DskipTests\"]");
    }

    @Test
    void runCommandCanAttachHarnessContext(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "draft guarded change",
                "--workspace",
                workspace.toString(),
                "--harness",
                "--harness-required-only");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("draft guarded change")
                .contains("harness={")
                .contains("maven-compile")
                .contains("Plan harness checks and attach verification context");
    }

    @Test
    void runCommandCanRenderJson(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "draft json change",
                "--workspace",
                workspace.toString(),
                "--harness",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"runId\":")
                .contains("\"successful\":true")
                .contains("\"outcome\":\"terminal\"")
                .contains("\"handle\":")
                .contains("\"state\":\"COMPLETED\"")
                .contains("\"terminal\":true")
                .contains("\"metadata\":")
                .contains("\"surface\":\"coding-agent\"")
                .contains("\"surfacePolicy\":")
                .contains("\"surfacePolicyAssessment\":")
                .contains("\"skillAssessment\":")
                .contains("\"runReadiness\":")
                .contains("\"ready\":true")
                .contains("\"workspacePreferred\":true")
                .contains("\"workspace\":")
                .contains("\"harness\":")
                .contains("\"maven-compile\"");
    }

    @Test
    void runCommandCanAttachSessionUserAndAdditionalContext() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "answer with context",
                "--surface",
                "assistant-agent",
                "--session",
                "session-a",
                "--user",
                "user-a",
                "--context",
                "rag.collection=docs",
                "--context",
                "mcp.server=filesystem",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("\"session\":\"session-a\"")
                .contains("\"user\":\"user-a\"")
                .contains("\"context\":{\"rag.collection\":\"docs\",\"mcp.server\":\"filesystem\"}");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandCanReadPromptAndSystemPromptFromFiles(@TempDir Path workspace) throws Exception {
        Path promptFile = workspace.resolve("prompt.md");
        Path systemFile = workspace.resolve("system.md");
        Files.writeString(promptFile, "answer from prompt file");
        Files.writeString(systemFile, "use platform boundaries");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "--prompt-file",
                promptFile.toString(),
                "--system-file",
                systemFile.toString(),
                "--surface",
                "assistant-agent",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Prepared Andalus agent run for: answer from prompt file")
                .contains("\"systemPrompt\":\"use platform boundaries\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandCanReadPromptFromStdin() {
        TestConsole console = new TestConsole();
        ByteArrayInputStream input = new ByteArrayInputStream(
                "answer from stdin".getBytes(StandardCharsets.UTF_8));

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                input,
                console.outStream(),
                console.errStream(),
                "run",
                "--stdin",
                "--surface",
                "assistant-agent",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out()).contains("Prepared Andalus agent run for: answer from stdin");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandCanLoadRunSpecProperties(@TempDir Path workspace) throws Exception {
        Path spec = workspace.resolve("andalus-run.properties");
        Files.writeString(spec, """
                prompt=answer from spec
                systemPrompt=stay scoped
                tenantId=tenant-spec
                modelId=model-spec
                surfaceId=assistant-agent
                skills=rag,mcp
                context.rag.collection=docs
                """);
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "--spec",
                spec.toString(),
                "--dry-run",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"tenantId\":\"tenant-spec\"")
                .contains("\"modelId\":\"model-spec\"")
                .contains("\"surfaceId\":\"assistant-agent\"")
                .contains("\"systemPromptPresent\":true")
                .contains("\"skills\":[\"rag\",\"mcp\"]")
                .contains("\"rag.collection\":\"docs\"")
                .contains("\"ready\":true");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandFlagsOverrideRunSpecDefaults(@TempDir Path workspace) throws Exception {
        Path spec = workspace.resolve("andalus-run.properties");
        Files.writeString(spec, """
                prompt=answer from spec
                tenantId=tenant-spec
                surfaceId=assistant-agent
                context.rag.collection=docs
                """);
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "--spec",
                spec.toString(),
                "--tenant",
                "tenant-cli",
                "--context",
                "rag.collection=cli-docs",
                "--dry-run",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("\"tenantId\":\"tenant-cli\"")
                .contains("\"rag.collection\":\"cli-docs\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandCanPrintResolvedRunSpec() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "answer as spec",
                "--system",
                "stay scoped",
                "--tenant",
                "tenant-a",
                "--surface",
                "assistant-agent",
                "--skill",
                "rag",
                "--context",
                "rag.collection=docs",
                "--print-spec");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("specVersion=1" + System.lineSeparator())
                .contains("prompt=answer as spec" + System.lineSeparator())
                .contains("systemPrompt=stay scoped" + System.lineSeparator())
                .contains("tenantId=tenant-a" + System.lineSeparator())
                .contains("surfaceId=assistant-agent" + System.lineSeparator())
                .contains("skills=rag" + System.lineSeparator())
                .contains("context.rag.collection=docs" + System.lineSeparator());
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandCanPrintResolvedProfileSpec() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "fix this repo",
                "--profile",
                "openclaw-agent",
                "--print-spec");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("specVersion=1" + System.lineSeparator())
                .contains("profileId=openclaw-agent" + System.lineSeparator())
                .contains("prompt=fix this repo" + System.lineSeparator())
                .contains("surfaceId=coding-agent" + System.lineSeparator())
                .contains("skills=repo,tools,patching,mcp" + System.lineSeparator())
                .contains("workspaceEnabled=true" + System.lineSeparator())
                .contains("harnessEnabled=true" + System.lineSeparator())
                .contains("context.andalus.profile=openclaw-agent" + System.lineSeparator())
                .contains("requireReady=true" + System.lineSeparator());
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandCanPrintResolvedSpecAfterOverrides(@TempDir Path workspace) throws Exception {
        Path spec = workspace.resolve("andalus-run.properties");
        Files.writeString(spec, """
                prompt=answer from spec
                tenantId=tenant-spec
                surfaceId=assistant-agent
                context.rag.collection=docs
                """);
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "--spec",
                spec.toString(),
                "--tenant",
                "tenant-cli",
                "--context",
                "rag.collection=cli-docs",
                "--print-spec");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("specVersion=1" + System.lineSeparator())
                .contains("prompt=answer from spec" + System.lineSeparator())
                .contains("tenantId=tenant-cli" + System.lineSeparator())
                .contains("surfaceId=assistant-agent" + System.lineSeparator())
                .contains("context.rag.collection=cli-docs" + System.lineSeparator());
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandProfileOverridesSpecProfileButKeepsSpecFields(@TempDir Path workspace) throws Exception {
        Path spec = workspace.resolve("andalus-run.properties");
        Files.writeString(spec, """
                profileId=assistant-agent
                prompt=wire workflow from spec
                workflowId=custom-flow
                """);
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "--spec",
                spec.toString(),
                "--profile",
                "low-code-agent",
                "--print-spec");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("profileId=low-code-agent" + System.lineSeparator())
                .contains("prompt=wire workflow from spec" + System.lineSeparator())
                .contains("workflowId=custom-flow" + System.lineSeparator())
                .contains("surfaceId=workflow-platform" + System.lineSeparator())
                .contains("skills=workflow,hitl,observability" + System.lineSeparator())
                .contains("context.andalus.profile=low-code-agent" + System.lineSeparator())
                .contains("requireReady=true" + System.lineSeparator());
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandCanWriteResolvedRunSpecToFile(@TempDir Path workspace) throws Exception {
        Path output = workspace.resolve("resolved").resolve("andalus-run.properties");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "answer to file",
                "--surface",
                "assistant-agent",
                "--print-spec",
                "--output",
                output.toString());

        assertThat(exitCode).isZero();
        assertThat(console.out()).contains("Wrote Andalus run spec: " + output);
        assertThat(Files.readString(output))
                .contains("specVersion=1" + System.lineSeparator())
                .contains("prompt=answer to file" + System.lineSeparator())
                .contains("surfaceId=assistant-agent" + System.lineSeparator());
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandRefusesToOverwriteSpecWithoutForce(@TempDir Path workspace) throws Exception {
        Path output = workspace.resolve("andalus-run.properties");
        Files.writeString(output, "original=true\n");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "answer to file",
                "--surface",
                "assistant-agent",
                "--print-spec",
                "--output",
                output.toString());

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err()).contains("already exists").contains("--force");
        assertThat(Files.readString(output)).isEqualTo("original=true\n");

        TestConsole forceConsole = new TestConsole();
        int forceExit = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                forceConsole.outStream(),
                forceConsole.errStream(),
                "run",
                "forced answer",
                "--surface",
                "assistant-agent",
                "--print-spec",
                "--output",
                output.toString(),
                "--force");

        assertThat(forceExit).isZero();
        assertThat(Files.readString(output))
                .contains("specVersion=1" + System.lineSeparator())
                .contains("prompt=forced answer" + System.lineSeparator())
                .contains("surfaceId=assistant-agent" + System.lineSeparator());
        assertThat(forceConsole.err()).isEmpty();
    }

    @Test
    void specCommandCanValidateReadySpecAsJson(@TempDir Path workspace) throws Exception {
        Path spec = workspace.resolve("andalus-run.properties");
        Files.writeString(spec, """
                prompt=answer from spec
                surfaceId=assistant-agent
                skills=rag,mcp
                context.rag.collection=docs
                """);
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "spec",
                "validate",
                "--path",
                spec.toString(),
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"path\":\"" + spec)
                .contains("\"ready\":true")
                .contains("\"surfaceId\":\"assistant-agent\"")
                .contains("\"rag.collection\":\"docs\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void specCommandReportsMissingRequiredContext(@TempDir Path workspace) throws Exception {
        Path spec = workspace.resolve("andalus-run.properties");
        Files.writeString(spec, "prompt=answer from spec\n");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "spec",
                "validate",
                "--path",
                spec.toString());

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .contains("Andalus run spec")
                .contains("ready: no")
                .contains("missing context:")
                .contains("- workspace")
                .contains("Attach workspace context with --workspace <path>.");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void specCommandCanPrintSurfaceTemplate() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "spec",
                "template",
                "--surface",
                "coding-agent");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("specVersion=1" + System.lineSeparator())
                .contains("prompt=Describe the task here." + System.lineSeparator())
                .contains("surfaceId=coding-agent" + System.lineSeparator())
                .contains("skills=repo,tools,patching" + System.lineSeparator())
                .contains("workspaceEnabled=true" + System.lineSeparator())
                .contains("harnessEnabled=true" + System.lineSeparator());
        assertThat(console.err()).isEmpty();
    }

    @Test
    void specCommandCanPrintProductProfileTemplate() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "spec",
                "template",
                "--profile",
                "openclaw-agent");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("specVersion=1" + System.lineSeparator())
                .contains("profileId=openclaw-agent" + System.lineSeparator())
                .contains("surfaceId=coding-agent" + System.lineSeparator())
                .contains("skills=repo,tools,patching,mcp" + System.lineSeparator())
                .contains("workspaceEnabled=true" + System.lineSeparator())
                .contains("harnessEnabled=true" + System.lineSeparator())
                .contains("requireReady=true" + System.lineSeparator())
                .contains("context.andalus.profile=openclaw-agent" + System.lineSeparator());
        assertThat(console.err()).isEmpty();
    }

    @Test
    void specCommandCanWriteSurfaceTemplateToFile(@TempDir Path workspace) throws Exception {
        Path output = workspace.resolve("templates").resolve("assistant.properties");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "spec",
                "template",
                "--surface",
                "assistant-agent",
                "--output",
                output.toString());

        assertThat(exitCode).isZero();
        assertThat(console.out()).contains("Wrote Andalus run spec template: " + output);
        assertThat(Files.readString(output))
                .contains("specVersion=1" + System.lineSeparator())
                .contains("surfaceId=assistant-agent" + System.lineSeparator())
                .contains("skills=memory,rag,mcp" + System.lineSeparator());
        assertThat(console.err()).isEmpty();
    }

    @Test
    void specCommandRefusesTemplateOverwriteWithoutForce(@TempDir Path workspace) throws Exception {
        Path output = workspace.resolve("template.properties");
        Files.writeString(output, "original=true\n");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "spec",
                "template",
                "--surface",
                "assistant-agent",
                "--output",
                output.toString());

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err()).contains("already exists").contains("--force");
        assertThat(Files.readString(output)).isEqualTo("original=true\n");
    }

    @Test
    void runCommandRequiresExactlyOnePromptSource(@TempDir Path workspace) throws Exception {
        Path promptFile = workspace.resolve("prompt.md");
        Files.writeString(promptFile, "answer from prompt file");
        TestConsole console = new TestConsole();

        int missingExit = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "--surface",
                "assistant-agent");

        assertThat(missingExit).isEqualTo(2);
        assertThat(console.err()).contains("Prompt is required");

        TestConsole conflictConsole = new TestConsole();
        int conflictExit = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                conflictConsole.outStream(),
                conflictConsole.errStream(),
                "run",
                "inline prompt",
                "--prompt-file",
                promptFile.toString());

        assertThat(conflictExit).isEqualTo(2);
        assertThat(conflictConsole.err()).contains("Prompt must come from only one source");
    }

    @Test
    void runCommandRejectsInvalidContextEntry() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "answer with context",
                "--context",
                "rag.collection");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err()).contains("Context entries must use key=value");
    }

    @Test
    void runCommandCanPreflightMissingSurfaceContext() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "draft a change",
                "--preflight");

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .contains("Andalus run preflight")
                .contains("ready: no")
                .contains("surface ready: no")
                .contains("skills ready: yes")
                .contains("missing context:")
                .contains("workspace")
                .contains("Attach workspace context with --workspace <path>.")
                .contains("Attach planned verification checks with --harness.");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandCanPreflightReadySurfaceAsJson(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "draft a change",
                "--workspace",
                workspace.toString(),
                "--harness",
                "--skill",
                "repo",
                "--preflight",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"contract\":{\"schema\":\"andalus.run.planning\",\"version\":1,\"envelope\":\"run-preflight\"}")
                .contains("\"surfaceId\":\"coding-agent\"")
                .contains("\"ready\":true")
                .contains("\"surfacePolicyAssessment\":")
                .contains("\"skillAssessment\":")
                .contains("\"missingContextKeys\":[]")
                .contains("\"resolvedSkillIds\":[\"repo.context\"]")
                .contains("\"recommendations\":[]")
                .contains("\"routingHints\":[\"inspect-workspace\",\"plan-harness\",\"prefer-tool-use\"]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandPreflightReportsSkillReadinessProblems() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "answer with docs",
                "--surface",
                "assistant-agent",
                "--skill",
                "repo",
                "--skill",
                "missing",
                "--preflight");

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .contains("Andalus run preflight")
                .contains("ready: no")
                .contains("surface ready: yes")
                .contains("skills ready: no")
                .contains("unknown skills:")
                .contains("- missing")
                .contains("surface-incompatible skills:")
                .contains("- repo.context")
                .contains("skill recommendations:")
                .contains("Register or remove unknown skills: missing.")
                .contains("Choose skills that support surface 'assistant-agent': repo.context.");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandCanDryRunCorePreviewAsJson(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "draft a guarded change",
                "--system",
                "stay inside repo",
                "--workspace",
                workspace.toString(),
                "--harness",
                "--skill",
                "repo",
                "--dry-run",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .startsWith("{")
                .contains("\"contract\":{\"schema\":\"andalus.run.planning\",\"version\":1,\"envelope\":\"run-preview\"}")
                .contains("\"requestId\":")
                .contains("\"surfaceId\":\"coding-agent\"")
                .contains("\"systemPromptPresent\":true")
                .contains("\"workspaceAttached\":true")
                .contains("\"harnessAttached\":true")
                .contains("\"contextKeys\":")
                .contains("\"workspace\"")
                .contains("\"harness\"")
                .contains("\"surfacePolicyAssessment\":")
                .contains("\"ready\":true")
                .contains("\"maven-compile\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandDryRunReportsMissingRequiredContext() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "draft a change",
                "--dry-run");

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .contains("Andalus run preview")
                .contains("ready: no")
                .contains("workspace attached: no")
                .contains("missing context:")
                .contains("- workspace");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandDryRunReportsSkillReadinessProblems() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "answer with docs",
                "--surface",
                "assistant-agent",
                "--skill",
                "repo",
                "--skill",
                "missing",
                "--dry-run");

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .contains("Andalus run preview")
                .contains("ready: no")
                .contains("resolved skills:")
                .contains("- repo.context")
                .contains("unknown skills:")
                .contains("- missing")
                .contains("surface-incompatible skills:")
                .contains("- repo.context")
                .contains("skill recommendations:")
                .contains("Register or remove unknown skills: missing.")
                .contains("Choose skills that support surface 'assistant-agent': repo.context.");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void runCommandRejectsUnknownProductSurface() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "run",
                "draft a workflow",
                "--surface",
                "future-agent");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.out()).isEmpty();
        assertThat(console.err())
                .contains("Unknown Andalus product surface 'future-agent'")
                .contains("coding-agent")
                .contains("assistant-agent");
    }

    @Test
    void shortAndalusCommandKeepsLegacyAlias() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "--help");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Usage: andalus")
                .contains("andalus-aqli");
    }

    @Test
    void rootSdkOptionsConfigureDefaultTenantAndModel() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                null,
                console.outStream(),
                console.errStream(),
                "--default-tenant",
                "tenant-b",
                "--default-model",
                "model-b",
                "run",
                "draft a workflow");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("tenant-b")
                .contains("model-b")
                .contains("sdkMode=local");
    }

    @Test
    void rootSdkOptionsConfigureRunStoreRetention(@TempDir Path directory) {
        Path storePath = directory.resolve("runs.properties");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                null,
                console.outStream(),
                console.errStream(),
                "--run-store",
                storePath.toString(),
                "--run-store-max-runs",
                "7",
                "--run-store-max-events-per-run",
                "3",
                "status",
                "--readiness",
                "--json");

        assertThat(exitCode).describedAs(console.err()).isZero();
        assertThat(console.out())
                .contains("\"probe\":\"storage.retention\"")
                .contains("\"maxRuns\":7")
                .contains("\"maxEventsPerRun\":3")
                .contains("\"runsBounded\":true")
                .contains("\"eventsPerRunBounded\":true");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void rootSdkOptionsConfigureUnlimitedRunStoreRetention(@TempDir Path directory) {
        Path storePath = directory.resolve("runs.properties");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                null,
                console.outStream(),
                console.errStream(),
                "--run-store",
                storePath.toString(),
                "--run-store-retention",
                "unlimited",
                "status",
                "--readiness",
                "--json");

        assertThat(exitCode).describedAs(console.err()).isZero();
        assertThat(console.out())
                .contains("\"probe\":\"storage.retention\"")
                .contains("\"maxRuns\":0")
                .contains("\"maxEventsPerRun\":0")
                .contains("\"bounded\":false")
                .contains("\"unlimited\":true");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void rootSdkOptionsConfigureReadinessProfileRegistryFile(@TempDir Path directory) throws IOException {
        Path catalog = writeReadinessProfileCatalog(directory);
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                null,
                console.outStream(),
                console.errStream(),
                "--readiness-profile-registry",
                "hybrid",
                "--readiness-profile-file",
                catalog.toString(),
                "status",
                "--readiness",
                "--json");

        assertThat(exitCode).describedAs(console.err()).isZero();
        assertThat(console.out())
                .contains("\"readinessProfileId\":\"file-default\"")
                .contains("\"readinessProfileDefault\":true")
                .contains("\"componentCount\":2")
                .contains("\"componentReadinessIds\":[\"andalus.storage.readiness\",\"andalus.contract.integrity.readiness\"]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void rootSdkOptionsReportBlockedReadinessForUnavailableRegistryFile(@TempDir Path directory) {
        Path missing = directory.resolve("missing-readiness-profiles.properties");
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                null,
                console.outStream(),
                console.errStream(),
                "--readiness-profile-registry",
                "file",
                "--readiness-profile-file",
                missing.toString(),
                "status",
                "--readiness",
                "--json");

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .contains("\"readinessId\":\"andalus.platform.readiness\"")
                .contains("\"ready\":false")
                .contains("\"readinessProfileId\":\"unavailable\"")
                .contains("\"readinessProfileRegistryActiveSourceId\":\"file\"")
                .contains("\"componentReadinessId\":\"andalus.platform.readiness-profile-registry.readiness\"")
                .contains("\"code\":\"readiness_profile_source_unavailable\"")
                .contains("\"sourceId\":\"file\"")
                .contains("Readiness profile file does not exist:");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void rootSdkOptionsReportBlockedReadinessForInvalidRegistryPolicy() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                null,
                console.outStream(),
                console.errStream(),
                "--readiness-profile-validation-policy",
                "future",
                "status",
                "--readiness",
                "--json");

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .contains("\"readinessId\":\"andalus.platform.readiness\"")
                .contains("\"ready\":false")
                .contains("\"readinessProfileRegistryConfigValid\":false")
                .contains("\"componentReadinessId\":\"andalus.platform.readiness-profile-registry-config.readiness\"")
                .contains("\"code\":\"readiness_profile_validation_policy_unknown\"")
                .contains("\"field\":\"validationPolicyId\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void rootSdkOptionsConfigureRustfsReadinessProfileRegistryWithFallback() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                null,
                console.outStream(),
                console.errStream(),
                "--readiness-profile-registry",
                "rustfs",
                "--readiness-profile-object-endpoint",
                "http://localhost:9000",
                "--readiness-profile-object-bucket",
                "andalus",
                "--readiness-profile-object-prefix",
                "profiles/default",
                "--readiness-profile-fallback=true",
                "readiness-profiles",
                "sources",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("\"activeSourceId\":\"builtin\"")
                .contains("\"fallbackUsed\":true")
                .contains("\"sourceId\":\"rustfs\"")
                .contains("\"sourceType\":\"object_storage\"")
                .contains("\"location\":\"rustfs://andalus/profiles/default\"")
                .contains("\"available\":false")
                .contains("\"sourceId\":\"builtin\"")
                .contains("\"selected\":true")
                .contains("\"totalProfiles\":5");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void remoteSdkModeFailsClearlyUntilRemoteProviderIsInstalled() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                null,
                console.outStream(),
                console.errStream(),
                "--sdk-mode",
                "REMOTE",
                "--endpoint",
                "https://andalus.example.test",
                "status");

        assertThat(exitCode).isEqualTo(2);
        assertThat(console.err()).contains("No remote Andalus SDK provider is available");
    }

    @Test
    void standardsCommandShowsDefaultHealth() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "standards");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("Andalus standard alignment")
                .contains("status: ready")
                .contains("standards: 0")
                .contains("No standard-alignment reports are currently provided by this SDK.");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void standardsCommandCanGatePinnedKnownStandardsAsJson() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "standards",
                "--policy",
                "pinned-known",
                "--json");

        assertThat(exitCode).isEqualTo(1);
        assertThat(console.out())
                .contains("\"product\":\"Andalus\"")
                .contains("\"status\":\"blocked\"")
                .contains("\"ready\":false")
                .contains("\"missingStandardIds\":[\"a2a\",\"a2ui\",\"agentic-commerce\"]");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void standardsCommandShowsCatalog() {
        TestConsole console = new TestConsole();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                console.outStream(),
                console.errStream(),
                "standards",
                "--catalog",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(console.out())
                .contains("\"totalStandards\":3")
                .contains("\"standardIds\":[\"a2a\",\"a2ui\",\"agentic-commerce\"]")
                .contains("\"specUrl\":\"https://www.agenticcommerce.dev\"");
        assertThat(console.err()).isEmpty();
    }

    private static String runIdFromTextOutput(String output) {
        return output.lines()
                .filter(line -> line.startsWith("Andalus run "))
                .map(line -> line.substring("Andalus run ".length()))
                .findFirst()
                .orElseThrow();
    }

    private static Path writeReadinessProfileCatalog(Path directory) throws IOException {
        Path catalog = directory.resolve("readiness-profiles.properties");
        Files.writeString(
                catalog,
                """
                schema=andalus.platform.readiness-profiles
                version=1
                profileIds=file-default,file-production
                defaultProfileId=file-default
                productionProfileId=file-production
                profile.file-default.description=File default profile.
                profile.file-default.readinessIds=andalus.storage.readiness,andalus.contract.integrity.readiness
                profile.file-production.description=File production profile.
                profile.file-production.readinessIds=andalus.storage.readiness,andalus.contract.integrity.readiness,andalus.contract.coverage.readiness,andalus.skill-catalog.readiness,andalus.provider-capability.readiness,andalus.standard-alignment.readiness
                """);
        return catalog;
    }

    private static final class TestConsole {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final PrintStream outStream = new PrintStream(out);
        private final PrintStream errStream = new PrintStream(err);

        PrintStream outStream() {
            return outStream;
        }

        PrintStream errStream() {
            return errStream;
        }

        String out() {
            return out.toString();
        }

        String err() {
            return err.toString();
        }
    }
}
