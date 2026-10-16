package tech.kayys.aqli.agent.skills.repo.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.andalus.skill.spi.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File-based skill repository with L1/L2 caching.
 *
 * <p>Stores skills in ~/.aqli/skills/ directory structure:
 * <pre>
 * ~/.aqli/skills/
 * ├── {skill-id}/
 * │   ├── manifest.json    # Skill manifest
 * │   ├── content.json     # Skill implementation
 * │   └── metadata.json    # Skill metadata
 * └── ...
 * </pre>
 *
 * <p>Caching layers:
 * <ul>
 *   <li>L1: In-memory Caffeine cache (fastest)</li>
 *   <li>L2: File system cache (persistent)</li>
 * </ul>
 */
@ApplicationScoped
public class FileSkillRepository implements SkillRepository {

    private static final Logger log = LoggerFactory.getLogger(FileSkillRepository.class);

    private static final String DEFAULT_BASE_DIR = System.getProperty("user.home") + "/.aqli/skills";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int MAX_CACHE_SIZE = 500;

    @Inject
    ObjectMapper objectMapper;

    private final Path baseDir;
    private final Cache<String, SkillContent> contentCache;
    private final Cache<String, SkillMetadata> metadataCache;
    private final CacheStats stats;

    /**
     * Default constructor with default base directory.
     */
    public FileSkillRepository() {
        this(Paths.get(DEFAULT_BASE_DIR));
    }

