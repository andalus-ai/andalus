package tech.kayys.andalus.runtime.identity;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AwsKmsAnpKeyMaterialProtectorTest {
    private static final byte[] DATA_KEY = new byte[32];

    @Test
    void roundTripsWithKmsWrappedDataKeyAndBoundEncryptionContext() {
        KmsClient kms = mock(KmsClient.class);
        byte[] wrapped = new byte[]{9, 8, 7, 6};
        when(kms.generateDataKey(any(GenerateDataKeyRequest.class))).thenReturn(
                GenerateDataKeyResponse.builder()
                        .plaintext(SdkBytes.fromByteArray(DATA_KEY))
                        .ciphertextBlob(SdkBytes.fromByteArray(wrapped))
                        .build());
        when(kms.decrypt(any(DecryptRequest.class))).thenReturn(
                DecryptResponse.builder().plaintext(SdkBytes.fromByteArray(DATA_KEY)).build());

        AwsKmsAnpKeyMaterialProtector protector = new AwsKmsAnpKeyMaterialProtector(kms, "alias/andalus-anp");
        byte[] privateKey = "pkcs8-private-key".getBytes(StandardCharsets.UTF_8);
        byte[] envelope = protector.protect("did:wba:example:agent", "did:wba:example:agent#key-1", privateKey);

        assertFalse(Arrays.equals(privateKey, envelope));
        assertArrayEquals(privateKey,
                protector.unprotect("did:wba:example:agent", "did:wba:example:agent#key-1", envelope));

        var generateCaptor = org.mockito.ArgumentCaptor.forClass(GenerateDataKeyRequest.class);
        verify(kms).generateDataKey(generateCaptor.capture());
        assertEquals("alias/andalus-anp", generateCaptor.getValue().keyId());
        assertEquals(DataKeySpec.AES_256, generateCaptor.getValue().keySpec());
        assertEquals(Map.of("andalus:agent-id", "did:wba:example:agent",
                "andalus:key-id", "did:wba:example:agent#key-1"),
                generateCaptor.getValue().encryptionContext());

        var decryptCaptor = org.mockito.ArgumentCaptor.forClass(DecryptRequest.class);
        verify(kms).decrypt(decryptCaptor.capture());
        assertEquals(generateCaptor.getValue().encryptionContext(), decryptCaptor.getValue().encryptionContext());
        assertArrayEquals(wrapped, decryptCaptor.getValue().ciphertextBlob().asByteArray());
    }

    @Test
    void rejectsEnvelopeReplayedForAnotherAgentOrKey() {
        KmsClient kms = mock(KmsClient.class);
        when(kms.generateDataKey(any(GenerateDataKeyRequest.class))).thenReturn(
                GenerateDataKeyResponse.builder()
                        .plaintext(SdkBytes.fromByteArray(DATA_KEY))
                        .ciphertextBlob(SdkBytes.fromByteArray(new byte[]{1, 2, 3}))
                        .build());
        when(kms.decrypt(any(DecryptRequest.class))).thenReturn(
                DecryptResponse.builder().plaintext(SdkBytes.fromByteArray(DATA_KEY)).build());
        AwsKmsAnpKeyMaterialProtector protector = new AwsKmsAnpKeyMaterialProtector(kms, "alias/test");
        byte[] envelope = protector.protect("agent-a", "key-a", new byte[]{1, 2, 3, 4});

        assertThrows(SecurityException.class, () -> protector.unprotect("agent-b", "key-a", envelope));
        assertThrows(SecurityException.class, () -> protector.unprotect("agent-a", "key-b", envelope));
    }

    @Test
    void rejectsMalformedAndTamperedEnvelopes() {
        KmsClient kms = mock(KmsClient.class);
        AwsKmsAnpKeyMaterialProtector protector = new AwsKmsAnpKeyMaterialProtector(kms, "alias/test");
        assertThrows(SecurityException.class, () -> protector.unprotect("agent", "key", new byte[]{1, 2, 3}));
    }
}
