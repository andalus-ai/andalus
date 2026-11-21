package tech.kayys.andalus.runtime.identity;

import tech.kayys.andalus.anp.identity.AnpIdentityKeyPair;
import tech.kayys.andalus.anp.identity.AnpKeyStore;
import tech.kayys.andalus.anp.identity.DidWbaPublicKeyResolver;
import tech.kayys.andalus.anp.identity.TrustedDidDocument;
import tech.kayys.andalus.anp.identity.TrustedDidDocumentStore;
import tech.kayys.andalus.anp.identity.TrustedDidKeyRotationValidator;

import java.util.Objects;

/**
 * Fail-closed coordinator for recoverable ANP DID signing-key rotation.
 *
 * <p>The sequence is journaled around idempotent external effects:
 * trusted DID update, new-key activation, old-key revocation, completion.
 * DID and key stores are not one transaction; inbound authentication must continue
 * enforcing both trusted-DID authorization and active-key status during recovery.
 */
public class PostgresAnpKeyRotationCoordinator {
    private final PostgresAnpKeyRotationJournal journal;
    private final TrustedDidDocumentStore didStore;
    private final AnpKeyStore keyStore;
    private final AnpKeyRotationAuthorizer authorizer;
    private final AnpKeyRotationMaterialProvider materialProvider;

