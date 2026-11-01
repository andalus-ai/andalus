package tech.kayys.andalus.agent.backend.gamelan;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CoordinationTimeoutTest {

    @Test
    void returnsFallbackWhenUpstreamDoesNotEmitBeforeDeadline() {
        AtomicBoolean fallbackInvoked = new AtomicBoolean();

        String result = CoordinationTimeout.withFallback(
                Uni.createFrom().<String>nothing(),
                Duration.ofMillis(10),
                () -> {
                    fallbackInvoked.set(true);
                    return "timed-out";
                }
        ).await().indefinitely();

        assertThat(result).isEqualTo("timed-out");
        assertThat(fallbackInvoked).isTrue();
    }

    @Test
    void preservesAnUpstreamItemThatArrivesBeforeDeadline() {
        AtomicBoolean fallbackInvoked = new AtomicBoolean();

        String result = CoordinationTimeout.withFallback(
                Uni.createFrom().item("completed"),
                Duration.ofSeconds(1),
                () -> {
                    fallbackInvoked.set(true);
                    return "timed-out";
                }
        ).await().indefinitely();

        assertThat(result).isEqualTo("completed");
        assertThat(fallbackInvoked).isFalse();
    }
}
