package org.mesdag.opallight.light;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.LongPredicate;

/**
 * 一次可见彩光状态的不可拆分所有权单元。
 *
 * <p>generation 同时绑定精确布局身份、全部生命周期修订号、冻结 CPU 光场和一份 GPU
 * 网格租约。调用方不能只把 CPU 快照或 GPU map 单独放进缓存，否则命中时可能把不同代的
 * 光场与网格拼在一起，重新造成整区灰块、残影或短暂穿墙。</p>
 *
 * <p>每个 {@code LightGeneration} 实例只代表一个 owner。活动状态进入缓存时使用
 * {@link #retainForCache()} 创建第二个 owner；两者共享不可变 CPU 数组，并各持一份 GPU
 * lease。缓存淘汰只关闭缓存 owner，直到最后一个 owner 关闭时才释放底层 GPU 资源。</p>
 *
 * <p>本类不负责决定在哪个线程创建或销毁 GPU 资源；这一约束由 {@link GpuMeshSet} 注入的
 * Render-thread 断言统一执行。这样即使缓存清理路径未来迁移，也不能在 worker 或 GC 线程
 * 静默关闭 VBO。</p>
 */
final class LightGeneration<T> implements AutoCloseable {
    /**
     * 所有会改变 generation 语义的修订号。
     *
     * <p>精确布局相同仍不代表可以跨世界、跨资源规则或跨模型数据复用，因此缓存命中必须
     * 同时比较整个键。字段保持为值类型，便于 worker 完成时再次核对是否已经过期。</p>
     */
    record RevisionKey(
            long levelSession,
            long sourceContent,
            long chunkLifecycle,
            long rulesResource,
            long modelVisual,
            long loadedDomain
    ) {
    }

    private static final long BYTES_PER_SECTION = 4096L * Short.BYTES;

    private final long generationId;
    private final Object exactIdentity;
    private final RevisionKey revisions;
    private final RgbLightEngine.ChunkSnapshot cpuState;
    private final GpuMeshSet<T>.Lease gpuMeshes;
    private final boolean converged;
    private boolean closed;

    LightGeneration(
            long generationId,
            Object exactIdentity,
            RevisionKey revisions,
            RgbLightEngine.ChunkSnapshot cpuState,
            GpuMeshSet<T>.Lease gpuMeshes,
            boolean converged
    ) {
        if (generationId < 0L) {
            throw new IllegalArgumentException("generationId must not be negative");
        }
        this.generationId = generationId;
        this.exactIdentity = Objects.requireNonNull(exactIdentity, "exactIdentity");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.cpuState = Objects.requireNonNull(cpuState, "cpuState");
        this.gpuMeshes = Objects.requireNonNull(gpuMeshes, "gpuMeshes");
        this.converged = converged;
    }

    long generationId() {
        requireOpen();
        return generationId;
    }

    Object exactIdentity() {
        requireOpen();
        return exactIdentity;
    }

    RevisionKey revisions() {
        requireOpen();
        return revisions;
    }

    RgbLightEngine.ChunkSnapshot cpuState() {
        requireOpen();
        return cpuState;
    }

    boolean converged() {
        requireOpen();
        return converged;
    }

    boolean matches(Object candidateIdentity, RevisionKey candidateRevisions) {
        requireOpen();
        return exactIdentity.equals(candidateIdentity) && revisions.equals(candidateRevisions);
    }

    long cacheWeightBytes() {
        requireOpen();
        return saturatedAdd(
                saturatedMultiply(cpuState.sectionCount(), BYTES_PER_SECTION),
                gpuMeshes.gpuBytes()
        );
    }

    boolean allGpuMeshesMatch(Predicate<? super T> predicate) {
        requireOpen();
        return gpuMeshes.allMatch(predicate);
    }

    void forEachGpuMesh(BiConsumer<Long, ? super T> consumer) {
        requireOpen();
        gpuMeshes.forEach(consumer);
    }

    boolean anyGpuSectionMatches(LongPredicate predicate) {
        requireOpen();
        return gpuMeshes.anyKeyMatches(predicate);
    }

    /**
     * 为 generation cache 创建独立 owner。
     *
     * <p>尚有队列、上传或模型刷新未收敛的候选代不能进入缓存，否则下一次命中会发布一个
     * 本就不完整的状态。CPU 快照无需复制；GPU 通过 retain 增加一份 Render-thread lease。</p>
     */
    LightGeneration<T> retainForCache() {
        requireOpen();
        if (!converged) {
            throw new IllegalStateException("An unconverged generation cannot enter the cache");
        }
        return new LightGeneration<>(
                generationId,
                exactIdentity,
                revisions,
                cpuState,
                gpuMeshes.retain(),
                true
        );
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        try {
            gpuMeshes.close();
        } finally {
            /*
             * 越线程断言发生在 lease 标记关闭之前，此时 generation 必须保持可重试；
             * disposer 异常发生在 lease 已接管并尝试释放全部资源之后，此时则必须封死 owner。
             */
            closed = gpuMeshes.closedForOwner();
        }
    }

    boolean isOpen() {
        return !closed;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Generation owner is already closed");
        }
    }

    private static long saturatedMultiply(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }
}