    public PostgresAnpKeyRotationCoordinator(
            PostgresAnpKeyRotationJournal journal,
            TrustedDidDocumentStore didStore,
            AnpKeyStore keyStore,
            AnpKeyRotationAuthorizer authorizer,
            AnpKeyRotationMaterialProvider materialProvider) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.didStore = Objects.requireNonNull(didStore, "didStore");
        this.keyStore = Objects.requireNonNull(keyStore, "keyStore");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.materialProvider = Objects.requireNonNull(materialProvider, "materialProvider");
    }

    /** Starts an authorized rotation and immediately drives it as far as possible. */
    public PostgresAnpKeyRotationJournal.Rotation rotate(
            PostgresAnpKeyRotationJournal.RotationRequest request,
            TrustedDidDocument candidate,
            AnpIdentityKeyPair newKeyPair) {
        Objects.requireNonNull(request, "request");
        authorizer.authorize(request.actorId(), request.did());
        validateCandidate(request, candidate, newKeyPair);
        boolean existing = journal.find(request.operationId()).isPresent();
        if (!existing) {
            requireCurrentRevision(request);
            if (keyStore.getActiveKey(request.oldKeyId()).isEmpty()) {
                throw new SecurityException("The old signing key is not currently active");
            }
        }
        PostgresAnpKeyRotationJournal.Rotation prepared = journal.findOrPrepare(request);
        return resume(prepared.operationId(), candidate, newKeyPair);
    }

    /** Resumes an operation using material supplied by the configured KMS/HSM provider. */
    public PostgresAnpKeyRotationJournal.Rotation resume(String operationId) {
        PostgresAnpKeyRotationJournal.Rotation rotation = journal.find(operationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown rotation: " + operationId));
        authorizer.authorize(rotation.actorId(), rotation.did());
        AnpKeyRotationMaterialProvider.RotationMaterial material = materialProvider.load(rotation);
        return resume(rotation.operationId(), material.candidateDocument(), material.newKeyPair());
    }

    /**
     * Drives one operation idempotently. This overload is useful for controlled recovery
     * handlers that already loaded key material from their protected secret store.
     */
    public PostgresAnpKeyRotationJournal.Rotation resume(
            String operationId, TrustedDidDocument candidate, AnpIdentityKeyPair newKeyPair) {
        PostgresAnpKeyRotationJournal.Rotation rotation = journal.find(operationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown rotation: " + operationId));
        authorizer.authorize(rotation.actorId(), rotation.did());
        validateCandidate(rotationRequest(rotation), candidate, newKeyPair);

        while (rotation.state() != PostgresAnpKeyRotationJournal.RotationState.COMPLETED) {
            switch (rotation.state()) {
                case PREPARED -> {
                    TrustedDidDocument stored = didStore.register(candidate);
                    if (stored.revision() != rotation.targetRevision()
                            || !stored.document().equals(candidate.document())) {
                        throw new SecurityException("Trusted DID store does not contain the proposed rotation snapshot");
                    }
                    rotation = journal.advance(operationId,
                            PostgresAnpKeyRotationJournal.RotationState.PREPARED,
                            PostgresAnpKeyRotationJournal.RotationState.DID_DOCUMENT_UPDATED);
                }
                case DID_DOCUMENT_UPDATED -> {
                    keyStore.storeKey(rotation.did(), newKeyPair);
                    if (keyStore.getActiveKey(rotation.newKeyId()).isEmpty()) {
                        throw new SecurityException("Key store did not activate the proposed signing key");
                    }
                    rotation = journal.advance(operationId,
                            PostgresAnpKeyRotationJournal.RotationState.DID_DOCUMENT_UPDATED,
                            PostgresAnpKeyRotationJournal.RotationState.NEW_KEY_ACTIVATED);
                }
                case NEW_KEY_ACTIVATED -> {
                    if (keyStore.getActiveKey(rotation.newKeyId()).isEmpty()) {
                        throw new SecurityException("New signing key is not active; refusing to revoke old key");
                    }
                    keyStore.revokeKey(rotation.oldKeyId());
                    if (keyStore.getActiveKey(rotation.oldKeyId()).isPresent()) {
                        throw new SecurityException("Old signing key remains active after revocation");
                    }
                    rotation = journal.advance(operationId,
                            PostgresAnpKeyRotationJournal.RotationState.NEW_KEY_ACTIVATED,
                            PostgresAnpKeyRotationJournal.RotationState.OLD_KEY_REVOKED);
                }
                case OLD_KEY_REVOKED -> rotation = journal.advance(operationId,
                        PostgresAnpKeyRotationJournal.RotationState.OLD_KEY_REVOKED,
                        PostgresAnpKeyRotationJournal.RotationState.COMPLETED);
                case COMPLETED -> { }
            }
        }
        return rotation;
    }

    private void requireCurrentRevision(PostgresAnpKeyRotationJournal.RotationRequest request) {
        TrustedDidDocument current = didStore.find(request.did())
                .orElseThrow(() -> new SecurityException("Cannot rotate an untrusted DID document"));
        if (current.revision() != request.expectedRevision()) {
            throw new SecurityException("Trusted DID revision changed before rotation");
        }
    }

    private static void validateCandidate(PostgresAnpKeyRotationJournal.RotationRequest request,
                                          TrustedDidDocument candidate, AnpIdentityKeyPair keyPair) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(keyPair, "newKeyPair");
        if (!request.did().equals(candidate.document().id())
                || request.targetRevision() != candidate.revision()
                || !request.newKeyId().equals(keyPair.keyId())) {
            throw new SecurityException("Rotation material does not match the journal request");
        }
        TrustedDidKeyRotationValidator.validate(candidate, keyPair);
        try {
            DidWbaPublicKeyResolver.resolveAuthenticationKey(candidate.document(), request.oldKeyId());
            throw new SecurityException("Candidate DID document still authorizes the old signing key");
        } catch (SecurityException expected) {
            if ("Candidate DID document still authorizes the old signing key".equals(expected.getMessage())) {
                throw expected;
            }
            // Absence from the authentication relationship is the required retirement condition.
        }
    }

    private static PostgresAnpKeyRotationJournal.RotationRequest rotationRequest(
            PostgresAnpKeyRotationJournal.Rotation rotation) {
        return new PostgresAnpKeyRotationJournal.RotationRequest(
                rotation.operationId(), rotation.did(), rotation.actorId(), rotation.oldKeyId(),
                rotation.newKeyId(), rotation.expectedRevision(), rotation.targetRevision());
    }
}
