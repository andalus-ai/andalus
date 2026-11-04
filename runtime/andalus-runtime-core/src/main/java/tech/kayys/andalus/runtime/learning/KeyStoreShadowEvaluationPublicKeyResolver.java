package tech.kayys.andalus.runtime.learning;

import java.security.KeyStore;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves public keys from a deployment-loaded Java KeyStore. The registry's public-key
 * reference is treated as an exact KeyStore alias, never as a URL or filesystem path.
 * KeyStore loading, password handling, rotation and storage remain deployment responsibilities.
 */
public final class KeyStoreShadowEvaluationPublicKeyResolver implements ShadowEvaluationPublicKeyResolver {
    private final KeyStore keyStore;

    public KeyStoreShadowEvaluationPublicKeyResolver(KeyStore keyStore) {
        this.keyStore = Objects.requireNonNull(keyStore, "keyStore");
    }

    @Override
    public Optional<PublicKey> resolve(String signerId, String publicKeyReference, String signatureAlgorithm) {
        if (signerId == null || signerId.isBlank()
                || publicKeyReference == null || publicKeyReference.isBlank()
                || !"Ed25519".equalsIgnoreCase(signatureAlgorithm)) return Optional.empty();
        try {
            Certificate certificate = keyStore.getCertificate(publicKeyReference);
            if (certificate == null) return Optional.empty();
            PublicKey key = certificate.getPublicKey();
            if (!"Ed25519".equalsIgnoreCase(key.getAlgorithm())
                    && !"EdDSA".equalsIgnoreCase(key.getAlgorithm())) return Optional.empty();
            return Optional.of(key);
        } catch (Exception unavailable) {
            return Optional.empty();
        }
    }
}
