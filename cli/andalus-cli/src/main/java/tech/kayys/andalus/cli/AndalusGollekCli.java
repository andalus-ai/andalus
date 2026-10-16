package tech.kayys.andalus.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import tech.kayys.andalus.cli.contract.AndalusContractCommands;
import tech.kayys.andalus.client.Andalus;
import tech.kayys.andalus.client.AndalusClient;
import tech.kayys.andalus.client.AndalusAqliSdk;
import tech.kayys.andalus.cli.bootstrap.AqliBootstrapService;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;

/**
 * Root picocli application for the Andalus agentic platform command line.
 *
 * <p>The root command owns SDK configuration and client construction, while
 * subcommands consume the shared {@link AndalusClient} through {@link AndalusCliContext}.</p>
 */
@Command(
        name = "andalus",
        aliases = "andalus-aqli",
        description = "Andalus agentic platform CLI. Compatibility alias: andalus-aqli.",
        mixinStandardHelpOptions = true,
        subcommands = {
               AndalusProjectCommand.class,
               AndalusAqliProxyCommand.class,
               AndalusPlatformCommands.StatusCommand.class,
               AndalusPlatformCommands.ProductsCommand.class,
               AndalusPlatformCommands.SdkBoundariesCommand.class,
               AndalusPlatformCommands.ReadinessProfilesCommand.class,
               AndalusPlatformCommands.ProfilesCommand.class,
               AndalusContextCommands.WorkspaceCommand.class,
               AndalusContextCommands.HarnessCommand.class,
               AndalusSpecCommands.SpecCommand.class,
               AndalusSkillCommands.SkillsCommand.class,
               AndalusProviderCapabilityCommands.ProvidersCommand.class,
               AndalusContractCommands.ContractsCommand.class,
               AndalusStandardsCommands.StandardsCommand.class,
               AndalusWorkbenchCommands.CommandsCommand.class,
               AndalusWorkbenchCommands.WorkbenchCommand.class,
               AndalusTuiCommands.TuiCommand.class,
               AndalusServeCommand.class,
               AndalusMemoryCommands.MemoryCommand.class,
               AndalusKnowledgeCommands.KnowledgeCommand.class,
               tech.kayys.andalus.cli.operator.OperatorCommand.class
        } )
 public final class AndalusAqliCli implements Runnable {

    private final AndalusAqliSdk injectedSdk;
    private final InputStream in;
    private final PrintStream out;
    private final PrintStream err;
    private final AndalusCliContext context;
    private AndalusAqliSdk resolvedSdk;
    private AndalusClient resolvedClient;

    @Mixin
    private AndalusCliSdkOptions sdkOptions = new AndalusCliSdkOptions();

    public AndalusAqliCli() {
        this(null, System.in, System.out, System.err);
    }

    AndalusAqliCli(AndalusAqliSdk injectedSdk, PrintStream out, PrintStream err) {
        this(injectedSdk, System.in, out, err);
    }

    AndalusAqliCli(AndalusAqliSdk injectedSdk, InputStream in, PrintStream out, PrintStream err) {
        this.injectedSdk = injectedSdk;
        this.in = in == null ? InputStream.nullInputStream() : in;
        this.out = out;
        this.err = err;
        this.context = new AndalusCliContext(
                this::client,
                this.in,
                this.out,
                this.err);
    }

    public static int execute(String... args) {
        return execute(null, System.out, System.err, args);
    }

    static int execute(AndalusAqliSdk service, PrintStream out, PrintStream err, String... args) {
        return execute(service, System.in, out, err, args);
    }

    static int execute(AndalusAqliSdk service, InputStream in, PrintStream out, PrintStream err, String... args) {
        CommandLine commandLine = new CommandLine(new AndalusAqliCli(service, in, out, err));
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(err, true));
        return commandLine.execute(args);
    }

    public static void main(String[] args) {
        System.setProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager");
        int exitCode = 1;
        try {
            exitCode = execute(args);
        } catch (Throwable t) {
            // Suppress spurious picocli classloading errors that sometimes occur
            // during teardown/exit on certain JVM or uber-jar setups.
            if (t instanceof NoClassDefFoundError && t.getMessage() != null && t.getMessage().contains("picocli")) {
                exitCode = 0;
            } else {
                t.printStackTrace();
            }
        }
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, out);
    }

    public boolean isIgnoreConfig() { return sdkOptions != null && Boolean.TRUE.equals(sdkOptions.ignoreConfig); }

    public AndalusAqliSdk sdk() {
        if (injectedSdk != null) {
            return injectedSdk;
        }
        if (resolvedSdk == null) {
            resolvedSdk = Andalus.create(sdkOptions.toConfig());

            // Apply preferred provider from ~/.andalus/config.json if present
            try {
                Path cfg = Paths.get(System.getProperty("user.home"), ".andalus", "config.json");
                if (Files.exists(cfg) && !Boolean.TRUE.equals(sdkOptions.ignoreConfig)) {
                        boolean debug = Boolean.getBoolean("andalus.cli.debug");
                    String content = Files.readString(cfg);
                    Pattern p = Pattern.compile("\"provider\"\s*:\s*\"([^\"]+)\"");
                    Matcher m = p.matcher(content);
                    if (m.find()) {
                        String provider = m.group(1).trim();
                        
                        // Bootstrap check if provider is aqli
                        if ("aqli".equals(provider)) {
                            // Determine active profile from config or system properties
                            String profile = System.getProperty("andalus.profile", "development");
                            try {
                                Pattern profilePattern = Pattern.compile("\"profile\"\s*:\s*\"([^\"]+)\"");
                                Matcher profileMatcher = profilePattern.matcher(content);
                                if (profileMatcher.find()) {
                                    profile = profileMatcher.group(1).trim();
                                }
                                AqliBootstrapService.ensureRunning(profile);
                            } catch (Exception ex) {
                                logToFile("Error during Aqli bootstrap: " + ex.getMessage());
                            }
                            // Apply fallback if bootstrap altered it
                            String fallback = System.getProperty("andalus.fallback.provider");
                            if (fallback != null) {
                                provider = fallback;
                            }
                        }

                        try {
                            // Use reflection so this compiles against older SDK jars that may not
                            // have provider APIs yet.
                            Method listMethod = null;
                            Method setMethod = null;
                            try {
                                listMethod = resolvedSdk.getClass().getMethod("listAvailableProviders");
                            } catch (NoSuchMethodException nsme) {
                                // ignore
                            }
                            try {
                                setMethod = resolvedSdk.getClass().getMethod("setPreferredProvider", String.class);
                            } catch (NoSuchMethodException nsme) {
                                // ignore
                            }

                            List<?> available = List.of();
                            if (listMethod != null) {
                                Object availObj = listMethod.invoke(resolvedSdk);
                                if (availObj instanceof List<?> l) {
                                    available = l;
                                }
                            }

                            boolean found = false;
                            for (Object o : available) {
                                if (o == null) continue;
                                if (o instanceof String s) {
                                    if (s.equals(provider)) { found = true; break; }
                                } else {
                                    try {
                                        Method idMethod = o.getClass().getMethod("id");
                                        Object idv = idMethod.invoke(o);
                                        if (idv != null && provider.equals(String.valueOf(idv))) { found = true; break; }
                                    } catch (NoSuchMethodException ignored) {
                                    }
                                }
                            }

                            if (!found) {
                                String known = available.isEmpty() ? "<unknown>" : available.stream().map(o -> {
                                    if (o instanceof String s) return s;
                                    try { Method idM = o.getClass().getMethod("id"); Object v = idM.invoke(o); return v == null ? "<unknown>" : String.valueOf(v); } catch (Exception ex) { return "<unknown>"; }
                                }).toList().toString();
                                logToFile("Preferred provider '" + provider + "' from ~/.andalus/config.json is not available. Known providers: " + known);

                                // Attempt to auto-load provider JAR from local Maven repository if present
                                try {
                                    Method loadMethod = null; logToFile("DEBUG: probing sdk for dynamic loadProviderJar support...");
                                    try { loadMethod = resolvedSdk.getClass().getMethod("loadProviderJar", String.class); logToFile("DEBUG: sdk exposes loadProviderJar"); } catch (NoSuchMethodException nsme) { logToFile("DEBUG: sdk does NOT expose loadProviderJar"); }
                                    try {
                                        loadMethod = resolvedSdk.getClass().getMethod("loadProviderJar", String.class);
                                    } catch (NoSuchMethodException nsme) {
                                        // SDK may not support dynamic loading
                                    }
                                    Path candidateDir = Paths.get(System.getProperty("user.home"), ".m2", "repository", "tech", "kayys", "aqli", "aqli-plugin-" + provider);
                                    if (Files.exists(candidateDir)) {
                                        java.util.Optional<Path> jarOpt = Files.walk(candidateDir)
                                                .filter(x -> x.getFileName().toString().endsWith(".jar"))
                                                .findFirst();
                                        if (jarOpt.isPresent()) {
                                            Path jarPath = jarOpt.get();
                                            logToFile("DEBUG: Found provider JAR candidate: " + jarPath);
                                            // If SDK exposes loadProviderJar, use it; else try to register capability via provider registry reflectively
                                            if (loadMethod != null) {
                                                try {
                                                    loadMethod.invoke(resolvedSdk, jarPath.toString());
                                                    if (debug) logToFile("DEBUG: Loaded provider JAR via SDK: " + jarPath);
                                                } catch (Throwable t) {
                                                    logToFile("Warning: failed to invoke SDK.loadProviderJar: " + t.getMessage());
                                                }
                                            } else {                                                try {
                                                    // reflectively register a capability descriptor into providerCapabilityRegistry
                                                    Method regMethod = resolvedSdk.getClass().getMethod("providerCapabilityRegistry");
                                                    Object registry = regMethod.invoke(resolvedSdk);
                                                    if (registry != null) {
                                                        Class<?> descClass = Class.forName("tech.kayys.andalus.capability.AndalusProviderCapabilityDescriptor");
                                                        Class<?> stateClass = Class.forName("tech.kayys.andalus.capability.AndalusProviderCapabilityState");
                                                        java.lang.reflect.Constructor<?> ctor = descClass.getConstructor(
                                                                String.class, String.class, String.class, String.class,
                                                                String.class, String.class, String.class, stateClass,
                                                                java.util.List.class, java.util.List.class, java.util.List.class, java.util.Map.class);
                                                        String moduleId = jarPath.getFileName().toString().replaceAll("\\\\.jar$", "");
                                                        String providerIdGuess = provider;
                                                        String capabilityId = providerIdGuess + ".inference";
                                                        Object stateVal = java.lang.Enum.valueOf((Class<Enum>) stateClass, "AVAILABLE");
                                                        Object descriptor = ctor.newInstance(
                                                                capabilityId,
                                                                providerIdGuess,
                                                                "aqli",
                                                                moduleId,
                                                                "inference",
                                                                Character.toUpperCase(providerIdGuess.charAt(0)) + providerIdGuess.substring(1) + " Provider",
                                                                "Dynamically registered provider from JAR: " + jarPath.toString(),
                                                                stateVal,
                                                                java.util.List.of("coding-agent", "assistant-agent"),
                                                                java.util.List.of(),
                                                                java.util.List.of("aqli", "provider", "inference"),
                                                                java.util.Map.of("jar", jarPath.toString())
                                                        );
                                                        Method registerMethod = registry.getClass().getMethod("register", descClass);
                                                        registerMethod.invoke(registry, descriptor);
                                                        if (debug) logToFile("DEBUG: Registered provider capability for: " + providerIdGuess);
                                                        found = true;
                                                        if (setMethod != null) {
                                                            setMethod.invoke(resolvedSdk, providerIdGuess);
                                                            logToFile("Applied preferred provider from ~/.andalus/config.json after registration: " + providerIdGuess);
                                                        }
                                                    }
                                                } catch (Throwable t) {
                                                    logToFile("Warning: failed to register provider capability reflectively: " + t.getMessage());
                                                }
                                            }
                                            // requery availability
                                            if (listMethod != null) {
                                                try {
                                                    Object availObj2 = listMethod.invoke(resolvedSdk);
                                                    if (availObj2 instanceof List<?> l2) {
                                                        for (Object o2 : l2) {
                                                            if (o2 instanceof String s2 && s2.equals(provider)) { found = true; break; }
                                                        }
                                                    }
                                                } catch (Throwable ignored) {
                                                }
                                            }
                                        }
                                    }
                                } catch (Throwable t) {
                                    logToFile("Warning: provider auto-load attempt failed: " + t.getMessage());
                                }
                            } else if (setMethod != null) {
                                setMethod.invoke(resolvedSdk, provider);
                                logToFile("Applied preferred provider from ~/.andalus/config.json: " + provider);
                            } else {
                                logToFile("Note: preferred provider '" + provider + "' is available, but SDK does not expose setPreferredProvider API yet.");
                            }
                        } catch (Throwable t) {
                            logToFile("Warning: failed to apply preferred provider '" + provider + "': " + t.getMessage());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return resolvedSdk;
    }

    private void logToFile(String msg) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(System.getProperty("user.home"), ".andalus", "logs");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path file = dir.resolve("andalus-cli.log");
            String line = java.time.Instant.now().toString() + " " + msg + System.lineSeparator();
            java.nio.file.Files.writeString(file, line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            if (Boolean.getBoolean("andalus.cli.debug")) {
                err.println("  Logging failed: " + e.getMessage());
            }
        }
    }

    private AndalusClient client() {
        if (resolvedClient == null) {
            resolvedClient = Andalus.client(sdk());
        }
        return resolvedClient;
    }

    public AndalusCliContext context() {
        return context;
    }
}
