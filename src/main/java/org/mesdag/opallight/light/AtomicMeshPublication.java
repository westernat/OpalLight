package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 管理一套可见 active 资源与至多一套不可见 staging 资源。
 *
 * <p>staging 在 commit 之前完全不可见；commit 只交换 map 引用并把旧 active 作为
 * {@link OwnedState} 转交给调用方。调用方可以把旧状态放入 generation cache，也可以立即
 * 关闭。构建、上传或取消路径只要关闭 stage，就只会释放 staging，不会修改 active。</p>
 *
 * <p>本类型不理解 OpenGL，但所有读取、写入、提交和释放都会执行调用方注入的线程断言。
 * 生产环境绑定 Render thread，单元测试用可控断言验证越线程失败不会遗失资源所有权。</p>
 */
final class AtomicMeshPublication<T> implements AutoCloseable {
    private final Consumer<? super T> disposer;
    private final Runnable threadAssertion;
    private Long2ObjectOpenHashMap<Entry<T>> active = new Long2ObjectOpenHashMap<>();
    private long activeGenerationId;
    private long activeBytes;
    private Stage staging;
    private boolean closed;

    AtomicMeshPublication(Consumer<? super T> disposer, Runnable threadAssertion) {
        this.disposer = Objects.requireNonNull(disposer, "disposer");
        this.threadAssertion = Objects.requireNonNull(threadAssertion, "threadAssertion");
    }

    T active(long sectionKey) {
        requireOpen();
        Entry<T> entry = active.get(sectionKey);
        return entry == null ? null : entry.resource;
    }

    int activeSize() {
        requireOpen();
        return active.size();
    }

    long activeBytes() {
        requireOpen();
        return activeBytes;
    }

    long activeGenerationId() {
        requireOpen();
        return activeGenerationId;
    }

    boolean allActiveMatch(Predicate<? super T> predicate) {
        requireOpen();
        Objects.requireNonNull(predicate, "predicate");
        return active.values().stream().allMatch(entry -> predicate.test(entry.resource));
    }

    void forEachActive(BiConsumer<Long, ? super T> consumer) {
        requireOpen();
        Objects.requireNonNull(consumer, "consumer");
        active.forEach((key, entry) -> consumer.accept(key, entry.resource));
    }

    long[] activeSectionKeys() {
        requireOpen();
        return active.keySet().toLongArray();
    }

    /** 兼容小更新的原地路径；完整批量发布必须使用 {@link Stage}。 */
    void putActiveForLegacy(long generationId, long sectionKey, T resource, long bytes) {
        requireOpen();
        if (generationId < 0L) {
            throw new IllegalArgumentException("generation id 不能为负数");
        }
        requireBytes(bytes);
        Entry<T> next = new Entry<>(Objects.requireNonNull(resource, "resource"), bytes);
        Entry<T> previous = active.put(sectionKey, next);
        if (previous != null) {
            activeBytes -= previous.bytes;
        }
        activeBytes = saturatedAdd(activeBytes, bytes);
        activeGenerationId = generationId;
        // 先完成新 active 的全部不变量，再释放旧资源；即使 disposer 失败，map/字节/代号仍一致。
        if (previous != null) {
            disposer.accept(previous.resource);
        }
    }

    T removeActiveForLegacy(long sectionKey) {
        requireOpen();
        Entry<T> removed = active.remove(sectionKey);
        if (removed == null) {
            return null;
        }
        activeBytes -= removed.bytes;
        return removed.resource;
    }

    Stage begin(long generationId) {
        requireOpen();
        if (generationId < 0L) {
            throw new IllegalArgumentException("generation id 不能为负数");
        }
        if (staging != null) {
            throw new IllegalStateException("同一时刻只能存在一个 staging generation");
        }
        staging = new Stage(generationId);
        return staging;
    }

    /** 原子采用一个已缓存的完整状态，并把旧 active 所有权返回给调用方。 */
    OwnedState<T> adopt(OwnedState<T> state) {
        requireOpen();
        Objects.requireNonNull(state, "state");
        if (staging != null) {
            throw new IllegalStateException("存在 staging 时不能采用缓存状态");
        }
        StatePayload<T> adopted = state.takeForAdoption(threadAssertion);
        OwnedState<T> previous = transferActive();
        active = adopted.resources;
        activeBytes = adopted.bytes;
        activeGenerationId = adopted.generationId;
        return previous;
    }

