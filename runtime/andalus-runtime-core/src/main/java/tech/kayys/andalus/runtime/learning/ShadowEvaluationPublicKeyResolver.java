package tech.kayys.andalus.runtime.learning;

import java.security.PublicKey;
import java.util.Optional;

/**
 * Deployment-managed key resolver. Implementations must resolve only managed key references;
 * they must not fetch arbitrary URLs or trust keys supplied by a manifest.
 */
public interface ShadowEvaluationPublicKeyResolver {
    Optional<PublicKey> resolve(String signerId, String publicKeyReference, String signatureAlgorithm);
}
