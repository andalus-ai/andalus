package tech.kayys.andalus.agent.skills.management;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tech.kayys.andalus.skill.spi.SkillDefinition;
import tech.kayys.andalus.storage.spi.ObjectStorageService;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class SkillDefinitionPersistenceStrategyContractTest {

    @Test
    void fileSystemStorePersistsDefinitionsAcrossInstances(@TempDir Path tempDir) {
        Path directory = tempDir.resolve("skills");
        SkillDefinition skill = skill("planner", "Planner", "REASONING");

        new FileSystemSkillDefinitionStore(directory).registerSkill(skill);

        Optional<SkillDefinition> reloaded = new FileSystemSkillDefinitionStore(directory).getSkill("planner");
        assertThat(reloaded).isPresent();
        assertThat(reloaded.orElseThrow().subSkillPrompts()).containsEntry("REVIEW", "Review carefully.");
        assertThat(reloaded.orElseThrow().tools()).containsExactly("search", "rag");
        assertThat(reloaded.orElseThrow().metadata())
                .containsEntry("version", "1")
                .containsEntry("enabled", "true")
                .doesNotContainKey("nested");
        assertThat(reloaded.orElseThrow().orchestration().defaultChildSkills())
                .containsExactly("researcher", "writer");
    }

    @Test
    void objectStorageStorePersistsDefinitionsWithConfiguredPrefix() {
        InMemoryObjectStore objectStore = new InMemoryObjectStore();
        ObjectStorageSkillDefinitionStore store =
                new ObjectStorageSkillDefinitionStore(objectStore, "tenant-a/skills");

        store.registerSkill(skill("cloud-planner", "Cloud Planner", "REASONING"));

        assertThat(objectStore.objects).containsOnlyKeys("tenant-a/skills/cloud-planner.properties");
        assertThat(store.getSkill("cloud-planner")).isPresent();
        assertThat(store.listSkills()).extracting(SkillDefinition::id)
                .containsExactly("cloud-planner");
        assertThat(store.unregisterSkill("cloud-planner")).isTrue();
        assertThat(store.getSkill("cloud-planner")).isEmpty();
    }

    @Test
    void platformStorageBridgeCanBackObjectStorageSkillStore() {
        InMemoryPlatformObjectStorageService storageService = new InMemoryPlatformObjectStorageService();
        ObjectStorageSkillDefinitionStore store = new ObjectStorageSkillDefinitionStore(
                new StorageServiceSkillManagementObjectStore(storageService),
                "tenant-b/skills");

        store.registerSkill(skill("bridge-planner", "Bridge Planner", "REASONING"));

        assertThat(storageService.objects).containsOnlyKeys("tenant-b/skills/bridge-planner.properties");
        assertThat(store.getSkill("bridge-planner")).isPresent();
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyDefinitionObjectStoreAliasStillWorksWithNeutralFactories() {
        SkillDefinitionObjectStore legacyObjectStore = new InMemoryLegacyObjectStore();
        SkillDefinitionStore store = new SkillDefinitionStoreFactory(null, legacyObjectStore)
                .create(SkillDefinitionStoreConfig.objectStorage("legacy/skills"));

        store.registerSkill(skill("legacy-planner", "Legacy Planner", "REASONING"));

        assertThat(store.getSkill("legacy-planner")).isPresent();
    }

    @Test
    void jdbcStorePersistsDefinitionsThroughDataSource() {
        InMemoryJdbcDataSource dataSource = new InMemoryJdbcDataSource();
        JdbcSkillDefinitionStore store = new JdbcSkillDefinitionStore(dataSource, "skill_defs", true);

        store.registerSkill(skill("jdbc-planner", "JDBC Planner", "REASONING"));
        store.registerSkill(skill("jdbc-planner", "Updated JDBC Planner", "REASONING"));

        assertThat(store.getSkill("jdbc-planner").orElseThrow().name()).isEqualTo("Updated JDBC Planner");
        assertThat(store.listSkills()).extracting(SkillDefinition::id)
                .containsExactly("jdbc-planner");
        assertThat(store.unregisterSkill("jdbc-planner")).isTrue();
        assertThat(store.getSkill("jdbc-planner")).isEmpty();
    }

    @Test
    void hybridStoreReadsFallbackAndWritesPrimary(@TempDir Path tempDir) {
        FileSystemSkillDefinitionStore primary = new FileSystemSkillDefinitionStore(tempDir.resolve("primary"));
        FileSystemSkillDefinitionStore fallback = new FileSystemSkillDefinitionStore(tempDir.resolve("fallback"));
        fallback.registerSkill(skill("backup", "Backup", "UTILITY"));
        fallback.registerSkill(skill("override", "Fallback Override", "UTILITY"));
        primary.registerSkill(skill("override", "Primary Override", "UTILITY"));
        HybridSkillDefinitionStore hybrid = new HybridSkillDefinitionStore(primary, fallback);

        hybrid.registerSkill(skill("new-primary", "New Primary", "UTILITY"));

        assertThat(hybrid.getSkill("backup")).isPresent();
        assertThat(hybrid.getSkill("override").orElseThrow().name()).isEqualTo("Primary Override");
        assertThat(primary.getSkill("new-primary")).isPresent();
        assertThat(hybrid.listSkills()).extracting(SkillDefinition::id)
                .containsExactly("backup", "override", "new-primary");
    }

    @Test
    void factoryBuildsConfiguredCloudPrimaryWithFileFallback(@TempDir Path tempDir) {
        InMemoryObjectStore objectStore = new InMemoryObjectStore();
        FileSystemSkillDefinitionStore fallback = new FileSystemSkillDefinitionStore(tempDir.resolve("fallback"));
        fallback.registerSkill(skill("file-fallback", "File Fallback", "UTILITY"));
        SkillDefinitionStoreConfig config = SkillDefinitionStoreConfig.hybrid(
                SkillDefinitionStoreConfig.objectStorage("runtime/skills"),
                SkillDefinitionStoreConfig.fileSystem(tempDir.resolve("fallback")));
        SkillManagementService service = new SkillManagementService(
                new SkillDefinitionStoreFactory(null, objectStore).create(config));

        service.createSkill(skill("cloud-primary", "Cloud Primary", "UTILITY")).await().indefinitely();

        assertThat(service.getSkill("file-fallback").await().indefinitely()).isPresent();
        assertThat(objectStore.objects).containsKey("runtime/skills/cloud-primary.properties");
    }

    @Test
    void factoryBuildsJdbcPrimaryWithFileFallback(@TempDir Path tempDir) {
        InMemoryJdbcDataSource dataSource = new InMemoryJdbcDataSource();
        FileSystemSkillDefinitionStore fallback = new FileSystemSkillDefinitionStore(tempDir.resolve("fallback"));
        fallback.registerSkill(skill("file-fallback", "File Fallback", "UTILITY"));
        SkillDefinitionStoreConfig config = SkillDefinitionStoreConfig.hybrid(
                SkillDefinitionStoreConfig.jdbc("skill_defs", true),
                SkillDefinitionStoreConfig.fileSystem(tempDir.resolve("fallback")));
        SkillDefinitionStore store = new SkillDefinitionStoreFactory(null, null, dataSource, Map.of()).create(config);

        store.registerSkill(skill("db-primary", "Database Primary", "UTILITY"));

        assertThat(store.getSkill("db-primary")).isPresent();
        assertThat(store.getSkill("file-fallback")).isPresent();
    }

    @Test
    void factoryCanComposeCustomDatabasePrimaryWithFileFallback(@TempDir Path tempDir) {
        TestSkillDefinitionStore database = new TestSkillDefinitionStore();
        FileSystemSkillDefinitionStore fallback = new FileSystemSkillDefinitionStore(tempDir.resolve("fallback"));
        fallback.registerSkill(skill("file-fallback", "File Fallback", "UTILITY"));
        SkillDefinitionStoreConfig config = SkillDefinitionStoreConfig.hybridWithFileFallback(
                SkillDefinitionStoreConfig.custom("database"),
                tempDir.resolve("fallback"));

        SkillDefinitionStore store = new SkillDefinitionStoreFactory(
                null,
                null,
                Map.of("database", database))
                .create(config);
        store.registerSkill(skill("db-primary", "Database Primary", "UTILITY"));

        assertThat(database.getSkill("db-primary")).isPresent();
        assertThat(store.getSkill("file-fallback")).isPresent();
    }

    private SkillDefinition skill(String id, String name, String category) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("version", "1");
        metadata.put("enabled", true);
        metadata.put("nested", Map.of("ignored", "not stored in properties fallback"));
        return SkillDefinition.builder()
                .id(id)
                .name(name)
                .description("Plans tasks")
                .category(category)
                .systemPrompt("Plan carefully.")
                .subSkillPrompts(Map.of("REVIEW", "Review carefully."))
                .temperature(0.4)
                .maxTokens(512)
                .defaultProvider("aqli")
                .fallbackProvider("openai")
                .tools(List.of("search", "rag"))
                .orchestration(new SkillDefinition.OrchestrationConfig(
                        "SEQUENTIAL",
                        "CENTRALIZED",
                        List.of("researcher", "writer"),
                        5,
                        2))
                .metadata(metadata)
                .build();
    }

    private static class InMemoryObjectStore implements SkillManagementObjectStore {
        private final Map<String, byte[]> objects = new LinkedHashMap<>();

        @Override
        public Optional<byte[]> get(String key) {
            return Optional.ofNullable(objects.get(key));
        }

        @Override
        public List<String> list(String prefix) {
            String normalizedPrefix = prefix == null ? "" : prefix;
            return objects.keySet().stream()
                    .filter(key -> key.startsWith(normalizedPrefix))
                    .toList();
        }

        @Override
        public void put(String key, byte[] content) {
            objects.put(key, content);
        }

        @Override
        public boolean delete(String key) {
            return objects.remove(key) != null;
        }
    }

    @SuppressWarnings("deprecation")
    private static final class InMemoryLegacyObjectStore extends InMemoryObjectStore
            implements SkillDefinitionObjectStore {
    }

    private static final class InMemoryPlatformObjectStorageService implements ObjectStorageService {
        private final Map<String, byte[]> objects = new LinkedHashMap<>();

        @Override
        public Uni<Optional<byte[]>> getObject(String key) {
            return Uni.createFrom().item(Optional.ofNullable(objects.get(key)));
        }

        @Override
        public Uni<List<String>> listObjects(String prefix) {
            String normalizedPrefix = prefix == null ? "" : prefix;
            return Uni.createFrom().item(objects.keySet().stream()
                    .filter(key -> key.startsWith(normalizedPrefix))
                    .toList());
        }

        @Override
        public Uni<Void> putObject(String key, byte[] data) {
            return Uni.createFrom().item(() -> {
                objects.put(key, data);
                return (Void) null;
            });
        }

        @Override
        public Uni<Boolean> deleteObject(String key) {
            return Uni.createFrom().item(() -> objects.remove(key) != null);
        }
    }

    private static final class InMemoryJdbcDataSource implements DataSource {
        private final Map<String, String> rows = new LinkedHashMap<>();

        @Override
        public Connection getConnection() {
            return proxy(Connection.class, this::connection);
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("unwrap is not supported");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        private Object connection(Object proxy, Method method, Object[] args) throws SQLException {
            return switch (method.getName()) {
                case "prepareStatement" -> preparedStatement((String) args[0]);
                case "close" -> null;
                case "isClosed" -> false;
                case "unwrap" -> throw new SQLException("unwrap is not supported");
                case "isWrapperFor" -> false;
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement preparedStatement(String sql) {
            Map<Integer, Object> parameters = new LinkedHashMap<>();
            return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
                case "setString", "setTimestamp" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "execute" -> true;
                case "executeUpdate" -> executeUpdate(sql, parameters);
                case "executeQuery" -> resultSet(select(sql, parameters));
                case "close" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            });
        }

        private int executeUpdate(String sql, Map<Integer, Object> parameters) {
            String normalizedSql = sql.toUpperCase(Locale.ROOT);
            if (normalizedSql.startsWith("UPDATE")) {
                String skillId = (String) parameters.get(3);
                if (!rows.containsKey(skillId)) {
                    return 0;
                }
                rows.put(skillId, (String) parameters.get(1));
                return 1;
            }
            if (normalizedSql.startsWith("INSERT")) {
                rows.put((String) parameters.get(1), (String) parameters.get(2));
                return 1;
            }
            if (normalizedSql.startsWith("DELETE")) {
                return rows.remove((String) parameters.get(1)) == null ? 0 : 1;
            }
            return 0;
        }

        private List<String> select(String sql, Map<Integer, Object> parameters) {
            String normalizedSql = sql.toUpperCase(Locale.ROOT);
            if (normalizedSql.contains("WHERE SKILL_ID = ?")) {
                String content = rows.get((String) parameters.get(1));
                return content == null ? List.of() : List.of(content);
            }
            return rows.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .toList();
        }

        private ResultSet resultSet(List<String> values) {
            return proxy(ResultSet.class, new InvocationHandler() {
                private int index = -1;

                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    return switch (method.getName()) {
                        case "next" -> ++index < values.size();
                        case "getString" -> values.get(index);
                        case "close" -> null;
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    };
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Float.TYPE) {
            return 0.0F;
        }
        if (returnType == Double.TYPE) {
            return 0.0D;
        }
        return null;
    }

}
