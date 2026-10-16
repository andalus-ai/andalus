package tech.kayys.andalus.cli;

import picocli.CommandLine.Option;
import tech.kayys.andalus.agent.store.AgentRunStoreBackupRetentionPolicy;
import tech.kayys.andalus.agent.store.AgentRunStoreRetentionPolicy;
import tech.kayys.andalus.client.AndalusAqliSdkConfig;
import tech.kayys.andalus.client.AndalusAqliSdkProvider;
import tech.kayys.andalus.readiness.AndalusPlatformReadinessProfileRegistryConfig;

import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;

/**
 * Shared root CLI options that resolve SDK, run-store, and readiness-profile
 * configuration from flags and environment variables.
 */
final class AndalusCliSdkOptions {

    @Option(
            names = "--sdk-mode",
            description = "SDK mode: ${COMPLETION-CANDIDATES}. Env: ANDALUS_SDK_MODE.")
    AndalusAqliSdkProvider.Mode mode;

    @Option(names = "--endpoint", description = "Remote Andalus API endpoint. Env: ANDALUS_ENDPOINT.")
    String endpoint;

    @Option(names = "--api-key", description = "Remote Andalus API key. Env: ANDALUS_API_KEY.")
    String apiKey;

    @Option(names = "--default-tenant", description = "Default tenant for SDK requests. Env: ANDALUS_TENANT.")
    String defaultTenantId;

    @Option(names = "--default-model", description = "Default Aqli model for SDK requests. Env: ANDALUS_MODEL.")
    String defaultModelId;

    @Option(names = "--ignore-config", description = "Ignore ~/.andalus/config.json and prefer CLI flags/env. Env: ANDALUS_IGNORE_CONFIG.")
    Boolean ignoreConfig;

    @Option(names = "--run-store", description = "Local run status store file. Env: ANDALUS_RUN_STORE.")
    String runStorePath;

    @Option(
            names = "--run-store-retention",
            description = "Run-store retention mode: default, bounded, or unlimited. Env: ANDALUS_RUN_STORE_RETENTION.")
    String runStoreRetentionMode;

    @Option(
            names = "--run-store-max-runs",
            description = "Maximum retained local run statuses; 0 disables the run limit. Env: ANDALUS_RUN_STORE_MAX_RUNS.")
    Integer runStoreMaxRuns;

    @Option(
            names = "--run-store-max-events-per-run",
            description = "Maximum retained local run events per run; 0 disables the event limit. Env: ANDALUS_RUN_STORE_MAX_EVENTS_PER_RUN.")
    Integer runStoreMaxEventsPerRun;

    @Option(
            names = "--run-store-backup-retention",
            description = "Run-store compaction backup retention mode: default, bounded, or unlimited. Env: ANDALUS_RUN_STORE_BACKUP_RETENTION.")
    String runStoreBackupRetentionMode;

    @Option(
            names = "--run-store-max-backups",
            description = "Maximum retained run-store compaction backups; 0 keeps all backups. Env: ANDALUS_RUN_STORE_MAX_BACKUPS.")
    Integer runStoreMaxBackups;

    @Option(
            names = "--readiness-profile-registry",
            description = "Readiness profile registry: builtin, file, database, object-storage, hybrid, s3, rustfs, or minio. Env: ANDALUS_READINESS_PROFILE_REGISTRY.")
    String readinessProfileRegistry;

    @Option(
            names = "--readiness-profile-file",
            description = "Readiness profile properties file path. Env: ANDALUS_READINESS_PROFILE_FILE.")
    String readinessProfileFile;

    @Option(
            names = "--readiness-profile-database-url",
            description = "Readiness profile database JDBC URL. Env: ANDALUS_READINESS_PROFILE_DATABASE_URL.")
    String readinessProfileDatabaseUrl;

    @Option(
            names = "--readiness-profile-object-provider",
            description = "Readiness profile object-storage provider: s3, rustfs, or minio. Env: ANDALUS_READINESS_PROFILE_OBJECT_PROVIDER.")
    String readinessProfileObjectProvider;

    @Option(
            names = "--readiness-profile-object-endpoint",
            description = "Readiness profile object-storage endpoint. Env: ANDALUS_READINESS_PROFILE_OBJECT_ENDPOINT.")
    String readinessProfileObjectEndpoint;

    @Option(
            names = "--readiness-profile-object-bucket",
            description = "Readiness profile object-storage bucket. Env: ANDALUS_READINESS_PROFILE_OBJECT_BUCKET.")
    String readinessProfileObjectBucket;

