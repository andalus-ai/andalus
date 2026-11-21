package tech.kayys.andalus.execution.snapshot;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.execution.resource.model.ResourceId;

class CheckpointValueCodecTest {
    private final CheckpointValueCodec codec = new CheckpointValueCodec();

    @Test
    void roundTripsSupportedNestedValuesAndPreservesOrder() {
        Instant instant = Instant.parse("2026-11-21T07:00:00Z");
        UUID uuid = UUID.fromString("2d13a85d-7e7c-4ad5-a48e-2f3d9a7d1c21");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("null", null); input.put("text", "hello"); input.put("flag", true);
        input.put("integer", new BigInteger("123456789012345678901234567890"));
        input.put("decimal", new BigDecimal("12.3400")); input.put("instant", instant);
        input.put("uuid", uuid); input.put("bytes", new byte[] { 0, 1, -1 });
        input.put("list", List.of("a", BigInteger.valueOf(2), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> restored = (Map<String, Object>) codec.decode(codec.encode(input));
        assertEquals(List.copyOf(input.keySet()), List.copyOf(restored.keySet()));
        assertNull(restored.get("null")); assertEquals(input.get("text"), restored.get("text"));
        assertEquals(input.get("flag"), restored.get("flag")); assertEquals(input.get("integer"), restored.get("integer"));
        assertEquals(input.get("decimal"), restored.get("decimal")); assertEquals(instant, restored.get("instant"));
        assertEquals(uuid, restored.get("uuid"));
        assertArrayEquals((byte[]) input.get("bytes"), (byte[]) restored.get("bytes"));
        assertEquals(input.get("list"), restored.get("list"));
        assertThrows(UnsupportedOperationException.class, () -> restored.put("x", 1));
    }


    @Test
    void resourceIdsRoundTripWithoutLosingTheirLogicalValue() {
        ResourceId original = ResourceId.of("resource://document/contract-42");
        CheckpointValueCodec.ResourceIdValue encoded = codec.encodeResourceId(original);
        assertEquals(original.value(), encoded.value());
        assertEquals(original, codec.decodeResourceId(encoded));
        assertThrows(IllegalArgumentException.class,
                () -> new CheckpointValueCodec.ResourceIdValue("  "));
    }

    @Test
    void rejectsUnknownObjectsInsteadOfStringifyingThem() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> codec.encode(new StringBuilder("not safe")));
        assertTrue(error.getMessage().contains("$"));
        assertTrue(error.getMessage().contains("unsupported value type"));
    }

    @Test
    void rejectsNonStringMapKeysAndCycles() {
        Map<Object, Object> invalid = new LinkedHashMap<>(); invalid.put(1, "bad");
        assertThrows(IllegalArgumentException.class, () -> codec.encode(invalid));
        List<Object> cyclic = new ArrayList<>(); cyclic.add(cyclic);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> codec.encode(cyclic))
                .getMessage().contains("cyclic object graph"));
    }

    @Test
    void rejectsNonFiniteNumbers() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(Float.POSITIVE_INFINITY));
    }

    @Test
    void byteValuesAreDefensivelyCopied() {
        byte[] original = { 1, 2, 3 };
        CheckpointValueCodec.BytesValue encoded = (CheckpointValueCodec.BytesValue) codec.encode(original);
        original[0] = 99; byte[] firstRead = encoded.value(); firstRead[1] = 88;
        assertArrayEquals(new byte[] { 1, 2, 3 }, encoded.value());
    }
}
