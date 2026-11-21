package tech.kayys.andalus.runtime.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import tech.kayys.andalus.anp.identity.DidWbaResolver;
import tech.kayys.andalus.anp.identity.TrustedDidDocumentStore;

/** Wires the ANP resolver to the runtime's durable trusted-document store. */
@ApplicationScoped
public class AnpIdentityRuntimeProducer {

    @Produces
    @ApplicationScoped
    public DidWbaResolver didWbaResolver(TrustedDidDocumentStore store) {
        return new DidWbaResolver(store);
    }
}
