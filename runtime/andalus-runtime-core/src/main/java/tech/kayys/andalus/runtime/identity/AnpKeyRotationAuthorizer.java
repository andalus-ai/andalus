package tech.kayys.andalus.runtime.identity;

/**
 * Runtime security boundary for DID signing-key rotation.
 *
 * <p>Implementations must authorize the actor against the DID and current tenant/security
 * context. The coordinator invokes this before creating a journal entry and on recovery.
 */
@FunctionalInterface
public interface AnpKeyRotationAuthorizer {
    void authorize(String actorId, String did);
}
