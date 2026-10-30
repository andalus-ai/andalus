package tech.kayys.andalus.execution.event.observability;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import tech.kayys.andalus.observability.DefaultObservabilityPublisher;
import tech.kayys.andalus.observability.ObservabilityPublisher;
import tech.kayys.andalus.observability.ObservabilitySink;

/**
 * Supplies a default publisher assembled from any installed sink beans.
 * Applications may replace this default with their own publisher bean.
 */
@ApplicationScoped
public class ObservabilityPublisherProducer {

    @Produces
    @ApplicationScoped
    @DefaultBean
    ObservabilityPublisher observabilityPublisher(Instance<ObservabilitySink> sinks) {
        return new DefaultObservabilityPublisher(sinks.stream().toList());
    }
}
