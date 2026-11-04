package tech.kayys.andalus.runtime.learning;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable exact-version manifest registry. Registration is immutable; signer revocation is the
 * only mutable trust-state operation. Cryptographic signature verification remains a separate SPI.
 */
public final class JdbcShadowEvaluationTrustRegistry implements ShadowEvaluationTrustRegistry {
    public enum SignerStatus { ACTIVE, REVOKED }

    public record Signer(String id, String signatureAlgorithm, String publicKeyReference,
                         SignerStatus status, Instant registeredAt, Instant revokedAt) {
        public Signer {
            required(id, 255, "id");
            required(signatureAlgorithm, 64, "signatureAlgorithm");
            required(publicKeyReference, 2048, "publicKeyReference");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(registeredAt, "registeredAt");
            if (status == SignerStatus.REVOKED && revokedAt == null)
                throw new IllegalArgumentException("revoked signer requires revokedAt");
            if (status == SignerStatus.ACTIVE && revokedAt != null)
                throw new IllegalArgumentException("active signer cannot have revokedAt");
        }
    }

    private final DataSource dataSource;

    public JdbcShadowEvaluationTrustRegistry(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void verifySchema() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT signer_id FROM andalus_shadow_trusted_signer WHERE 1 = 0");
             PreparedStatement d = c.prepareStatement("SELECT dataset_id FROM andalus_shadow_dataset_manifest WHERE 1 = 0");
             PreparedStatement e = c.prepareStatement("SELECT candidate_id FROM andalus_shadow_evaluator_manifest WHERE 1 = 0")) {
            s.executeQuery().close(); d.executeQuery().close(); e.executeQuery().close();
        } catch (SQLException ex) {
            throw new IllegalStateException("Shadow evaluation trust registry schema is not ready", ex);
        }
    }

    /** Registers a signer once. Key bytes are never stored here; publicKeyReference resolves them in the trust store. */
    public Signer registerSigner(String id, String algorithm, String publicKeyReference) {
        required(id, 255, "id"); required(algorithm, 64, "algorithm");
        required(publicKeyReference, 2048, "publicKeyReference");
        verifySchema();
        try (Connection c = dataSource.getConnection()) {
            Optional<Signer> existing = findSigner(c, id);
            if (existing.isPresent()) {
                Signer signer = existing.get();
                if (!signer.signatureAlgorithm().equals(algorithm)
                        || !signer.publicKeyReference().equals(publicKeyReference)
                        || signer.status() != SignerStatus.ACTIVE)
                    throw new IllegalStateException("signer ID is already registered with different or revoked trust metadata");
                return signer;
            }
            Instant now = Instant.now();
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO andalus_shadow_trusted_signer
                    (signer_id, signature_algorithm, public_key_reference, signer_status,
                     registered_at_epoch_millis, revoked_at_epoch_millis)
                    VALUES (?, ?, ?, 'ACTIVE', ?, NULL)
                    """)) {
                insert.setString(1, id); insert.setString(2, algorithm);
                insert.setString(3, publicKeyReference); insert.setLong(4, now.toEpochMilli());
                insert.executeUpdate();
            }
            return new Signer(id, algorithm, publicKeyReference, SignerStatus.ACTIVE,
                    Instant.ofEpochMilli(now.toEpochMilli()), null);
        } catch (SQLException ex) {
            Optional<Signer> concurrent = findSigner(id);
            if (concurrent.isPresent() && concurrent.get().signatureAlgorithm().equals(algorithm)
                    && concurrent.get().publicKeyReference().equals(publicKeyReference)
                    && concurrent.get().status() == SignerStatus.ACTIVE) return concurrent.get();
            throw new IllegalStateException("Unable to register trusted signer", ex);
        }
    }

    /** Revocation is immediate for registry lookups and cannot be reversed by re-registering the same ID. */
    public Signer revokeSigner(String id) {
        required(id, 255, "id"); verifySchema();
        try (Connection c = dataSource.getConnection()) {
            Signer signer = findSigner(c, id).orElseThrow(() -> new IllegalArgumentException("unknown signer"));
            if (signer.status() == SignerStatus.REVOKED) return signer;
            Instant now = Instant.now();
            try (PreparedStatement update = c.prepareStatement("""
                    UPDATE andalus_shadow_trusted_signer
                    SET signer_status = 'REVOKED', revoked_at_epoch_millis = ?
                    WHERE signer_id = ? AND signer_status = 'ACTIVE'
                    """)) {
                update.setLong(1, now.toEpochMilli()); update.setString(2, id);
                update.executeUpdate();
            }
            return new Signer(signer.id(), signer.signatureAlgorithm(), signer.publicKeyReference(),
                    SignerStatus.REVOKED, signer.registeredAt(), Instant.ofEpochMilli(now.toEpochMilli()));
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to revoke trusted signer", ex);
        }
    }

    public Optional<Signer> findSigner(String id) {
        verifySchema();
        try (Connection c = dataSource.getConnection()) { return findSigner(c, id); }
        catch (SQLException ex) { throw new IllegalStateException("Unable to read signer trust metadata", ex); }
    }

    private Optional<Signer> findSigner(Connection c, String id) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("""
                SELECT signer_id, signature_algorithm, public_key_reference, signer_status,
                       registered_at_epoch_millis, revoked_at_epoch_millis
                FROM andalus_shadow_trusted_signer WHERE signer_id = ?
                """)) {
            q.setString(1, id);
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) return Optional.empty();
                long revoked = r.getLong("revoked_at_epoch_millis");
                Instant revokedAt = r.wasNull() ? null : Instant.ofEpochMilli(revoked);
                return Optional.of(new Signer(r.getString("signer_id"), r.getString("signature_algorithm"),
                        r.getString("public_key_reference"), SignerStatus.valueOf(r.getString("signer_status")),
                        Instant.ofEpochMilli(r.getLong("registered_at_epoch_millis")), revokedAt));
            }
        }
    }

    public void registerDataset(ShadowEvaluationManifest.DatasetManifest manifest) {
        Objects.requireNonNull(manifest, "manifest"); verifySchema();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            requireActiveSigner(c, manifest.signerId());
            Optional<ShadowEvaluationManifest.DatasetManifest> existing =
                    findDataset(c, manifest.datasetId(), manifest.datasetVersion());
            if (existing.isPresent()) {
                if (existing.get().equals(manifest)) { c.commit(); return; }
                throw new IllegalStateException("dataset ID/version is already bound to a different manifest");
            }
            try (PreparedStatement q = c.prepareStatement("""
                    INSERT INTO andalus_shadow_dataset_manifest
                    (dataset_id, dataset_version, schema_version, case_count, content_fingerprint,
                     source_reference, signer_id, signature, registered_at_epoch_millis)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                q.setString(1, manifest.datasetId()); q.setString(2, manifest.datasetVersion());
                q.setString(3, manifest.schemaVersion()); q.setInt(4, manifest.caseCount());
                q.setString(5, manifest.contentFingerprint()); q.setString(6, manifest.sourceReference());
                q.setString(7, manifest.signerId()); q.setString(8, manifest.signature());
                q.setLong(9, Instant.now().toEpochMilli()); q.executeUpdate();
            }
            c.commit();
        } catch (SQLException ex) {
            Optional<ShadowEvaluationManifest.DatasetManifest> concurrent =
                    findDataset(manifest.datasetId(), manifest.datasetVersion());
            if (concurrent.filter(manifest::equals).isPresent()) return;
            throw new IllegalStateException("Unable to register dataset manifest", ex);
        }
    }

    public void registerEvaluatorArtifact(ShadowEvaluationManifest.EvaluatorArtifactManifest manifest) {
        Objects.requireNonNull(manifest, "manifest"); verifySchema();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            requireActiveSigner(c, manifest.signerId());
            Optional<ShadowEvaluationManifest.EvaluatorArtifactManifest> existing = findEvaluatorArtifact(c,
                    manifest.candidateId(), manifest.candidateVersion(), manifest.evaluatorId(),
                    manifest.evaluatorVersion(), manifest.configurationFingerprint());
            if (existing.isPresent()) {
                if (existing.get().equals(manifest)) { c.commit(); return; }
                throw new IllegalStateException("candidate/evaluator version is already bound to a different manifest");
            }
            try (PreparedStatement q = c.prepareStatement("""
                    INSERT INTO andalus_shadow_evaluator_manifest
                    (candidate_id, candidate_version, evaluator_id, evaluator_version,
                     configuration_fingerprint, artifact_id, artifact_version, artifact_reference,
                     artifact_digest, signer_id, signature, registered_at_epoch_millis)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                q.setString(1, manifest.candidateId()); q.setString(2, manifest.candidateVersion());
                q.setString(3, manifest.evaluatorId()); q.setString(4, manifest.evaluatorVersion());
                q.setString(5, manifest.configurationFingerprint()); q.setString(6, manifest.artifactId());
                q.setString(7, manifest.artifactVersion()); q.setString(8, manifest.artifactReference());
                q.setString(9, manifest.artifactDigest()); q.setString(10, manifest.signerId());
                q.setString(11, manifest.signature()); q.setLong(12, Instant.now().toEpochMilli());
                q.executeUpdate();
            }
            c.commit();
        } catch (SQLException ex) {
            Optional<ShadowEvaluationManifest.EvaluatorArtifactManifest> concurrent = findEvaluatorArtifact(
                    manifest.candidateId(), manifest.candidateVersion(), manifest.evaluatorId(),
                    manifest.evaluatorVersion(), manifest.configurationFingerprint());
            if (concurrent.filter(manifest::equals).isPresent()) return;
            throw new IllegalStateException("Unable to register evaluator artifact manifest", ex);
        }
    }

    @Override
    public Optional<ShadowEvaluationManifest.DatasetManifest> findDataset(String id, String version) {
        verifySchema();
        try (Connection c = dataSource.getConnection()) { return findDataset(c, id, version); }
        catch (SQLException ex) { throw new IllegalStateException("Unable to read dataset manifest", ex); }
    }

    private Optional<ShadowEvaluationManifest.DatasetManifest> findDataset(Connection c, String id, String version)
            throws SQLException {
        try (PreparedStatement q = c.prepareStatement("""
                SELECT m.dataset_id, m.dataset_version, m.schema_version, m.case_count,
                       m.content_fingerprint, m.source_reference, m.signer_id, m.signature
                FROM andalus_shadow_dataset_manifest m
                JOIN andalus_shadow_trusted_signer s ON s.signer_id = m.signer_id
                WHERE m.dataset_id = ? AND m.dataset_version = ? AND s.signer_status = 'ACTIVE'
                """)) {
            q.setString(1, id); q.setString(2, version);
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new ShadowEvaluationManifest.DatasetManifest(
                        r.getString(1), r.getString(2), r.getString(3), r.getInt(4),
                        r.getString(5), r.getString(6), r.getString(7), r.getString(8)));
            }
        }
    }

    @Override
    public Optional<ShadowEvaluationManifest.EvaluatorArtifactManifest> findEvaluatorArtifact(
            String candidateId, String candidateVersion, String evaluatorId,
            String evaluatorVersion, String fingerprint) {
        verifySchema();
        try (Connection c = dataSource.getConnection()) {
            return findEvaluatorArtifact(c, candidateId, candidateVersion, evaluatorId, evaluatorVersion, fingerprint);
        } catch (SQLException ex) { throw new IllegalStateException("Unable to read evaluator artifact manifest", ex); }
    }

    private Optional<ShadowEvaluationManifest.EvaluatorArtifactManifest> findEvaluatorArtifact(
            Connection c, String candidateId, String candidateVersion, String evaluatorId,
            String evaluatorVersion, String fingerprint) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("""
                SELECT m.artifact_id, m.artifact_version, m.artifact_reference, m.artifact_digest,
                       m.candidate_id, m.candidate_version, m.evaluator_id, m.evaluator_version,
                       m.configuration_fingerprint, m.signer_id, m.signature
                FROM andalus_shadow_evaluator_manifest m
                JOIN andalus_shadow_trusted_signer s ON s.signer_id = m.signer_id
                WHERE m.candidate_id = ? AND m.candidate_version = ? AND m.evaluator_id = ?
                  AND m.evaluator_version = ? AND m.configuration_fingerprint = ?
                  AND s.signer_status = 'ACTIVE'
                """)) {
            q.setString(1, candidateId); q.setString(2, candidateVersion); q.setString(3, evaluatorId);
            q.setString(4, evaluatorVersion); q.setString(5, fingerprint);
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new ShadowEvaluationManifest.EvaluatorArtifactManifest(
                        r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6), r.getString(7), r.getString(8),
                        r.getString(9), r.getString(10), r.getString(11)));
            }
        }
    }

    private void requireActiveSigner(Connection c, String signerId) throws SQLException {
        // Hold the signer row lock until the manifest transaction commits. A concurrent revoke
        // therefore linearizes either before registration (which is rejected) or after it
        // (which immediately hides the newly registered manifest from lookups).
        try (PreparedStatement q = c.prepareStatement("""
                SELECT signer_status FROM andalus_shadow_trusted_signer
                WHERE signer_id = ? FOR UPDATE
                """)) {
            q.setString(1, signerId);
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("manifest signer is not registered");
                if (SignerStatus.valueOf(r.getString(1)) != SignerStatus.ACTIVE)
                    throw new IllegalArgumentException("manifest signer is revoked");
            }
        }
    }

    private static void required(String value, int max, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > max)
            throw new IllegalArgumentException(field + " must be non-blank and at most " + max + " characters");
    }
}
