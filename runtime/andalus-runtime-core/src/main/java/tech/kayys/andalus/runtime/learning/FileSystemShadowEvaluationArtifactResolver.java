package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.evaluation.Evaluator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves artifacts beneath one deployment-controlled directory. References are relative paths;
 * absolute paths, traversal, symlinks, non-regular files and oversized artifacts are rejected.
 * The evaluator is loaded from the exact byte array later consumed by digest verification.
 */
public final class FileSystemShadowEvaluationArtifactResolver implements ShadowEvaluationArtifactResolver {
    private static final long MAX_ARTIFACT_BYTES = 512L * 1024 * 1024;
    private final Path root;
    private final ShadowEvaluationArtifactLoader loader;

    public FileSystemShadowEvaluationArtifactResolver(Path root, ShadowEvaluationArtifactLoader loader) {
        try {
            this.root = Objects.requireNonNull(root, "root").toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            throw new IllegalArgumentException("artifact root must exist and be resolvable", ex);
        }
        this.loader = Objects.requireNonNull(loader, "loader");
        if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(this.root))
            throw new IllegalArgumentException("artifact root must be a real directory");
    }

    @Override
    public ResolvedArtifact resolve(String immutableReference) {
        if (immutableReference == null || immutableReference.isBlank())
            throw new IllegalArgumentException("artifact reference is required");
        try {
            Path relative = Path.of(immutableReference);
            if (relative.isAbsolute()) throw new IllegalArgumentException("absolute artifact paths are forbidden");
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root) || target.equals(root))
                throw new IllegalArgumentException("artifact path escapes configured root");
            Path cursor = root;
            for (Path part : root.relativize(target)) {
                cursor = cursor.resolve(part);
                if (Files.isSymbolicLink(cursor))
                    throw new IllegalArgumentException("symbolic links are forbidden in artifact paths");
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("artifact must be a regular file");
            long size = Files.size(target);
            if (size < 1 || size > MAX_ARTIFACT_BYTES)
                throw new IllegalArgumentException("artifact size is outside the allowed range");
            byte[] bytes = Files.readAllBytes(target);
            if (bytes.length != size || bytes.length > MAX_ARTIFACT_BYTES)
                throw new IllegalStateException("artifact changed while being read or exceeded size limit");
            Evaluator evaluator = Objects.requireNonNull(loader.load(immutableReference, bytes),
                    "artifact loader returned no evaluator");
            return new ResolvedArtifact(new ByteArrayInputStream(bytes), evaluator);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalStateException("unable to read immutable evaluator artifact", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("unable to load evaluator from verified artifact bytes", ex);
        }
    }
}
