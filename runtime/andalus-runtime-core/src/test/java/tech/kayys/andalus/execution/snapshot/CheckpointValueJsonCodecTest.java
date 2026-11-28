package tech.kayys.andalus.execution.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.*;

class CheckpointValueJsonCodecTest {
    private final CheckpointValueJsonCodec codec = new CheckpointValueJsonCodec(new ObjectMapper());

    @Test
    void roundTripsEveryCheckpointValueVariantAndNestedNulls() {
        Map<String, CheckpointValue> values = new LinkedHashMap<>();
        values.put("null", NullValue.INSTANCE);
        values.put("string", new StringValue("hello"));
        values.put("boolean", new BooleanValue(true));
        values.put("integer", new IntegerValue(new BigInteger("123456789012345678901234567890")));
        values.put("decimal", new DecimalValue(new BigDecimal("1234567890.000000000000000001")));
        values.put("instant", new InstantValue(Instant.parse("2026-11-28T10:00:00.123456Z")));
        values.put("uuid", new UuidValue(UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479")));
        values.put("resource", new ResourceIdValue("agent:123"));
        values.put("bytes", new BytesValue(new byte[] {0, 1, 2, -1}));
        values.put("list", new ListValue(List.of(new StringValue("nested"), NullValue.INSTANCE)));
        values.put("map", new MapValue(Map.of("inner", new BooleanValue(false))));

        for (CheckpointValue value : values.values()) {
            assertEquals(value, codec.decode(codec.encode(value)));
        }
    }

    @Test
    void usesExplicitTypeTagsAndPreservesIntegerAndDecimalPrecision() {
        String integer = codec.encode(new IntegerValue(new BigInteger("999999999999999999999999")));
        String decimal = codec.encode(new DecimalValue(new BigDecimal("0.000000000000000000123")));

        assertEquals("integer", new ObjectMapper().readTree(integer).get("type").asText());
        assertEquals(new BigInteger("999999999999999999999999"),
                ((IntegerValue) codec.decode(integer)).value());
        assertEquals(new BigDecimal("0.000000000000000000123"),
                ((DecimalValue) codec.decode(decimal)).value());
    }

    @Test
    void rejectsUnknownTagsMalformedValuesAndInvalidBase64() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"java-object\"}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"integer\",\"value\":\"x\"}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"bytes\",\"value\":\"%%%\"}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"null\",\"value\":null}"));
    }
}
