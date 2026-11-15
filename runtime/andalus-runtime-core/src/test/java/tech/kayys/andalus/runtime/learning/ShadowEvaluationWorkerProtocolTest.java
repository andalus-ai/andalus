package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShadowEvaluationWorkerProtocolTest {
    @Test
    void roundTripsVersionedFrameAndDefensivelyCopiesPayload() throws Exception {
        byte[] payload = "{\"operation\":\"evaluate\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID requestId = UUID.randomUUID();
        var frame = new ShadowEvaluationWorkerProtocol.Frame(
                requestId, ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_REQUEST, payload);
        payload[0] = 'X';

        byte[] encoded = ShadowEvaluationWorkerProtocol.encode(frame);
        var decoded = ShadowEvaluationWorkerProtocol.decode(encoded);

        assertThat(decoded.requestId()).isEqualTo(requestId);
        assertThat(decoded.type()).isEqualTo(ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_REQUEST);
        assertThat(new String(decoded.payload(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"operation\":\"evaluate\"}");
    }

    @Test
    void rejectsTruncationCorruptionAndTrailingBytes() throws Exception {
        byte[] encoded = ShadowEvaluationWorkerProtocol.encode(new ShadowEvaluationWorkerProtocol.Frame(
                UUID.randomUUID(), ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_RESPONSE,
                new byte[]{1, 2, 3}));

        assertThatThrownBy(() -> ShadowEvaluationWorkerProtocol.decode(
                java.util.Arrays.copyOf(encoded, encoded.length - 1))).isInstanceOf(IOException.class);

        byte[] corrupted = encoded.clone();
        corrupted[corrupted.length - 1] ^= 0x01;
        assertThatThrownBy(() -> ShadowEvaluationWorkerProtocol.decode(corrupted))
                .isInstanceOf(IOException.class).hasMessageContaining("digest");

        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThatThrownBy(() -> ShadowEvaluationWorkerProtocol.decode(trailing))
                .isInstanceOf(IOException.class).hasMessageContaining("trailing");
    }

    @Test
    void rejectsOversizedPayloadAndUnknownProtocolVersion() {
        assertThatThrownBy(() -> new ShadowEvaluationWorkerProtocol.Frame(UUID.randomUUID(),
                ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_REQUEST,
                new byte[ShadowEvaluationWorkerProtocol.MAX_PAYLOAD_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] encoded = ShadowEvaluationWorkerProtocol.encode(new ShadowEvaluationWorkerProtocol.Frame(
                UUID.randomUUID(), ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_REQUEST, new byte[0]));
        ByteBuffer.wrap(encoded).putShort(4, (short) 99);
        assertThatThrownBy(() -> ShadowEvaluationWorkerProtocol.decode(encoded))
                .isInstanceOf(IOException.class).hasMessageContaining("version");
    }
}
