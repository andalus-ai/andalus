package tech.kayys.andalus.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;
import tech.kayys.andalus.client.ProductProfile;
import tech.kayys.andalus.client.AndalusClient;
import tech.kayys.andalus.client.AndalusPlatformApi;
import tech.kayys.andalus.client.AndalusPlatformStatus;
import tech.kayys.andalus.boundry.AndalusSdkBoundary;
import tech.kayys.andalus.readiness.AndalusPlatformReadinessProfileDescriptor;
import tech.kayys.andalus.readiness.AndalusPlatformReadinessProfileExternalReaderProviderDiscoveryReport;
import tech.kayys.andalus.readiness.AndalusPlatformReadinessProfileRegistryConfigDiagnostics;
import tech.kayys.andalus.readiness.AndalusPlatformReadinessProfileFileSource;
import tech.kayys.andalus.readiness.AndalusPlatformReadinessProfileRegistryPreflightReport;
import tech.kayys.andalus.readiness.AndalusPlatformReadinessProfileRegistryResolution;
import tech.kayys.andalus.readiness.AndalusPlatformReadinessProfileValidationPolicy;
import tech.kayys.andalus.readiness.AndalusPlatformReadinessProfileValidationPolicyDescriptor;
import tech.kayys.andalus.readiness.AndalusPlatformReadinessProfileValidationReport;
import tech.kayys.andalus.readiness.AndalusReadinessReport;

import java.util.List;
import java.util.concurrent.Callable;

public final class AndalusPlatformCommands {

    private AndalusPlatformCommands() {
    }

    @Command(name = "status", description = "Show platform boundary and adapter status.")
    public static final class StatusCommand implements Callable<Integer> {
        @ParentCommand
        public AndalusAqliCli parent;

        @Option(names = "--json", description = "Render status as a compact JSON object.")
        boolean json;

        @Option(names = "--readiness", description = "Render aggregate production readiness instead of boundary status.")
        boolean readiness;

        @Option(
                names = "--readiness-profile",
                description = "Select readiness profile id from the configured readiness profile registry.")
        String readinessProfile;

        @Override
        public Integer call() {
            try {
                AndalusCliContext context = parent.context();
                AndalusPlatformApi platform = context.client().platform();
                if (readiness || hasReadinessProfile()) {
                    AndalusReadinessReport report = hasReadinessProfile()
                            ? platform.readiness(readinessProfile)
                            : platform.readiness();
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            () -> platform.readinessJson(report),
                            () -> AndalusReadinessTextFormat.text(report));
                    return report.exitCode();
                }
                AndalusPlatformStatus status = platform.status();
                AndalusCliRender.jsonOrText(
                        context.out(),
                        json,
                        () -> platform.statusJson(status),
                        () -> AndalusStatusTextFormat.text(status));
                return 0;
            } catch (RuntimeException e) {
                return parent.context().commandFailure(e);
            }
        }

