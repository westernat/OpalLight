package org.mesdag.opallight.light;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * 已发布复合彩光代的唯一 active/cache 所有权层。
 *
 * <p>每个缓存项始终是 {@link LightGeneration} 的独立 owner：它与 active 共享冻结 CPU
 * section，但各自持有一份 GPU lease。预算淘汰只能关闭缓存 owner，不能影响屏幕上仍在
 * 使用的 active。命中时再 retain 一份 owner 交给调用者，因此调用者可以把它作为下一次
 * 原子发布的候选，而不借用 store 内部引用。</p>
 *
 * <p>本类只管理完整复合代，不接受尚未收敛的 worker/staging candidate。exact identity
 * 必须包含 dimension 等布局域信息，缓存键还会比较全部 revision；任何一侧不匹配都不是
 * 命中。GPU 有效性由生产渲染层提供谓词，失效项会立即离开缓存。</p>
 *
 * <p>{@link #publish(LightGeneration)} 的静态校验失败时所有权仍属于调用者；校验通过后，
 * 调用一进入交换阶段所有权就转移给 store。即使随后淘汰旧项或关闭旧 active 抛出异常，
 * 新 active 仍由 store 持有，异常也会原样上抛。GPU 线程约束继续由 {@link GpuMeshSet}
 * 断言，本类不会把越线程失败伪装成成功。</p>
 */
final class CompositeGenerationStore<T> implements AutoCloseable {
    private final WeightedLruCache<CacheKey, LightGeneration<T>> cache;
    private final List<LightGeneration<T>> deferredClosures = new ArrayList<>();
    private final Set<LightGeneration<T>> deferredIdentities =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private LightGeneration<T> active;

    CompositeGenerationStore(long maxCachedGenerations, long maxCachedBytes) {
        cache = new WeightedLruCache<>(
                maxCachedGenerations,
                maxCachedBytes,
                this::closeOwnedOrDefer
        );
    }

    /**
     * 发布一个已经收敛的复合代，并为缓存建立独立 owner。
     *
     * <p>旧 active 只有在新引用已成为 active 后才关闭；缓存建立或旧资源关闭失败不会让
     * store 回到“没有 active”的半提交状态。缓存超预算时，新缓存 owner 可以立即被淘汰，
     * 但新 active 仍然有效。</p>
     */
    void publish(LightGeneration<T> generation) {
        Objects.requireNonNull(generation, "generation");
        Object exactIdentity = generation.exactIdentity();
        validateIdentity(exactIdentity);
        LightGeneration.RevisionKey revisions =
                Objects.requireNonNull(generation.revisions(), "revisions");
        if (!generation.converged()) {
            throw new IllegalStateException("未收敛 generation 不能发布到复合缓存");
        }
        if (active == generation) {
            throw new IllegalArgumentException("同一个 generation owner 不能重复发布");
        }
        long weight = generation.cacheWeightBytes();

        // 从这里开始，调用者不得再关闭 generation；即使后续步骤失败，store 仍持有它。
        LightGeneration<T> previousActive = active;
        active = generation;

        /*
         * 延迟释放可能再次失败，但不能发生在接管新 owner 之前。否则调用方无法区分
         * “store 尚未接管”和“store 已经接管但清理失败”，生产发布路径就只能泄漏或误关二选一。
         */
        RuntimeException failure = retryDeferredClosures();
        try {
            LightGeneration<T> cacheOwner = generation.retainForCache();
            cache.put(new CacheKey(exactIdentity, revisions), cacheOwner, weight);
        } catch (RuntimeException currentFailure) {
            failure = merge(failure, currentFailure);
        }
        if (previousActive != null) {
            try {
                closeOwnedOrDefer(previousActive);
            } catch (RuntimeException currentFailure) {
                failure = merge(failure, currentFailure);
            }
        }
        rethrow(failure);
    }

    /**
     * 在 CPU identity、全部 revision 与 GPU 有效性同时命中时返回新的独立 owner。
     *
     * <p>返回 {@code null} 表示必须重建；store 不会暴露内部 cache owner。GPU 失效会先
     * 淘汰该加速项再返回 miss，淘汰释放失败则直接抛出，不能让调用者误以为清理成功。</p>
     */
    LightGeneration<T> retainExact(
            Object exactIdentity,
            LightGeneration.RevisionKey revisions,
            Predicate<? super T> gpuValidity
    ) {
        validateIdentity(exactIdentity);
        Objects.requireNonNull(revisions, "revisions");
        Objects.requireNonNull(gpuValidity, "gpuValidity");

        CacheKey key = new CacheKey(exactIdentity, revisions);
        LightGeneration<T> cached = cache.get(key);
        if (cached == null) {
            return null;
        }
        // 即使键实现将来变化，也在所有权边界再次校验完整复合身份。
        if (!cached.matches(exactIdentity, revisions)
                || !cached.allGpuMeshesMatch(gpuValidity)) {
            cache.remove(key);
            return null;
        }
        return cached.retainForCache();
    }

    /** 返回 active 的新 owner；不存在 active 时返回 {@code null}。 */
    LightGeneration<T> retainActive() {
        return active == null ? null : active.retainForCache();
    }

    long activeGenerationId() {
        return active == null ? -1L : active.generationId();
    }

    int activeGpuMeshCount() {
        return active == null ? 0 : active.gpuMeshCount();
    }

    boolean allActiveGpuMeshesMatch(Predicate<? super T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return active == null || active.allGpuMeshesMatch(predicate);
    }

    void forEachActiveGpuMesh(BiConsumer<Long, ? super T> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (active != null) {
            active.forEachGpuMesh(consumer);
        }
    }

    /** 仅释放 active owner；同代 cache owner 仍可保留供以后精确命中。 */
    void discardActive() {
        LightGeneration<T> removed = active;
        active = null;
        if (removed != null) {
            closeOwnedOrDefer(removed);
        }
    }

    /** 按完整 generation 条件淘汰加速项，不影响当前 active owner。 */
    void removeCachedIf(Predicate<? super LightGeneration<T>> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        cache.removeValuesIf(predicate::test);
    }

    int cachedGenerationCount() {
        return cache.size();
    }

    long cachedWeightBytes() {
        return cache.totalWeight();
    }

    /**
     * 清空缓存与 active；重复调用幂等。
     *
     * <p>上一次因 Render-thread 断言失败而没有转移完成的 owner 会在本次最先重试。释放
     * 过程中仍会尝试其余 owner，并把后续异常作为 suppressed 附加到首个异常。</p>
     */
    void clear() {
        RuntimeException failure = retryDeferredClosures();
        try {
            cache.clear();
        } catch (RuntimeException currentFailure) {
            failure = merge(failure, currentFailure);
        }

        LightGeneration<T> removedActive = active;
        active = null;
        if (removedActive != null) {
            try {
                closeOwnedOrDefer(removedActive);
            } catch (RuntimeException currentFailure) {
                failure = merge(failure, currentFailure);
            }
        }
        rethrow(failure);
    }

    @Override
    public void close() {
        clear();
    }

    private void closeOwnedOrDefer(LightGeneration<T> generation) {
        try {
            generation.close();
        } catch (RuntimeException failure) {
            if (ownerStillOpen(generation) && deferredIdentities.add(generation)) {
                deferredClosures.add(generation);
            }
            throw failure;
        }
    }

    private RuntimeException retryDeferredClosures() {
        if (deferredClosures.isEmpty()) {
            return null;
        }
        List<LightGeneration<T>> retrying = new ArrayList<>(deferredClosures);
        deferredClosures.clear();
        deferredIdentities.clear();

        RuntimeException failure = null;
        for (LightGeneration<T> generation : retrying) {
            try {
                closeOwnedOrDefer(generation);
            } catch (RuntimeException currentFailure) {
                failure = merge(failure, currentFailure);
            }
        }
        return failure;
    }

    private static boolean ownerStillOpen(LightGeneration<?> generation) {
        try {
            generation.generationId();
            return true;
        } catch (IllegalStateException closed) {
            return false;
        }
    }

    private static void validateIdentity(Object exactIdentity) {
        Objects.requireNonNull(exactIdentity, "exactIdentity");
        if (exactIdentity instanceof CharSequence text && text.toString().isBlank()) {
            throw new IllegalArgumentException("exact identity 不能为空");
        }
    }

    private static RuntimeException merge(RuntimeException first, RuntimeException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static void rethrow(RuntimeException failure) {
        if (failure != null) {
            throw failure;
        }
    }

    private record CacheKey(Object exactIdentity, LightGeneration.RevisionKey revisions) {
        private CacheKey {
            Objects.requireNonNull(exactIdentity, "exactIdentity");
            Objects.requireNonNull(revisions, "revisions");
        }
    }
}
