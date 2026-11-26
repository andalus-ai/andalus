package tech.kayys.andalus.runtime.identity;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * AWS KMS envelope-encryption implementation for ANP private keys.
 *
 * <p>Each protection operation obtains a fresh 256-bit data key from KMS, encrypts
 * the PKCS#8 bytes locally with AES-256-GCM, and stores the KMS-wrapped data key
 * alongside the nonce and authenticated ciphertext. The KMS encryption context
 * binds the wrapped data key to the exact agent and key identifier; the same values
 * are also authenticated as GCM additional data.
 *
 * <p>Configure the KMS key ARN/ID through trusted application configuration. The
 * KMS client lifecycle and credentials remain application-owned.
 */
public final class AwsKmsAnpKeyMaterialProtector implements AnpKeyMaterialProtector {
    private static final int MAGIC = 0x414E4B31; // "ANK1"
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_WRAPPED_KEY_BYTES = 64 * 1024;
    private static final int MAX_CIPHERTEXT_BYTES = 1024 * 1024;

    private final KmsClient kmsClient;
    private final String kmsKeyId;
    private final SecureRandom secureRandom;

    public AwsKmsAnpKeyMaterialProtector(KmsClient kmsClient, String kmsKeyId) {
        this(kmsClient, kmsKeyId, new SecureRandom());
    }

    AwsKmsAnpKeyMaterialProtector(KmsClient kmsClient, String kmsKeyId, SecureRandom secureRandom) {
        this.kmsClient = Objects.requireNonNull(kmsClient, "kmsClient");
        if (kmsKeyId == null || kmsKeyId.isBlank()) {
            throw new IllegalArgumentException("kmsKeyId must not be blank");
        }
        this.kmsKeyId = kmsKeyId;
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    @Override
    public byte[] protect(String agentId, String keyId, byte[] plaintextPkcs8) {
        requireContext(agentId, keyId);
        if (plaintextPkcs8 == null || plaintextPkcs8.length == 0) {
            throw new IllegalArgumentException("plaintextPkcs8 must not be empty");
        }

        byte[] dataKey = null;
        try {
            var generated = kmsClient.generateDataKey(GenerateDataKeyRequest.builder()
                    .keyId(kmsKeyId)
                    .keySpec(DataKeySpec.AES_256)
                    .encryptionContext(encryptionContext(agentId, keyId))
                    .build());
            dataKey = generated.plaintext().asByteArray();
            byte[] wrappedKey = generated.ciphertextBlob().asByteArray();
            if (dataKey.length != 32 || wrappedKey.length == 0 || wrappedKey.length > MAX_WRAPPED_KEY_BYTES) {
                throw new IllegalStateException("AWS KMS returned invalid envelope key material");
            }

            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(additionalAuthenticatedData(agentId, keyId));
            byte[] encrypted = cipher.doFinal(plaintextPkcs8);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(wrappedKey.length);
                output.write(wrappedKey);
                output.write(nonce);
                output.writeInt(encrypted.length);
                output.write(encrypted);
            }
            return bytes.toByteArray();
        } catch (GeneralSecurityException | java.io.IOException exception) {
            throw new IllegalStateException("Unable to encrypt ANP private key material", exception);
        } finally {
            if (dataKey != null) Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public byte[] unprotect(String agentId, String keyId, byte[] envelope) {
        requireContext(agentId, keyId);
        if (envelope == null || envelope.length < 4 + 4 + 1 + NONCE_BYTES + 4 + 16) {
            throw new SecurityException("Invalid ANP key envelope");
        }

        byte[] dataKey = null;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(envelope))) {
            if (input.readInt() != MAGIC) throw new SecurityException("Unsupported ANP key envelope version");
            int wrappedLength = input.readInt();
            if (wrappedLength < 1 || wrappedLength > MAX_WRAPPED_KEY_BYTES
                    || wrappedLength > input.available() - NONCE_BYTES - 4 - 16) {
                throw new SecurityException("Invalid wrapped data-key length");
            }
            byte[] wrappedKey = input.readNBytes(wrappedLength);
            byte[] nonce = input.readNBytes(NONCE_BYTES);
            int ciphertextLength = input.readInt();
            if (ciphertextLength < 16 || ciphertextLength > MAX_CIPHERTEXT_BYTES
                    || ciphertextLength != input.available()) {
                throw new SecurityException("Invalid ANP key ciphertext length");
            }
            byte[] encrypted = input.readNBytes(ciphertextLength);

            dataKey = kmsClient.decrypt(DecryptRequest.builder()
                    .ciphertextBlob(SdkBytes.fromByteArray(wrappedKey))
                    .encryptionContext(encryptionContext(agentId, keyId))
                    .build()).plaintext().asByteArray();
            if (dataKey.length != 32) throw new SecurityException("AWS KMS returned an invalid data key");

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(additionalAuthenticatedData(agentId, keyId));
            return cipher.doFinal(encrypted);
        } catch (SecurityException exception) {
            throw exception;
        } catch (GeneralSecurityException | java.io.IOException exception) {
            throw new SecurityException("Unable to authenticate ANP private key envelope", exception);
        } finally {
            if (dataKey != null) Arrays.fill(dataKey, (byte) 0);
        }
    }

    private static Map<String, String> encryptionContext(String agentId, String keyId) {
        return Map.of("andalus:agent-id", agentId, "andalus:key-id", keyId);
    }

    private static byte[] additionalAuthenticatedData(String agentId, String keyId) {
        return ("andalus-anp-key-v1|" + agentId.length() + ":" + agentId
                + "|" + keyId.length() + ":" + keyId).getBytes(StandardCharsets.UTF_8);
    }

    private static void requireContext(String agentId, String keyId) {
        if (agentId == null || agentId.isBlank() || keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("agentId and keyId must not be blank");
        }
    }
}
