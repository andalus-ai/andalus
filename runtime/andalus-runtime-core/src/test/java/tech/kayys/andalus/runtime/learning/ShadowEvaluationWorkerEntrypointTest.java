package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ShadowEvaluationWorkerEntrypointTest {

    @Test
    void readsOneBoundedInputFrameUntilEof() throws Exception {
        byte[] frame = new byte[]{1, 2, 3, 4};
        assertArrayEquals(frame, ShadowEvaluationWorkerEntrypoint.readBounded(
                new ByteArrayInputStream(frame), frame.length));
    }

    @Test
    void rejectsInputLargerThanConfiguredFrameLimit() {
        byte[] frame = new byte[33];
        Arrays.fill(frame, (byte) 7);
        assertThrows(IOException.class, () -> ShadowEvaluationWorkerEntrypoint.readBounded(
                new ByteArrayInputStream(frame), 32));
    }

    @Test
    void acceptsEmptyInputAtReaderLayerForCallerToRejectExplicitly() throws Exception {
        assertEquals(0, ShadowEvaluationWorkerEntrypoint.readBounded(
                new ByteArrayInputStream(new byte[0]), 32).length);
    }
}
