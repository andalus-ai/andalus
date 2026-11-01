package tech.kayys.andalus.agent.backend.gamelan;

import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Applies a caller-side timeout to a Mutiny operation.
 *
 * <p>Timeout cancellation is propagated to the upstream subscription. Whether that
 * stops remote work depends on the upstream client's cancellation contract.</p>
 */
final class CoordinationTimeout {

    private CoordinationTimeout() {
    }

    static <T> Uni<T> withFallback(Uni<T> upstream, Duration timeout, Supplier<? extends T> fallback) {
        Objects.requireNonNull(upstream, "upstream must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        Objects.requireNonNull(fallback, "fallback must not be null");

        return upstream
                .ifNoItem().after(timeout).fail()
                .onFailure(TimeoutException.class).recoverWithItem(ignored -> fallback.get());
    }
}