    /**
     * Constructor with custom base directory.
     *
     * @param baseDir base directory for skill storage
     */
    public FileSkillRepository(Path baseDir) {
        this.baseDir = baseDir;
        this.contentCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHE_SIZE)
                .expireAfterWrite(CACHE_TTL.toMillis(), TimeUnit.MILLISECONDS)
                .recordStats()
                .build();
        this.metadataCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHE_SIZE)
                .expireAfterWrite(CACHE_TTL.toMillis(), TimeUnit.MILLISECONDS)
                .recordStats()
                .build();
        this.stats = new CacheStats();
        log.info("FileSkillRepository initialized with base dir: {}", baseDir);
    }

    @Override
    public String name() {
        return "file";
    }

    @Override
    public Uni<SkillMetadata> save(SkillContent content) {
        return Uni.createFrom().item(() -> {
            try {
                // Ensure directory exists
                Path skillDir = getSkillDir(content.metadata().id());
                Files.createDirectories(skillDir);

                // Calculate checksum
                String checksum = calculateChecksum(content.content());

                // Create metadata with updated paths and checksum
                SkillMetadata metadata = new SkillMetadata.Builder()
                        .from(content.metadata())
                        .contentPath(skillDir.resolve("content.json").toString())
                        .manifestPath(skillDir.resolve("manifest.json").toString())
                        .checksum(checksum)
                        .updatedAt(Instant.now())
                        .build();

                // Write content
                Path contentPath = skillDir.resolve("content.json");
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(contentPath.toFile(), content);

                // Write manifest
                Path manifestPath = skillDir.resolve("manifest.json");
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(manifestPath.toFile(), content.manifest());

                // Write metadata
                Path metadataPath = skillDir.resolve("metadata.json");
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(metadataPath.toFile(), metadata);

                // Update caches
                contentCache.put(metadata.id(), content);
                metadataCache.put(metadata.id(), metadata);

                log.info("Saved skill: {} to {}", metadata.id(), skillDir);
                return metadata;

            } catch (IOException e) {
                log.error("Failed to save skill: {}", content.metadata().id(), e);
                throw new RuntimeException("Failed to save skill", e);
            }
        });
    }

    @Override
    public Uni<Optional<SkillContent>> get(String skillId) {
        return Uni.createFrom().item(() -> {
            // Check L1 cache first
            SkillContent cached = contentCache.getIfPresent(skillId);
            if (cached != null) {
                stats.recordHit();
                log.debug("Cache hit for skill: {}", skillId);
                return Optional.of(cached);
            }

            stats.recordMiss();
            
            // Check file system
            Path skillDir = getSkillDir(skillId);
            Path contentPath = skillDir.resolve("content.json");

            if (!Files.exists(contentPath)) {
                log.debug("Skill not found: {}", skillId);
                return Optional.empty();
            }

            try {
                // Read and parse content
                SkillContent content = objectMapper.readValue(
                        contentPath.toFile(),
                        SkillContent.class
                );

                // Update L1 cache
                contentCache.put(skillId, content);

                log.debug("Loaded skill from file: {}", skillId);
                return Optional.of(content);

            } catch (IOException e) {
                log.error("Failed to read skill: {}", skillId, e);
                return Optional.empty();
            }
        });
    }

    @Override
    public Uni<Optional<SkillMetadata>> getMetadata(String skillId) {
        return Uni.createFrom().item(() -> {
            // Check L1 cache first
            SkillMetadata cached = metadataCache.getIfPresent(skillId);
            if (cached != null) {
                stats.recordHit();
                return Optional.of(cached);
            }

            stats.recordMiss();

            // Check file system
            Path skillDir = getSkillDir(skillId);
            Path metadataPath = skillDir.resolve("metadata.json");

            if (!Files.exists(metadataPath)) {
                return Optional.empty();
            }

            try {
                SkillMetadata metadata = objectMapper.readValue(
                        metadataPath.toFile(),
                        SkillMetadata.class
                );

                // Update L1 cache
                metadataCache.put(skillId, metadata);

                return Optional.of(metadata);

            } catch (IOException e) {
                log.error("Failed to read metadata: {}", skillId, e);
                return Optional.empty();
            }
        });
    }

    @Override
    public Uni<Boolean> delete(String skillId) {
        return Uni.createFrom().item(() -> {
            try {
                Path skillDir = getSkillDir(skillId);

                if (!Files.exists(skillDir)) {
                    return false;
                }

                // Delete directory recursively
                deleteDirectory(skillDir);

                // Remove from caches
                contentCache.invalidate(skillId);
                metadataCache.invalidate(skillId);

                log.info("Deleted skill: {}", skillId);
                return true;

            } catch (IOException e) {
                log.error("Failed to delete skill: {}", skillId, e);
                throw new RuntimeException("Failed to delete skill", e);
            }
        });
    }

    @Override
    public Uni<List<SkillMetadata>> list(int offset, int limit) {
        return Uni.createFrom().item(() -> {
            try {
                if (!Files.exists(baseDir)) {
                    return List.of();
                }

                List<SkillMetadata> skills = new ArrayList<>();

                try (Stream<Path> paths = Files.list(baseDir)) {
                    paths.filter(Files::isDirectory)
                            .skip(offset)
                            .limit(limit)
                            .forEach(dir -> {
                                Path metadataPath = dir.resolve("metadata.json");
                                if (Files.exists(metadataPath)) {
                                    try {
                                        SkillMetadata metadata = objectMapper.readValue(
                                                metadataPath.toFile(),
                                                SkillMetadata.class
                                        );
                                        skills.add(metadata);
                                    } catch (IOException e) {
                                        log.warn("Failed to read metadata for: {}", dir.getFileName());
                                    }
                                }
                            });
                }

                return skills;

            } catch (IOException e) {
                log.error("Failed to list skills", e);
                throw new RuntimeException("Failed to list skills", e);
            }
        });
    }

    @Override
    public Uni<List<SkillMetadata>> search(String query, int offset, int limit) {
        return Uni.createFrom().item(() -> {
            try {
                String lowerQuery = query.toLowerCase();

                List<SkillMetadata> allSkills = list().await().indefinitely();

                return allSkills.stream()
                        .filter(skill -> matchesQuery(skill, lowerQuery))
                        .skip(offset)
                        .limit(limit)
                        .collect(Collectors.toList());

            } catch (Exception e) {
                log.error("Failed to search skills", e);
                throw new RuntimeException("Failed to search skills", e);
            }
        });
    }

    @Override
    public Uni<List<SkillMetadata>> getByCategory(String category) {
        return Uni.createFrom().item(() -> {
            try {
                List<SkillMetadata> allSkills = list().await().indefinitely();

                return allSkills.stream()
                        .filter(skill -> category.equalsIgnoreCase(skill.category()))
                        .collect(Collectors.toList());

            } catch (Exception e) {
                log.error("Failed to get skills by category", e);
                throw new RuntimeException("Failed to get skills by category", e);
            }
        });
    }

    @Override
    public Uni<List<SkillMetadata>> getByTags(List<String> tags, boolean matchAll) {
        return Uni.createFrom().item(() -> {
            try {
                List<SkillMetadata> allSkills = list().await().indefinitely();

                return allSkills.stream()
                        .filter(skill -> matchesTags(skill, tags, matchAll))
                        .collect(Collectors.toList());

            } catch (Exception e) {
                log.error("Failed to get skills by tags", e);
                throw new RuntimeException("Failed to get skills by tags", e);
            }
        });
    }

    @Override
    public Uni<Boolean> exists(String skillId) {
        return Uni.createFrom().item(() -> {
            // Check cache first
            if (metadataCache.getIfPresent(skillId) != null) {
                return true;
            }

            // Check file system
            Path skillDir = getSkillDir(skillId);
            return Files.exists(skillDir);
        });
    }

    @Override
    public Uni<Boolean> setEnabled(String skillId, boolean enabled) {
        return Uni.createFrom().item(() -> {
            try {
                Optional<SkillMetadata> maybeMetadata = getMetadata(skillId).await().indefinitely();
                if (maybeMetadata.isEmpty()) {
                    return false;
                }

                SkillMetadata metadata = maybeMetadata.get();
                SkillMetadata updated = new SkillMetadata.Builder()
                        .from(metadata)
                        .enabled(enabled)
                        .updatedAt(Instant.now())
                        .build();

                // Write updated metadata
                Path metadataPath = getSkillDir(skillId).resolve("metadata.json");
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(metadataPath.toFile(), updated);

                // Update cache
                metadataCache.put(skillId, updated);

                return true;

            } catch (IOException e) {
                log.error("Failed to set enabled status for skill: {}", skillId, e);
                return false;
            }
        });
    }

    @Override
    public Uni<RepositoryStats> getStats() {
        return Uni.createFrom().item(() -> {
            try {
                List<SkillMetadata> allSkills = list().await().indefinitely();
                int total = allSkills.size();
                int enabled = (int) allSkills.stream().filter(SkillMetadata::enabled).count();
                int disabled = total - enabled;

                long totalSize = calculateTotalSize();

                double hitRatio = stats.getHitRatio();

                return new RepositoryStats(
                        total, enabled, disabled, totalSize,
                        (int) stats.getHits(), (int) stats.getMisses(), hitRatio
                );

            } catch (Exception e) {
                log.error("Failed to get stats", e);
                throw new RuntimeException("Failed to get stats", e);
            }
        });
    }

    @Override
    public Uni<Void> clearCache() {
        return Uni.createFrom().voidItem().invoke(() -> {
            contentCache.invalidateAll();
            metadataCache.invalidateAll();
            stats.reset();
            log.info("Cache cleared");
        });
    }

    @Override
    public Uni<Void> initialize() {
        return Uni.createFrom().voidItem().invoke(() -> {
            try {
                if (!Files.exists(baseDir)) {
                    Files.createDirectories(baseDir);
                    log.info("Created base directory: {}", baseDir);
                }
            } catch (IOException e) {
                log.error("Failed to initialize repository", e);
                throw new RuntimeException("Failed to initialize repository", e);
            }
        });
    }

    // ==================== Private Helpers ====================

    private Path getSkillDir(String skillId) {
        return baseDir.resolve(skillId);
    }

    private boolean matchesQuery(SkillMetadata skill, String query) {
        return skill.name().toLowerCase().contains(query) ||
                skill.description().toLowerCase().contains(query) ||
                skill.tags().stream().anyMatch(tag -> tag.toLowerCase().contains(query));
    }

    private boolean matchesTags(SkillMetadata skill, List<String> tags, boolean matchAll) {
        if (matchAll) {
            return skill.tags().containsAll(tags);
        } else {
            return tags.stream().anyMatch(skill.tags()::contains);
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path);
                        }
                    });
        }
    }

    private long calculateTotalSize() throws IOException {
        if (!Files.exists(baseDir)) {
            return 0;
        }

        try (Stream<Path> paths = Files.walk(baseDir)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        }
    }

    private String calculateChecksum(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(content.hashCode());
        }
    }

    /**
     * Simple cache statistics tracker.
     */
    private static class CacheStats {
        private final java.util.concurrent.atomic.AtomicLong hits = new java.util.concurrent.atomic.AtomicLong(0);
        private final java.util.concurrent.atomic.AtomicLong misses = new java.util.concurrent.atomic.AtomicLong(0);

        public void recordHit() {
            hits.incrementAndGet();
        }

        public void recordMiss() {
            misses.incrementAndGet();
        }

        public long getHits() {
            return hits.get();
        }

        public long getMisses() {
            return misses.get();
        }

        public double getHitRatio() {
            long total = hits.get() + misses.get();
            return total > 0 ? (double) hits.get() / total : 0.0;
        }

        public void reset() {
            hits.set(0);
            misses.set(0);
        }
    }
}
