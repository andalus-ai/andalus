package tech.kayys.andalus.runtime.identity;

import tech.kayys.andalus.anp.identity.AnpIdentityKeyPair;
import tech.kayys.andalus.anp.identity.TrustedDidDocument;

/**
 * Supplies the candidate DID snapshot and signing key for a journaled operation.
 *
 * <p>Production implementations should resolve private material from a KMS/HSM or
 * encrypted secret store. The rotation journal intentionally stores identifiers only.
 */
@FunctionalInterface
public interface AnpKeyRotationMaterialProvider {
    RotationMaterial load(PostgresAnpKeyRotationJournal.Rotation rotation);

    record RotationMaterial(TrustedDidDocument candidateDocument, AnpIdentityKeyPair newKeyPair) {
        public RotationMaterial {
            if (candidateDocument == null || newKeyPair == null) {
                throw new IllegalArgumentException("Candidate DID document and new key pair are required");
            }
        }
    }
}
