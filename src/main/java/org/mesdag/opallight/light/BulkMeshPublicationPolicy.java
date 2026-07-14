package org.mesdag.opallight.light;

/** 决定批量目标是否必须构造并原子发布完整 GPU generation。 */
final class BulkMeshPublicationPolicy {
    private BulkMeshPublicationPolicy() {
    }

    static boolean requiresAtomicReplacement(
            Object requestedToken,
            Object activeToken,
            boolean pairedCacheHit
    ) {
        return requestedToken != null                && !requestedToken.equals(activeToken)
                && !pairedCacheHit;
    }
}
