package tech.kayys.andalus.execution.snapshot;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tech.kayys.andalus.artifact.ArtifactFormat;
import tech.kayys.andalus.extension.Id;
import tech.kayys.andalus.extension.Metadata;
import tech.kayys.andalus.extension.Version;
import tech.kayys.andalus.identity.ResourceId.ArtifactId;
import tech.kayys.andalus.resource.Artifact;
import tech.kayys.andalus.resource.ArtifactMetadata;
import tech.kayys.andalus.resource.BinaryArtifact;
import tech.kayys.andalus.resource.JsonArtifact;
import tech.kayys.andalus.resource.ResourceType;
import tech.kayys.andalus.resource.TextArtifact;

/**
 * Explicit checkpoint codec for the built-in artifact implementations.
 * Unknown Artifact implementations are rejected rather than reflectively serialized.
 */
public final class ArtifactCheckpointCodec {
    private final CheckpointValueCodec values;

    public ArtifactCheckpointCodec(CheckpointValueCodec values) {
        this.values = Objects.requireNonNull(values, "values");
    }

    public ArtifactValue encode(Artifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        String kind;
        CheckpointValueCodec.CheckpointValue content;
        CheckpointValueCodec.CheckpointValue parsed = CheckpointValueCodec.NullValue.INSTANCE;
        boolean hasParsed = false;
        if (artifact instanceof TextArtifact text) {
            kind = "text";
            content = values.encode(text.data());
        } else if (artifact instanceof JsonArtifact json) {
            kind = "json";
            content = values.encode(json.json());
            if (json.parsed() != null) {
                parsed = values.encode(json.parsed());
                hasParsed = true;
            }
        } else if (artifact instanceof BinaryArtifact binary) {
            kind = "binary";
            content = values.encode(binary.asBytes());
        } else {
            throw new IllegalArgumentException("Unsupported artifact implementation: " + artifact.getClass().getName());
        }

        return new ArtifactValue(kind, artifact.id().asString(), artifact.type().name(),
                artifact.format().name(), content, parsed, hasParsed,
                encodeMetadata(artifact.metadata()), encodeArtifactMetadata(artifact.artifactMetadata()));
    }

    public Artifact decode(ArtifactValue value) {
        Objects.requireNonNull(value, "value");
        ArtifactId id = new ArtifactId(Id.fromString(value.id()));
        ResourceType type = ResourceType.fromString(value.type());
        ArtifactFormat format = ArtifactFormat.valueOf(value.format());
        Metadata metadata = decodeMetadata(value.metadata());
        ArtifactMetadata artifactMetadata = decodeArtifactMetadata(value.artifactMetadata());
        Object content = values.decode(value.content());

        return switch (value.kind()) {
            case "text" -> new TextArtifact(id, type, format, (String) content, metadata, artifactMetadata);
            case "json" -> new JsonArtifact(id, type, format, (String) content,
                    value.hasParsedContent() ? values.decode(value.parsedContent()) : null,
                    metadata, artifactMetadata);
            case "binary" -> new BinaryArtifact(id, type, format, (byte[]) content, metadata, artifactMetadata);
            default -> throw new IllegalArgumentException("Unsupported artifact checkpoint kind: " + value.kind());
        };
    }

    private MetadataValue encodeMetadata(Metadata metadata) {
        if (metadata == null) return null;
        return new MetadataValue(metadata.name(), metadata.description(),
                metadata.version().major(), metadata.version().minor(), metadata.version().patch(),
                metadata.version().prerelease(), metadata.version().build(),
                metadata.createdAt() == null ? null : metadata.createdAt().toString(),
                metadata.updatedAt() == null ? null : metadata.updatedAt().toString(),
                metadata.createdBy(), metadata.updatedBy(), metadata.labels(),
                encodeObjectMap(metadata.attributes()));
    }

    private Metadata decodeMetadata(MetadataValue value) {
        if (value == null) return null;
        return new Metadata(value.name(), value.description(),
                new Version(value.major(), value.minor(), value.patch(), value.prerelease(), value.build()),
                value.createdAt() == null ? null : java.time.Instant.parse(value.createdAt()),
                value.updatedAt() == null ? null : java.time.Instant.parse(value.updatedAt()),
                value.createdBy(), value.updatedBy(), value.labels(), decodeObjectMap(value.attributes()));
    }

    private ArtifactMetadataValue encodeArtifactMetadata(ArtifactMetadata metadata) {
        if (metadata == null) return null;
        return new ArtifactMetadataValue(metadata.mimeType(), metadata.sizeBytes(), metadata.uri(),
                metadata.duration() == null ? null : metadata.duration().toString(),
                metadata.width(), metadata.height(), metadata.pages(), metadata.language(),
                encodeObjectMap(metadata.attributes()));
    }

    private ArtifactMetadata decodeArtifactMetadata(ArtifactMetadataValue value) {
        if (value == null) return null;
        return new ArtifactMetadata(value.mimeType(), value.sizeBytes(), value.uri(),
                value.duration() == null ? null : Duration.parse(value.duration()),
                value.width(), value.height(), value.pages(), value.language(),
                decodeObjectMap(value.attributes()));
    }

    private Map<String, CheckpointValueCodec.CheckpointValue> encodeObjectMap(Map<String, Object> source) {
        Map<String, CheckpointValueCodec.CheckpointValue> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, values.encode(value)));
        return Map.copyOf(result);
    }

    private Map<String, Object> decodeObjectMap(Map<String, CheckpointValueCodec.CheckpointValue> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, values.decode(value)));
        return Map.copyOf(result);
    }

    public record ArtifactValue(String kind, String id, String type, String format,
            CheckpointValueCodec.CheckpointValue content,
            CheckpointValueCodec.CheckpointValue parsedContent, boolean hasParsedContent,
            MetadataValue metadata, ArtifactMetadataValue artifactMetadata) {
        public ArtifactValue {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(content, "content");
        }
    }

    public record MetadataValue(String name, String description, int major, int minor, int patch,
            String prerelease, String build, String createdAt, String updatedAt,
            String createdBy, String updatedBy, Map<String, String> labels,
            Map<String, CheckpointValueCodec.CheckpointValue> attributes) {
        public MetadataValue {
            labels = Map.copyOf(labels);
            attributes = Map.copyOf(attributes);
        }
    }

    public record ArtifactMetadataValue(String mimeType, long sizeBytes, String uri, String duration,
            Integer width, Integer height, Integer pages, String language,
            Map<String, CheckpointValueCodec.CheckpointValue> attributes) {
        public ArtifactMetadataValue {
            attributes = Map.copyOf(attributes);
        }
    }
}
