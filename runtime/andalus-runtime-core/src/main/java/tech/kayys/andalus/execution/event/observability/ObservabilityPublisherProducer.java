package tech.kayys.andalus.execution.event.observability;

import io.quarkus.arc.DefaultBean;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.andalus.observability.DefaultObservabilityPublisher;
import tech.kayys.andalus.observability.ObservabilityPublisher;
import tech.kayys.andalus.observability.ObservabilitySink;

import java.time.Duration;
import java.util.Locale;

/**
 * Creates the default bounded asynchronous publisher and owns its lifecycle.
 * Applications can replace the publisher bean when they need a different
 * transport or delivery policy.
 */
@ApplicationScoped
public class ObservabilityPublisherProducer {

    @ConfigProperty(name = "andalus.observability.async.queue-capacity", defaultValue = "4096")
    int queueCapacity;

    @ConfigProperty(name = "andalus.observability.async.overflow-policy", defaultValue = "DROP_OLDEST")
    String overflowPolicy;

    @ConfigProperty(name = "andalus.observability.async.shutdown-timeout-ms", defaultValue = "5000")
    long shutdownTimeoutMillis;

    private BoundedAsyncObservabilityPublisher publisher;

    @Produces
    @ApplicationScoped
    @DefaultBean
    synchronized ObservabilityPublisher observabilityPublisher(Instance<ObservabilitySink> sinks) {
        if (publisher == null) {
            var synchronous = new DefaultObservabilityPublisher(sinks.stream().toList());
            var policy = BoundedAsyncObservabilityPublisher.OverflowPolicy.valueOf(
                    overflowPolicy.trim().toUpperCase(Locale.ROOT));
            publisher = new BoundedAsyncObservabilityPublisher(
                    synchronous,
                    queueCapacity,
                    policy,
                    Duration.ofMillis(shutdownTimeoutMillis));
        }
        return publisher;
    }

    @PreDestroy
    void shutdown() {
        if (publisher != null) {
            publisher.close();
        }
    }
}