        private boolean hasReadinessProfile() {
            return readinessProfile != null;
        }
    }

    @Command(name = "products", description = "List product surfaces that can be powered by the Andalus agent engine.")
    static final class ProductsCommand implements Callable<Integer> {
        @ParentCommand
        public AndalusAqliCli parent;

        @Option(names = "--json", description = "Render product surfaces and policies as compact JSON.")
        boolean json;

        @Override
        public Integer call() {
            try {
                AndalusCliContext context = parent.context();
                AndalusPlatformApi platform = context.client().platform();
                AndalusCliRender.jsonOrText(
                        context.out(),
                        json,
                        platform::productCatalogJson,
                        () -> AndalusProductTextFormat.text(
                                platform.productSurfaces(),
                                platform.productSurfacePolicies(),
                                platform.productProfiles()));
                return 0;
            } catch (RuntimeException e) {
                return parent.context().commandFailure(e);
            }
        }
    }

    @Command(name = "sdk-boundaries", description = "List SDK ownership boundaries for package and API separation.")
    static final class SdkBoundariesCommand implements Callable<Integer> {
        @ParentCommand
        public AndalusAqliCli parent;

        @Parameters(index = "0", arity = "0..1", description = "Optional SDK boundary id to inspect.")
        String boundaryId;

        @Option(names = "--json", description = "Render SDK boundaries as compact JSON.")
        boolean json;

        @Override
        public Integer call() {
            try {
                AndalusCliContext context = parent.context();
                AndalusPlatformApi platform = context.client().platform();
                if (boundaryId == null || boundaryId.isBlank()) {
                    List<AndalusSdkBoundary> boundaries = platform.sdkBoundaries();
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            platform::sdkBoundaryCatalogJson,
                            () -> AndalusSdkBoundaryTextFormat.text(boundaries));
                    return 0;
                }
                AndalusSdkBoundary boundary = platform.sdkBoundary(boundaryId);
                AndalusCliRender.jsonOrText(
                        context.out(),
                        json,
                        () -> platform.sdkBoundaryJson(boundaryId),
                        () -> AndalusSdkBoundaryTextFormat.detailText(boundary));
                return 0;
            } catch (RuntimeException e) {
                return parent.context().commandFailure(e);
            }
        }
    }

    @Command(
            name = "readiness-profiles",
            description = "List platform readiness profiles and component bindings.",
            subcommands = {
                    ReadinessProfilesCommand.ConfigCommand.class,
                    ReadinessProfilesCommand.InspectCommand.class,
                    ReadinessProfilesCommand.PoliciesCommand.class,
                    ReadinessProfilesCommand.PreflightCommand.class,
                    ReadinessProfilesCommand.ProvidersCommand.class,
                    ReadinessProfilesCommand.SourcesCommand.class
            })
    static final class ReadinessProfilesCommand implements Callable<Integer> {
        @ParentCommand
        public AndalusAqliCli parent;

        @Option(names = "--json", description = "Render readiness profiles as compact JSON.")
        boolean json;

        @Option(names = "--check", description = "Validate readiness profile bindings instead of listing profiles.")
        boolean check;

        @Option(
                names = "--validation-policy",
                description = "Validation policy id for --check: strict, relaxed, strict-without-profile-roles, strict-without-full-coverage, or relaxed-with-full-coverage.")
        String validationPolicy;

        @Override
        public Integer call() {
            try {
                AndalusCliContext context = parent.context();
                AndalusPlatformApi platform = context.client().platform();
                if (check) {
                    AndalusPlatformReadinessProfileValidationReport report = validationPolicy == null
                            ? platform.readinessProfileValidation()
                            : platform.readinessProfileValidation(validationPolicy);
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            () -> platform.readinessProfileValidationJson(report),
                            () -> AndalusReadinessProfileTextFormat.validationText(report));
                    return report.valid() ? 0 : 1;
                }
                List<AndalusPlatformReadinessProfileDescriptor> profiles =
                        platform.readinessProfiles();
                AndalusCliRender.jsonOrText(
                        context.out(),
                        json,
                        () -> platform.readinessProfilesJson(profiles),
                        () -> AndalusReadinessProfileTextFormat.text(profiles));
                return 0;
            } catch (RuntimeException e) {
                return parent.context().commandFailure(e);
            }
        }

        AndalusCliContext context() {
            return parent.context();
        }

        @Command(name = "config", description = "Inspect readiness profile registry configuration diagnostics.")
        static final class ConfigCommand implements Callable<Integer> {
            @ParentCommand
            ReadinessProfilesCommand parent;

            @Option(names = "--json", description = "Render readiness profile registry config diagnostics as JSON.")
            boolean json;

            @Override
            public Integer call() {
                try {
                    AndalusCliContext context = parent.context();
                    AndalusPlatformApi platform = context.client().platform();
                    AndalusPlatformReadinessProfileRegistryConfigDiagnostics diagnostics =
                            platform.readinessProfileRegistryConfigDiagnostics();
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            () -> platform.readinessProfileRegistryConfigDiagnosticsJson(diagnostics),
                            () -> AndalusReadinessProfileTextFormat.registryConfigDiagnosticsText(diagnostics));
                    return diagnostics.valid() ? 0 : 1;
                } catch (RuntimeException e) {
                    return parent.context().commandFailure(e);
                }
            }
        }

        @Command(name = "inspect", description = "Inspect one platform readiness profile.")
        static final class InspectCommand implements Callable<Integer> {
            @ParentCommand
            ReadinessProfilesCommand parent;

            @Parameters(index = "0", description = "Readiness profile id.")
            String profileId;

            @Option(names = "--json", description = "Render readiness profile as compact JSON.")
            boolean json;

            @Override
            public Integer call() {
                try {
                    AndalusCliContext context = parent.context();
                    AndalusPlatformApi platform = context.client().platform();
                    AndalusPlatformReadinessProfileDescriptor profile =
                            platform.readinessProfile(profileId);
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            () -> platform.readinessProfileDetailJson(profile),
                            () -> AndalusReadinessProfileTextFormat.detailText(profile));
                    return 0;
                } catch (RuntimeException e) {
                    return parent.context().commandFailure(e);
                }
            }
        }

        @Command(name = "policies", description = "List readiness profile validation policies.")
        static final class PoliciesCommand implements Callable<Integer> {
            @ParentCommand
            ReadinessProfilesCommand parent;

            @Option(names = "--json", description = "Render readiness profile validation policies as compact JSON.")
            boolean json;

            @Override
            public Integer call() {
                try {
                    AndalusCliContext context = parent.context();
                    AndalusPlatformApi platform = context.client().platform();
                    List<AndalusPlatformReadinessProfileValidationPolicyDescriptor> policies =
                            platform.readinessProfileValidationPolicies();
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            () -> platform.readinessProfileValidationPoliciesJson(policies),
                            () -> AndalusReadinessProfileTextFormat.validationPoliciesText(policies));
                    return 0;
                } catch (RuntimeException e) {
                    return parent.context().commandFailure(e);
                }
            }
        }

        @Command(name = "preflight", description = "Run production preflight for readiness profile registry sources and providers.")
        static final class PreflightCommand implements Callable<Integer> {
            @ParentCommand
            ReadinessProfilesCommand parent;

            @Option(names = "--json", description = "Render readiness profile registry preflight as JSON.")
            boolean json;

            @Override
            public Integer call() {
                try {
                    AndalusCliContext context = parent.context();
                    AndalusPlatformApi platform = context.client().platform();
                    AndalusPlatformReadinessProfileRegistryPreflightReport report =
                            platform.readinessProfileRegistryPreflight();
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            () -> platform.readinessProfileRegistryPreflightJson(report),
                            () -> AndalusReadinessProfileTextFormat.registryPreflightText(report));
                    return report.exitCode();
                } catch (RuntimeException e) {
                    return parent.context().commandFailure(e);
                }
            }
        }

        @Command(name = "providers", description = "Inspect readiness profile external reader provider discovery.")
        static final class ProvidersCommand implements Callable<Integer> {
            @ParentCommand
            ReadinessProfilesCommand parent;

            @Option(names = "--json", description = "Render readiness profile external reader provider diagnostics as JSON.")
            boolean json;

            @Override
            public Integer call() {
                try {
                    AndalusCliContext context = parent.context();
                    AndalusPlatformApi platform = context.client().platform();
                    AndalusPlatformReadinessProfileExternalReaderProviderDiscoveryReport report =
                            platform.readinessProfileExternalReaderProviderDiscovery();
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            () -> platform.readinessProfileExternalReaderProviderDiscoveryJson(report),
                            () -> AndalusReadinessProfileTextFormat.externalReaderProviderDiscoveryText(report));
                    return report.exitCode();
                } catch (RuntimeException e) {
                    return parent.context().commandFailure(e);
                }
            }
        }

        @Command(name = "sources", description = "Inspect readiness profile registry source resolution.")
        static final class SourcesCommand implements Callable<Integer> {
            @ParentCommand
            ReadinessProfilesCommand parent;

            @Option(names = "--json", description = "Render readiness profile source resolution as compact JSON.")
            boolean json;

            @Option(names = "--file", description = "Resolve a readiness profile properties file with built-in fallback.")
            String file;

            @Option(
                    names = "--validation-policy",
                    description = "Validation policy id for source resolution: strict, relaxed, strict-without-profile-roles, strict-without-full-coverage, or relaxed-with-full-coverage.")
            String validationPolicy;

            @Override
            public Integer call() {
                try {
                    AndalusCliContext context = parent.context();
                    AndalusPlatformApi platform = context.client().platform();
                    AndalusPlatformReadinessProfileRegistryResolution resolution = resolve(platform);
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            () -> platform.readinessProfileRegistryResolutionJson(resolution),
                            () -> AndalusReadinessProfileTextFormat.registryResolutionText(resolution));
                    return resolution.valid() ? 0 : 1;
                } catch (RuntimeException e) {
                    return parent.context().commandFailure(e);
                }
            }

            private AndalusPlatformReadinessProfileRegistryResolution resolve(AndalusPlatformApi platform) {
                AndalusPlatformReadinessProfileValidationPolicy policy = validationPolicy == null
                        ? null
                        : platform.readinessProfileValidationPolicy(validationPolicy);
                if (file == null || file.isBlank()) {
                    return platform.readinessProfileRegistryResolution(policy);
                }
                return platform.readinessProfileRegistryResolution(
                        AndalusPlatformReadinessProfileFileSource.of(file),
                        policy);
            }
        }
    }

    @Command(
            name = "profiles",
            description = "List reusable Andalus product profiles.",
            subcommands = {
                    ProfilesCommand.InspectCommand.class
            })
    static final class ProfilesCommand implements Callable<Integer> {
        @ParentCommand
        public AndalusAqliCli parent;

        @Option(names = "--surface", description = "Filter profiles by product surface id.")
        String surfaceId;

        @Option(names = "--json", description = "Render product profiles as compact JSON.")
        boolean json;

        @Override
        public Integer call() {
            try {
                AndalusCliContext context = parent.context();
                AndalusClient client = context.client();
                AndalusPlatformApi platform = client.platform();
                List<ProductProfile> profiles = surfaceId == null || surfaceId.isBlank()
                        ? platform.productProfiles()
                        : platform.productProfilesForSurface(surfaceId);
                AndalusCliRender.jsonOrText(
                        context.out(),
                        json,
                        () -> platform.profilesJson(surfaceId, profiles),
                        () -> AndalusProfileTextFormat.text(client.productName(), surfaceId, profiles));
                return 0;
            } catch (RuntimeException e) {
                return parent.context().commandFailure(e);
            }
        }

        AndalusCliContext context() {
            return parent.context();
        }

        @Command(name = "inspect", description = "Inspect one reusable Andalus product profile.")
        static final class InspectCommand implements Callable<Integer> {
            @ParentCommand
            ProfilesCommand parent;

            @Parameters(index = "0", description = "Product profile id.")
            String profileId;

            @Option(names = "--json", description = "Render product profile as compact JSON.")
            boolean json;

            @Override
            public Integer call() {
                try {
                    AndalusCliContext context = parent.context();
                    AndalusClient client = context.client();
                    ProductProfile profile = client.platform().productProfile(profileId);
                    AndalusCliRender.jsonOrText(
                            context.out(),
                            json,
                            () -> client.platform().profileDetailJson(profile),
                            () -> AndalusProfileTextFormat.detailText(client.productName(), profile));
                    return 0;
                } catch (RuntimeException e) {
                    return parent.context().commandFailure(e);
                }
            }
        }
    }
}
