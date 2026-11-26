package tech.kayys.andalus.knowledge.exchange.coverage;

/**
 * Package anchor retained for legacy wildcard imports in the knowledge runtime.
 *
 * <p>The active coverage scoring contract currently lives in the
 * {@code exchange.routing} package. This type intentionally exposes no
 * capability or behavior; it prevents stale wildcard imports from making
 * the runtime reactor uncompilable while those imports are retired.</p>
 *
 * @deprecated Remove legacy {@code exchange.coverage.*} imports and then
 *             delete this package anchor.
 */
@Deprecated(forRemoval = true)
public final class KnowledgeExchangeCoveragePackageMarker {
    private KnowledgeExchangeCoveragePackageMarker() {
    }
}
