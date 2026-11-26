package tech.kayys.andalus.execution.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tech.kayys.andalus.context.ContextData;
import tech.kayys.andalus.context.Document;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.CheckpointValue;

/**
 * Explicit, versioned checkpoint representation for Framework ContextData.
 *
 * <p>Document fields are represented directly. Memories, knowledge and map
 * values use the restricted CheckpointValue algebra; unsupported runtime
 * objects fail encoding rather than being stringified or reflectively loaded.</p>
 */
public final class ContextDataCheckpointCodec {
    public static final int SCHEMA_VERSION = 1;

    private final CheckpointValueCodec values;

    public ContextDataCheckpointCodec(CheckpointValueCodec values) {
        this.values = Objects.requireNonNull(values, "values");
    }

    public ContextDataValue encode(ContextData context) {
        if (context == null) return null;
        List<DocumentValue> documents = context.documents() == null ? null
                : context.documents().stream().map(this::encodeDocument).toList();
        return new ContextDataValue(SCHEMA_VERSION, context.id(), context.query(),
                context.originalQuery(), documents, encodeList(context.memories()),
                encodeList(context.knowledge()), encodeMap(context.structuredData()),
                encodeMap(context.metadata()), context.relevanceScore(),
                context.retrievalTimeMs(), context.contextType());
    }

    public ContextData decode(ContextDataValue value) {
        if (value == null) return null;
        requireSupportedVersion(value.schemaVersion());
        List<Document> documents = value.documents() == null ? null
                : value.documents().stream().map(this::decodeDocument).toList();
        return new ContextData(value.id(), value.query(), value.originalQuery(),
                documents, decodeList(value.memories()), decodeList(value.knowledge()),
                decodeMap(value.structuredData()), decodeMap(value.metadata()),
                value.relevanceScore(), value.retrievalTimeMs(), value.contextType());
    }

    private DocumentValue encodeDocument(Document document) {
        Objects.requireNonNull(document, "document");
        return new DocumentValue(document.id(), document.content(), document.title(),
                document.source(), encodeMap(document.metadata()),
                document.embedding() == null ? null : document.embedding().stream()
                        .map(v -> values.encode(v)).toList());
    }

    private Document decodeDocument(DocumentValue value) {
        List<Double> embedding = null;
        if (value.embedding() != null) {
            embedding = new ArrayList<>(value.embedding().size());
            for (CheckpointValue item : value.embedding()) {
                Object decoded = values.decode(item);
                if (!(decoded instanceof Number number)) {
                    throw new IllegalArgumentException("Document embedding values must be numeric");
                }
                embedding.add(number.doubleValue());
            }
        }
        return new Document(value.id(), value.content(), value.title(), value.source(),
                decodeMap(value.metadata()), embedding);
    }

    private List<CheckpointValue> encodeList(List<?> source) {
        if (source == null) return null;
        return source.stream().map(values::encode).toList();
    }

    private List<Object> decodeList(List<CheckpointValue> source) {
        if (source == null) return null;
        List<Object> result = new ArrayList<>(source.size());
        for (CheckpointValue item : source) result.add(values.decode(item));
        return Collections.unmodifiableList(result);
    }

    private Map<String, CheckpointValue> encodeMap(Map<String, ?> source) {
        if (source == null) return null;
        Map<String, CheckpointValue> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, values.encode(value)));
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> decodeMap(Map<String, CheckpointValue> source) {
        if (source == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, values.decode(value)));
        return Collections.unmodifiableMap(result);
    }

    private void requireSupportedVersion(int version) {
        if (version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported ContextData checkpoint schema version: " + version);
        }
    }

    public record ContextDataValue(
            int schemaVersion,
            String id,
            String query,
            String originalQuery,
            List<DocumentValue> documents,
            List<CheckpointValue> memories,
            List<CheckpointValue> knowledge,
            Map<String, CheckpointValue> structuredData,
            Map<String, CheckpointValue> metadata,
            double relevanceScore,
            long retrievalTimeMs,
            String contextType) {
        public ContextDataValue {
            documents = documents == null ? null : List.copyOf(documents);
            memories = memories == null ? null : List.copyOf(memories);
            knowledge = knowledge == null ? null : List.copyOf(knowledge);
            structuredData = immutableNullableMap(structuredData);
            metadata = immutableNullableMap(metadata);
        }
    }

    public record DocumentValue(
            String id,
            String content,
            String title,
            String source,
            Map<String, CheckpointValue> metadata,
            List<CheckpointValue> embedding) {
        public DocumentValue {
            metadata = immutableNullableMap(metadata);
            embedding = embedding == null ? null : List.copyOf(embedding);
        }
    }

    private static <K, V> Map<K, V> immutableNullableMap(Map<K, V> source) {
        return source == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