    /** 取消 staging、释放 active，但保留 publication 对象供世界切换后的下一代复用。 */
    void reset() {
        requireOpen();
        RuntimeException failure = null;
        if (staging != null) {
            try {
                staging.close();
            } catch (RuntimeException current) {
                failure = current;
            }
        }
        OwnedState<T> previous = transferActive();
        try {
            previous.close();
        } catch (RuntimeException current) {
            if (failure == null) {
                failure = current;
            } else {
                failure.addSuppressed(current);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private OwnedState<T> transferActive() {
        OwnedState<T> previous = new OwnedState<>(
                activeGenerationId, active, activeBytes, disposer, threadAssertion
        );
        active = new Long2ObjectOpenHashMap<>();
        activeBytes = 0L;
        activeGenerationId = 0L;
        return previous;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        threadAssertion.run();
        RuntimeException failure = null;
        if (staging != null) {
            try {
                staging.close();
            } catch (RuntimeException current) {
                failure = current;
            }
        }
        OwnedState<T> previous = transferActive();
        try {
            previous.close();
        } catch (RuntimeException current) {
            if (failure == null) {
                failure = current;
            } else {
                failure.addSuppressed(current);
            }
        }
        closed = true;
        if (failure != null) {
            throw failure;
        }
    }

    private void requireOpen() {
        threadAssertion.run();
        if (closed) {
            throw new IllegalStateException("mesh publication 已经关闭");
        }
    }

    private static void requireBytes(long bytes) {
        if (bytes < 0L) {
            throw new IllegalArgumentException("GPU 字节数不能为负数");
        }
    }

    private static long saturatedAdd(long current, long added) {
        return added > Long.MAX_VALUE - current ? Long.MAX_VALUE : current + added;
    }

    /** 一套已经脱离 active、由 cache 或调用方独占的完整资源。 */
    static final class OwnedState<T> implements AutoCloseable {
        private final Consumer<? super T> disposer;
        private final Runnable threadAssertion;
        private Long2ObjectOpenHashMap<Entry<T>> resources;
        private long generationId;
        private long bytes;
        private boolean closed;

        private OwnedState(
                long generationId,
                Long2ObjectOpenHashMap<Entry<T>> resources,
                long bytes,
                Consumer<? super T> disposer,
                Runnable threadAssertion
        ) {
            this.generationId = generationId;
            this.resources = resources;
            this.bytes = bytes;
            this.disposer = disposer;
            this.threadAssertion = threadAssertion;
        }

        int size() {
            requireOwned();
            return resources.size();
        }

        long bytes() {
            requireOwned();
            return bytes;
        }

        long[] sectionKeys() {
            requireOwned();
            return resources.keySet().toLongArray();
        }

        boolean allMatch(Predicate<? super T> predicate) {
            requireOwned();
            Objects.requireNonNull(predicate, "predicate");
            return resources.values().stream().allMatch(entry -> predicate.test(entry.resource));
        }

        private StatePayload<T> takeForAdoption(Runnable expectedThreadAssertion) {
            requireOwned();
            if (threadAssertion != expectedThreadAssertion) {
                throw new IllegalArgumentException("资源状态属于不同的 Render-thread owner");
            }
            StatePayload<T> payload = new StatePayload<>(generationId, resources, bytes);
            resources = new Long2ObjectOpenHashMap<>();
            bytes = 0L;
            closed = true;
            return payload;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            threadAssertion.run();
            closed = true;
            closeEntries(resources, disposer);
            resources.clear();
            bytes = 0L;
        }

        private void requireOwned() {
            threadAssertion.run();
            if (closed) {
                throw new IllegalStateException("资源状态已经转移或关闭");
            }
        }
    }

    /** 一次不可见构建；close 表示取消，commit 表示把完整 map 一次交换为 active。 */
    final class Stage implements AutoCloseable {
        private final long generationId;
        private Long2ObjectOpenHashMap<Entry<T>> resources = new Long2ObjectOpenHashMap<>();
        private long bytes;
        private boolean finished;

        private Stage(long generationId) {
            this.generationId = generationId;
        }

        void put(long sectionKey, T resource, long resourceBytes) {
            requireUsable();
            requireBytes(resourceBytes);
            Entry<T> next = new Entry<>(Objects.requireNonNull(resource, "resource"), resourceBytes);
            Entry<T> previous = resources.put(sectionKey, next);
            if (previous != null) {
                bytes -= previous.bytes;
                disposer.accept(previous.resource);
            }
            bytes = saturatedAdd(bytes, resourceBytes);
        }

        OwnedState<T> commit() {
            requireUsable();
            OwnedState<T> previous = transferActive();
            active = resources;
            activeBytes = bytes;
            activeGenerationId = generationId;
            resources = new Long2ObjectOpenHashMap<>();
            bytes = 0L;
            finished = true;
            staging = null;
            return previous;
        }

        @Override
        public void close() {
            if (finished) {
                return;
            }
            threadAssertion.run();
            finished = true;
            staging = null;
            closeEntries(resources, disposer);
            resources.clear();
            bytes = 0L;
        }

        private void requireUsable() {
            requireOpen();
            if (finished || staging != this) {
                throw new IllegalStateException("staging generation 已经提交或取消");
            }
        }
    }

    private static <T> void closeEntries(
            Long2ObjectOpenHashMap<Entry<T>> entries,
            Consumer<? super T> disposer
    ) {
        long[] keys = entries.keySet().toLongArray();
        Arrays.sort(keys);
        RuntimeException failure = null;
        for (long key : keys) {
            try {
                disposer.accept(entries.get(key).resource);
            } catch (RuntimeException current) {
                if (failure == null) {
                    failure = current;
                } else {
                    failure.addSuppressed(current);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private record Entry<T>(T resource, long bytes) {
    }

    private record StatePayload<T>(
            long generationId,
            Long2ObjectOpenHashMap<Entry<T>> resources,
            long bytes
    ) {
    }
}