    @Option(
            names = "--readiness-profile-object-region",
            description = "Readiness profile object-storage region. Env: ANDALUS_READINESS_PROFILE_OBJECT_REGION.")
    String readinessProfileObjectRegion;

    @Option(
            names = {"--readiness-profile-object-key", "--readiness-profile-object-prefix"},
            description = "Readiness profile object-storage key. Env: ANDALUS_READINESS_PROFILE_OBJECT_KEY or ANDALUS_READINESS_PROFILE_OBJECT_PREFIX.")
    String readinessProfileObjectPrefix;

    @Option(
            names = "--readiness-profile-object-path-style",
            description = "Use path-style access for S3-compatible readiness profile storage. Env: ANDALUS_READINESS_PROFILE_OBJECT_PATH_STYLE.")
    Boolean readinessProfileObjectPathStyle;

    @Option(
            names = "--readiness-profile-object-credentials",
            description = "Secret or credentials reference for readiness profile object storage. Env: ANDALUS_READINESS_PROFILE_OBJECT_CREDENTIALS.")
    String readinessProfileObjectCredentials;

    @Option(
            names = "--readiness-profile-fallback",
            description = "Enable built-in readiness profile fallback. Env: ANDALUS_READINESS_PROFILE_FALLBACK.")
    Boolean readinessProfileFallback;

    @Option(
            names = "--readiness-profile-validation-policy",
            description = "Default readiness profile validation policy. Env: ANDALUS_READINESS_PROFILE_VALIDATION_POLICY.")
    String readinessProfileValidationPolicy;

    AndalusAqliSdkConfig toConfig() {
        AndalusAqliSdkProvider.Mode resolvedMode = mode == null ? modeFromEnv() : mode;
        AndalusAqliSdkConfig config = new AndalusAqliSdkConfig(
                resolvedMode,
                choose(endpoint, "ANDALUS_ENDPOINT", ""),
                choose(apiKey, "ANDALUS_API_KEY", ""),
                choose(defaultTenantId, "ANDALUS_TENANT", "default"),
                resolveDefaultModel(),
                choose(runStorePath, "ANDALUS_RUN_STORE", ""));
        AgentRunStoreRetentionPolicy retentionPolicy = retentionPolicy();
        if (retentionPolicy != null) {
            config = config.withStorage(config.storage().withRetentionPolicy(retentionPolicy));
        }
        AgentRunStoreBackupRetentionPolicy backupRetentionPolicy = backupRetentionPolicy();
        if (backupRetentionPolicy != null) {
            config = config.withStorage(config.storage().withBackupRetentionPolicy(backupRetentionPolicy));
        }
        return config.withReadinessProfileRegistry(readinessProfileRegistryConfig());
    }

    private AgentRunStoreRetentionPolicy retentionPolicy() {
        String mode = choose(runStoreRetentionMode, "ANDALUS_RUN_STORE_RETENTION", "");
        Integer maxRuns = chooseInteger(runStoreMaxRuns, "ANDALUS_RUN_STORE_MAX_RUNS");
        Integer maxEventsPerRun = chooseInteger(
                runStoreMaxEventsPerRun,
                "ANDALUS_RUN_STORE_MAX_EVENTS_PER_RUN");
        if (mode.isBlank() && maxRuns == null && maxEventsPerRun == null) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        put(values, "mode", mode);
        put(values, "maxRuns", maxRuns);
        put(values, "maxEventsPerRun", maxEventsPerRun);
        return AgentRunStoreRetentionPolicy.fromMap(values);
    }

    private AgentRunStoreBackupRetentionPolicy backupRetentionPolicy() {
        String mode = choose(runStoreBackupRetentionMode, "ANDALUS_RUN_STORE_BACKUP_RETENTION", "");
        Integer maxBackups = chooseInteger(runStoreMaxBackups, "ANDALUS_RUN_STORE_MAX_BACKUPS");
        if (mode.isBlank() && maxBackups == null) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        put(values, "mode", mode);
        put(values, "maxBackups", maxBackups);
        return AgentRunStoreBackupRetentionPolicy.fromMap(values);
    }

