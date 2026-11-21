package tech.kayys.andalus.runtime.learning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Read-only worker trust snapshot loaded once at process bootstrap.
 *
 * The trust directory must be image-baked or mounted read-only by the deployment. The file
 * format is UTF-8: one signerId=base64(X.509 SubjectPublicKeyInfo) Ed25519 key per line.
 * There is deliberately no request-driven trust update or network lookup.
 */
public final class FileShadowEvaluationWorkerTrustStore
        implements ShadowEvaluationWorkerManifestVerifier.TrustStore {

    private static final Pattern SIGNER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final int MAX_BUNDLE_BYTES = 1024 * 1024;
    private static final int MAX_SIGNERS = 1024;

    private final Map<String, ShadowEvaluationWorkerManifestVerifier.TrustedSigner> signers;

    public FileShadowEvaluationWorkerTrustStore(Path trustDirectory) throws Exception {
        if (trustDirectory == null || Files.isSymbolicLink(trustDirectory)
                || !Files.isDirectory(trustDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("worker trust directory is unavailable or unsafe");
        }
        Path bundle = trustDirectory.resolve("trusted-signers.ed25519");
        if (Files.isSymbolicLink(bundle) || !Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(bundle)) {
            throw new IOException("worker trust bundle is unavailable or unsafe");
        }
        long size = Files.size(bundle);
        if (size < 1 || size > MAX_BUNDLE_BYTES) throw new IOException("worker trust bundle size is invalid");

        Map<String, ShadowEvaluationWorkerManifestVerifier.TrustedSigner> loaded = new HashMap<>();
        int lineNumber = 0;
        for (String raw : Files.readAllLines(bundle, StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = line.indexOf('=');
            if (separator < 1 || separator == line.length() - 1 || line.indexOf('=', separator + 1) >= 0) {
                throw new IOException("invalid worker trust bundle line " + lineNumber);
            }
            String signerId = line.substring(0, separator).trim();
            String encodedKey = line.substring(separator + 1).trim();
            if (!SIGNER_ID.matcher(signerId).matches() || loaded.containsKey(signerId)
                    || loaded.size() >= MAX_SIGNERS) {
                throw new IOException("invalid or duplicate worker signer at line " + lineNumber);
            }
            byte[] keyBytes;
            try {
                keyBytes = Base64.getDecoder().decode(encodedKey);
            } catch (IllegalArgumentException malformed) {
                throw new IOException("invalid worker public key at line " + lineNumber);
            }
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
            loaded.put(signerId, new ShadowEvaluationWorkerManifestVerifier.TrustedSigner(
                    signerId, "Ed25519", key));
        }
        if (loaded.isEmpty()) throw new IOException("worker trust bundle contains no active signers");
        this.signers = Map.copyOf(loaded);
    }

    @Override
    public Optional<ShadowEvaluationWorkerManifestVerifier.TrustedSigner> findActiveSigner(String signerId) {
        return Optional.ofNullable(signers.get(signerId));
    }
}
