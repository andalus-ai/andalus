package tech.kayys.andalus.runtime.identity;

/**
 * Boundary to a KMS/HSM-backed envelope-encryption implementation for ANP private keys.
 *
 * <p>Implementations must use authenticated encryption and bind ciphertext to both
 * {@code agentId} and {@code keyId} as authenticated context (AAD). They must never
 * log plaintext or ciphertext. The runtime deliberately provides no home-grown
 * cryptographic fallback: production must supply a real KMS/HSM implementation.
 */
public interface AnpKeyMaterialProtector {

    /** Encrypts PKCS#8 private-key bytes using an envelope key managed outside this database. */
    byte[] protect(String agentId, String keyId, byte[] plaintextPkcs8);

    /** Authenticates and decrypts bytes previously protected for this exact agent/key pair. */
    byte[] unprotect(String agentId, String keyId, byte[] ciphertext);
}