    private AndalusPlatformReadinessProfileRegistryConfig readinessProfileRegistryConfig() {
        Map<String, Object> values = new LinkedHashMap<>();
        put(values, "mode", choose(readinessProfileRegistry, "ANDALUS_READINESS_PROFILE_REGISTRY", ""));
        put(values, "path", choose(readinessProfileFile, "ANDALUS_READINESS_PROFILE_FILE", ""));
        put(values, "databaseUrl", choose(readinessProfileDatabaseUrl, "ANDALUS_READINESS_PROFILE_DATABASE_URL", ""));
        put(values, "provider", choose(
                readinessProfileObjectProvider,
                "ANDALUS_READINESS_PROFILE_OBJECT_PROVIDER",
                ""));
        put(values, "endpoint", choose(
                readinessProfileObjectEndpoint,
                "ANDALUS_READINESS_PROFILE_OBJECT_ENDPOINT",
                ""));
        put(values, "bucket", choose(
                readinessProfileObjectBucket,
                "ANDALUS_READINESS_PROFILE_OBJECT_BUCKET",
                ""));
        put(values, "region", choose(
                readinessProfileObjectRegion,
                "ANDALUS_READINESS_PROFILE_OBJECT_REGION",
                ""));
        put(values, "objectKey", choose(
                readinessProfileObjectPrefix,
                "ANDALUS_READINESS_PROFILE_OBJECT_KEY",
                choose(null, "ANDALUS_READINESS_PROFILE_OBJECT_PREFIX", "")));
        put(values, "keyPrefix", choose(
                readinessProfileObjectPrefix,
                "ANDALUS_READINESS_PROFILE_OBJECT_KEY",
                ""));
        put(values, "credentialsRef", choose(
                readinessProfileObjectCredentials,
                "ANDALUS_READINESS_PROFILE_OBJECT_CREDENTIALS",
                ""));
        put(values, "validationPolicy", choose(
                readinessProfileValidationPolicy,
                "ANDALUS_READINESS_PROFILE_VALIDATION_POLICY",
                ""));
        put(values, "pathStyleAccess", chooseBoolean(
                readinessProfileObjectPathStyle,
                "ANDALUS_READINESS_PROFILE_OBJECT_PATH_STYLE"));
        put(values, "fallbackToBuiltIn", chooseBoolean(
                readinessProfileFallback,
                "ANDALUS_READINESS_PROFILE_FALLBACK"));
        return AndalusPlatformReadinessProfileRegistryConfig.fromMap(values);
    }

    private AndalusAqliSdkProvider.Mode modeFromEnv() {
        String value = choose(null, "ANDALUS_SDK_MODE", "LOCAL");
        try {
            return AndalusAqliSdkProvider.Mode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AndalusAqliSdkProvider.Mode.LOCAL;
        }
    }

    private String choose(String value, String envName, String defaultValue) {
        String direct = CliText.trimToEmpty(value);
        if (!direct.isEmpty()) {
            return direct;
        }
        return CliText.trimToDefault(System.getenv(envName), defaultValue);
    }

    private static Boolean chooseBoolean(Boolean value, String envName) {
        if (value != null) {
            return value;
        }
        String env = CliText.trimToEmpty(System.getenv(envName));
        return env.isEmpty() ? null : Boolean.parseBoolean(env);
    }

    private static Integer chooseInteger(Integer value, String envName) {
        if (value != null) {
            return value;
        }
        String env = CliText.trimToEmpty(System.getenv(envName));
        if (env.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(env);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Resolve default model id from CLI flag, env, or ~/.andalus/config.json
     */
    private String resolveDefaultModel() {
        // Precedence: explicit CLI flag > environment > config.json.
        // Root SDK options must remain deterministic for CLI/API tests and
        // automation, while config.json supplies only a default.
        String direct = CliText.trimToEmpty(defaultModelId);
        if (!direct.isEmpty()) {
            return direct;
        }
        String env = CliText.trimToEmpty(System.getenv("ANDALUS_MODEL"));
        if (!env.isEmpty()) {
            return env;
        }
        try {
            Path cfg = Paths.get(System.getProperty("user.home"), ".andalus", "config.json");
            if (Files.exists(cfg) && !Boolean.TRUE.equals(ignoreConfig)) {
                String content = Files.readString(cfg);
                Pattern p = Pattern.compile("\"(?:model|defaultModel|default_model)\"\\s*:\\s*\"([^\"]+)\"");
                Matcher m = p.matcher(content);
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static void put(Map<String, Object> values, String key, String value) {
        if (!CliText.trimToEmpty(value).isEmpty()) {
            values.put(key, value);
        }
    }

    private static void put(Map<String, Object> values, String key, Boolean value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private static void put(Map<String, Object> values, String key, Integer value) {
        if (value != null) {
            values.put(key, value);
        }
    }
}
