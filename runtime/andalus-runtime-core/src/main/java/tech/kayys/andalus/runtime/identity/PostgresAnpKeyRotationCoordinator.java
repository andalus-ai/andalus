package tech.kayys.andalus.runtime.identity;

import tech.kayys.andalus.anp.identity.AnpIdentityKeyPair;
import tech.kayys.andalus.anp.identity.AnpKeyStore;
import tech.kayys.andalus.anp.identity.DidWbaPublicKeyResolver;
import tech.kayys.andalus.anp.identity.TrustedDidDocument;
import tech.kayys.andalus.anp.identity.TrustedDidDocumentStore;
import tech.kayys.andalus.anp.identity.TrustedDidKeyRotationValidator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
    private final PostgresAnpKeyRotationRecoveryQueue recoveryQueue;

    public PostgresAnpKeyRotationCoordinator(
            PostgresAnpKeyRotationJournal journal,
            TrustedDidDocumentStore didStore,
            AnpKeyStore keyStore,
            AnpKeyRotationAuthorizer authorizer,
            AnpKeyRotationMaterialProvider materialProvider,
            PostgresAnpKeyRotationRecoveryQueue recoveryQueue) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.didStore = Objects.requireNonNull(didStore, "didStore");
        this.keyStore = Objects.requireNonNull(keyStore, "keyStore");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.materialProvider = Objects.requireNonNull(materialProvider, "materialProvider");
        this.recoveryQueue = Objects.requireNonNull(recoveryQueue, "recoveryQueue");
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
        if (prepared.state() == PostgresAnpKeyRotationJournal.RotationState.COMPLETED) {
            return prepared;
        }
        String owner = "foreground-" + UUID.randomUUID();
        if (!recoveryQueue.claimOperation(prepared.operationId(), owner, Duration.ofMinutes(5))) {
            throw new IllegalStateException("ANP key rotation is already owned by another worker");
        }
        try {
            return resumeWithLease(prepared.operationId(), candidate, newKeyPair, owner);
        } finally {
            recoveryQueue.release(prepared.operationId(), owner);
        }
    }

    /**
     * Claims a bounded batch and resumes each operation independently.
     * Failed operations retain their lease until expiry to avoid a tight retry loop.
     */
    public List<RecoveryResult> recoverPending(String workerId, Duration leaseDuration, int limit) {
        List<PostgresAnpKeyRotationJournal.Rotation> claimed =
                recoveryQueue.claimPending(workerId, leaseDuration, limit);
        List<RecoveryResult> results = new ArrayList<>(claimed.size());
        for (PostgresAnpKeyRotationJournal.Rotation rotation : claimed) {
            try {
                PostgresAnpKeyRotationJournal.Rotation completed = resumeWithProviderLease(rotation.operationId(), workerId);
                results.add(new RecoveryResult(rotation.operationId(), true,
                        completed.state().name(), null));
                recoveryQueue.release(rotation.operationId(), workerId);
            } catch (RuntimeException failure) {
                results.add(new RecoveryResult(rotation.operationId(), false,
                        rotation.state().name(), failure.getClass().getSimpleName()));
                // Keep the lease until expiry; the next worker attempt is naturally back-pressured.
            }
        }
        return List.copyOf(results);
    }

    public record RecoveryResult(String operationId, boolean completed,
                                 String lastKnownState, String failureType) { }

    /** Resumes an operation using material supplied by the configured KMS/HSM provider. */
    public PostgresAnpKeyRotationJournal.Rotation resume(String operationId) {
        PostgresAnpKeyRotationJournal.Rotation rotation = journal.find(operationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown rotation: " + operationId));
        authorizer.authorize(rotation.actorId(), rotation.did());
        if (rotation.state() == PostgresAnpKeyRotationJournal.RotationState.COMPLETED) {
            return rotation;
        }
        String owner = "foreground-" + UUID.randomUUID();
        if (!recoveryQueue.claimOperation(operationId, owner, Duration.ofMinutes(5))) {
            throw new IllegalStateException("ANP key rotation is already owned by another worker");
        }
        try {
            return resumeWithProviderLease(operationId, owner);
        } finally {
            recoveryQueue.release(operationId, owner);
        }
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
        if (rotation.state() == PostgresAnpKeyRotationJournal.RotationState.COMPLETED) {
            return rotation;
        }
        String owner = "foreground-" + UUID.randomUUID();
        if (!recoveryQueue.claimOperation(operationId, owner, Duration.ofMinutes(5))) {
            throw new IllegalStateException("ANP key rotation is already owned by another worker");
        }
        try {
            return resumeWithLease(operationId, candidate, newKeyPair, owner);
        } finally {
            recoveryQueue.release(operationId, owner);
        }
    }

    private PostgresAnpKeyRotationJournal.Rotation resumeWithProviderLease(String operationId, String owner) {
        PostgresAnpKeyRotationJournal.Rotation rotation = journal.find(operationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown rotation: " + operationId));
        assertLease(operationId, owner);
        authorizer.authorize(rotation.actorId(), rotation.did());
        AnpKeyRotationMaterialProvider.RotationMaterial material = materialProvider.load(rotation);
        return resumeWithLease(operationId, material.candidateDocument(), material.newKeyPair(), owner);
    }

    private PostgresAnpKeyRotationJournal.Rotation resumeWithLease(
            String operationId, TrustedDidDocument candidate, AnpIdentityKeyPair newKeyPair, String owner) {
        PostgresAnpKeyRotationJournal.Rotation rotation = journal.find(operationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown rotation: " + operationId));
        authorizer.authorize(rotation.actorId(), rotation.did());
        validateCandidate(rotationRequest(rotation), candidate, newKeyPair);

        while (rotation.state() != PostgresAnpKeyRotationJournal.RotationState.COMPLETED) {
            assertLease(operationId, owner);
            switch (rotation.state()) {
                case PREPARED -> {
                    assertLease(operationId, owner);
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
                    assertLease(operationId, owner);
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
                    assertLease(operationId, owner);
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

    private void assertLease(String operationId, String owner) {
        if (!recoveryQueue.renewLease(operationId, owner, Duration.ofMinutes(5))) {
            throw new IllegalStateException("ANP key rotation lease was lost; refusing further side effects");
        }
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
