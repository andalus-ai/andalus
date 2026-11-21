package tech.kayys.andalus.execution.snapshot;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tech.kayys.andalus.execution.resource.model.ResourceId;

/** Safe, explicit value algebra for checkpoint snapshots. */
public final class CheckpointValueCodec {
    public CheckpointValue encode(Object value) {
        return encode(value, "$", new IdentityHashMap<>());
    }

    public ResourceIdValue encodeResourceId(ResourceId resourceId) {
        Objects.requireNonNull(resourceId, "resourceId");
        return new ResourceIdValue(resourceId.value());
    }

    public ResourceId decodeResourceId(ResourceIdValue value) {
        Objects.requireNonNull(value, "value");
        return ResourceId.of(value.value());
    }

    public Object decode(CheckpointValue value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof NullValue) return null;
        if (value instanceof StringValue v) return v.value();
        if (value instanceof BooleanValue v) return v.value();
        if (value instanceof IntegerValue v) return v.value();
        if (value instanceof DecimalValue v) return v.value();
        if (value instanceof InstantValue v) return v.value();
        if (value instanceof UuidValue v) return v.value();
        if (value instanceof BytesValue v) return v.value();
        if (value instanceof ListValue v) {
            List<Object> result = new ArrayList<>(v.values().size());
            for (CheckpointValue item : v.values()) result.add(decode(item));
            return Collections.unmodifiableList(result);
        }
        if (value instanceof MapValue v) {
            Map<String, Object> result = new LinkedHashMap<>();
            v.values().forEach((key, item) -> result.put(key, decode(item)));
            return Collections.unmodifiableMap(result);
        }
        throw new IllegalArgumentException("Unsupported checkpoint value variant: " + value.getClass().getName());
    }

    private CheckpointValue encode(Object value, String path, IdentityHashMap<Object, Boolean> visiting) {
        if (value == null) return NullValue.INSTANCE;
        if (value instanceof String v) return new StringValue(v);
        if (value instanceof Boolean v) return new BooleanValue(v);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger) {
            return new IntegerValue(value instanceof BigInteger bi ? bi : new BigInteger(value.toString()));
        }
        if (value instanceof BigDecimal v) return new DecimalValue(v);
        if (value instanceof Float v) {
            if (!Float.isFinite(v)) throw unsupported(path, "non-finite floating-point number");
            return new DecimalValue(BigDecimal.valueOf(v.doubleValue()));
        }
        if (value instanceof Double v) {
            if (!Double.isFinite(v)) throw unsupported(path, "non-finite floating-point number");
            return new DecimalValue(BigDecimal.valueOf(v));
        }
        if (value instanceof Instant v) return new InstantValue(v);
        if (value instanceof UUID v) return new UuidValue(v);
        if (value instanceof byte[] v) return new BytesValue(v);

        if (visiting.put(value, Boolean.TRUE) != null) throw unsupported(path, "cyclic object graph");
        try {
            if (value instanceof List<?> list) {
                List<CheckpointValue> encoded = new ArrayList<>(list.size());
                for (int i = 0; i < list.size(); i++) encoded.add(encode(list.get(i), path + "[" + i + "]", visiting));
                return new ListValue(encoded);
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, CheckpointValue> encoded = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw unsupported(path, "map key is not a string");
                    encoded.put(key, encode(entry.getValue(), path + "." + key, visiting));
                }
                return new MapValue(encoded);
            }
            throw unsupported(path, "unsupported value type " + value.getClass().getName());
        } finally {
            visiting.remove(value);
        }
    }

    private IllegalArgumentException unsupported(String path, String reason) {
        return new IllegalArgumentException("Cannot checkpoint value at " + path + ": " + reason);
    }

    /** Explicit tagged union; extend only with a versioned schema migration. */
    public sealed interface CheckpointValue permits NullValue, StringValue, BooleanValue,
            IntegerValue, DecimalValue, InstantValue, UuidValue, ResourceIdValue, BytesValue, ListValue, MapValue { }
    public enum NullValue implements CheckpointValue { INSTANCE }
    public record StringValue(String value) implements CheckpointValue {
        public StringValue { Objects.requireNonNull(value, "value"); }
    }
    public record BooleanValue(boolean value) implements CheckpointValue { }
    public record IntegerValue(BigInteger value) implements CheckpointValue {
        public IntegerValue { Objects.requireNonNull(value, "value"); }
    }
    public record DecimalValue(BigDecimal value) implements CheckpointValue {
        public DecimalValue { Objects.requireNonNull(value, "value"); }
    }
    public record InstantValue(Instant value) implements CheckpointValue {
        public InstantValue { Objects.requireNonNull(value, "value"); }
    }
    public record UuidValue(UUID value) implements CheckpointValue {
        public UuidValue { Objects.requireNonNull(value, "value"); }
    }
    public record ResourceIdValue(String value) implements CheckpointValue {
        public ResourceIdValue {
            Objects.requireNonNull(value, "value");
            if (value.isBlank()) throw new IllegalArgumentException("value cannot be blank");
        }
    }
    public record BytesValue(String base64) implements CheckpointValue {
        public BytesValue(byte[] value) { this(Base64.getEncoder().encodeToString(value.clone())); }
        public BytesValue { Objects.requireNonNull(base64, "base64"); }
        public byte[] value() { return Base64.getDecoder().decode(base64); }
    }
    public record ListValue(List<CheckpointValue> values) implements CheckpointValue {
        public ListValue { values = List.copyOf(values); }
    }
    public record MapValue(Map<String, CheckpointValue> values) implements CheckpointValue {
        public MapValue { values = Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    }
}
