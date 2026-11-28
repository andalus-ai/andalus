package tech.kayys.andalus.execution.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.BooleanValue;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.BytesValue;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.CheckpointValue;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.DecimalValue;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.InstantValue;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.IntegerValue;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.ListValue;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.MapValue;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.NullValue;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.ResourceIdValue;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.StringValue;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.UuidValue;

/**
 * Stable tagged JSON representation for the checkpoint value algebra.
 *
 * <p>Every variant has an explicit type tag. This deliberately avoids Jackson
 * default typing and Java class names in persisted data.</p>
 */
public final class CheckpointValueJsonCodec {
    private final ObjectMapper mapper;

    public CheckpointValueJsonCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public String encode(CheckpointValue value) {
        try {
            return mapper.writeValueAsString(toJson(Objects.requireNonNull(value, "value")));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to encode checkpoint value", exception);
        }
    }

    public CheckpointValue decode(String json) {
        Objects.requireNonNull(json, "json");
        try {
            return fromJson(mapper.readTree(json));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid checkpoint value JSON", exception);
        }
    }

    private JsonNode toJson(CheckpointValue value) {
        ObjectNode node = mapper.createObjectNode();
        if (value instanceof NullValue) {
            node.put("type", "null");
        } else if (value instanceof StringValue v) {
            node.put("type", "string");
            node.put("value", v.value());
        } else if (value instanceof BooleanValue v) {
            node.put("type", "boolean");
            node.put("value", v.value());
        } else if (value instanceof IntegerValue v) {
            node.put("type", "integer");
            node.put("value", v.value().toString());
        } else if (value instanceof DecimalValue v) {
            node.put("type", "decimal");
            node.put("value", v.value().toString());
        } else if (value instanceof InstantValue v) {
            node.put("type", "instant");
            node.put("value", v.value().toString());
        } else if (value instanceof UuidValue v) {
            node.put("type", "uuid");
            node.put("value", v.value().toString());
        } else if (value instanceof ResourceIdValue v) {
            node.put("type", "resource-id");
            node.put("value", v.value());
        } else if (value instanceof BytesValue v) {
            node.put("type", "bytes");
            node.put("value", v.base64());
        } else if (value instanceof ListValue v) {
            node.put("type", "list");
            ArrayNode items = node.putArray("value");
            v.values().forEach(item -> items.add(toJson(item)));
        } else if (value instanceof MapValue v) {
            node.put("type", "map");
            ObjectNode entries = node.putObject("value");
            v.values().forEach((key, item) -> entries.set(key, toJson(item)));
        } else {
            throw new IllegalArgumentException("Unsupported checkpoint value variant: " + value.getClass().getName());
        }
        return node;
    }

    private CheckpointValue fromJson(JsonNode node) {
        if (node == null || !node.isObject() || !node.hasNonNull("type")) {
            throw new IllegalArgumentException("Checkpoint value must be an object with a type tag");
        }
        String type = node.get("type").asText();
        JsonNode value = node.get("value");
        return switch (type) {
            case "null" -> {
                requireNoValue(value);
                yield NullValue.INSTANCE;
            }
            case "string" -> new StringValue(requireText(value, type));
            case "boolean" -> {
                if (value == null || !value.isBoolean()) throw invalid(type);
                yield new BooleanValue(value.booleanValue());
            }
            case "integer" -> new IntegerValue(new BigInteger(requireText(value, type)));
            case "decimal" -> new DecimalValue(new BigDecimal(requireText(value, type)));
            case "instant" -> new InstantValue(Instant.parse(requireText(value, type)));
            case "uuid" -> new UuidValue(UUID.fromString(requireText(value, type)));
            case "resource-id" -> new ResourceIdValue(requireText(value, type));
            case "bytes" -> {
                String encoded = requireText(value, type);
                Base64.getDecoder().decode(encoded);
                yield new BytesValue(encoded);
            }
            case "list" -> {
                if (value == null || !value.isArray()) throw invalid(type);
                List<CheckpointValue> items = new ArrayList<>(value.size());
                value.forEach(item -> items.add(fromJson(item)));
                yield new ListValue(items);
            }
            case "map" -> {
                if (value == null || !value.isObject()) throw invalid(type);
                Map<String, CheckpointValue> entries = new LinkedHashMap<>();
                value.fields().forEachRemaining(entry -> entries.put(entry.getKey(), fromJson(entry.getValue())));
                yield new MapValue(entries);
            }
            default -> throw new IllegalArgumentException("Unknown checkpoint value type tag: " + type);
        };
    }

    private static String requireText(JsonNode value, String type) {
        if (value == null || !value.isTextual()) throw invalid(type);
        return value.textValue();
    }

    private static void requireNoValue(JsonNode value) {
        if (value != null) throw invalid("null");
    }

    private static IllegalArgumentException invalid(String type) {
        return new IllegalArgumentException("Invalid value for checkpoint type tag: " + type);
    }
}
