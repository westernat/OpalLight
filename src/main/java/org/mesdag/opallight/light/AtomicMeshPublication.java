package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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
    private @Nullable Stage staging;
    private boolean closed;

    AtomicMeshPublication(Consumer<? super T> disposer, Runnable threadAssertion) {
        this.disposer = Objects.requireNonNull(disposer, "disposer");
        this.threadAssertion = Objects.requireNonNull(threadAssertion, "threadAssertion");
    }

    @Nullable T active(long sectionKey) {
        requireOpen();
        Entry<T> entry = active.get(sectionKey);
        return entry == null ? null : entry.resource;
    }

    int activeSize() {
        requireOpen();
        return active.size();
    }

    long activeGenerationId() {
        requireOpen();
        return activeGenerationId;
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

    @Nullable T removeActiveForLegacy(long sectionKey) {
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
            throw new IllegalArgumentException("generationId must not be negative");
        }
        if (staging != null) {
            throw new IllegalStateException("Only one staging generation may exist at a time");
        }
        staging = new Stage(generationId);
        return staging;
    }

    /** 原子采用一个已缓存的完整状态，并把旧 active 所有权返回给调用方。 */
    OwnedState<T> adopt(OwnedState<T> state) {
        requireOpen();
        Objects.requireNonNull(state, "state");
        if (staging != null) {
            throw new IllegalStateException("Cached state cannot be adopted while a staging generation exists");
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
        Throwable failure = null;
        if (staging != null) {
            try {
                staging.close();
            } catch (RuntimeException | Error current) {
                failure = current;
            }
        }
        OwnedState<T> previous = transferActive();
        try {
            previous.close();
        } catch (RuntimeException | Error current) {
            if (failure == null) {
                failure = current;
            } else {
                failure.addSuppressed(current);
            }
        }
        rethrow(failure);
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
        Throwable failure = null;
        if (staging != null) {
            try {
                staging.close();
            } catch (RuntimeException | Error current) {
                failure = current;
            }
        }
        OwnedState<T> previous = transferActive();
        try {
            previous.close();
        } catch (RuntimeException | Error current) {
            if (failure == null) {
                failure = current;
            } else {
                failure.addSuppressed(current);
            }
        }
        closed = true;
        rethrow(failure);
    }

    private void requireOpen() {
        threadAssertion.run();
        if (closed) {
            throw new IllegalStateException("Mesh publication is already closed");
        }
    }

    private static void requireBytes(long bytes) {
        if (bytes < 0L) {
            throw new IllegalArgumentException("GPU byte count must not be negative");
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

        private StatePayload<T> takeForAdoption(Runnable expectedThreadAssertion) {
            requireOwned();
            if (threadAssertion != expectedThreadAssertion) {
                throw new IllegalArgumentException("The resource state belongs to a different render-thread owner");
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
                throw new IllegalStateException("The resource state has already been transferred or closed");
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
                throw new IllegalStateException("The staging generation has already been committed or cancelled");
            }
        }
    }

    private static <T> void closeEntries(
            Long2ObjectOpenHashMap<Entry<T>> entries,
            Consumer<? super T> disposer
    ) {
        Throwable failure = null;
        var iterator = entries.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            T resource = iterator.next().getValue().resource;
            iterator.remove();
            try {
                disposer.accept(resource);
            } catch (RuntimeException | Error current) {
                if (failure == null) {
                    failure = current;
                } else {
                    failure.addSuppressed(current);
                }
            }
        }
        rethrow(failure);
    }

    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new AssertionError("GPU resource disposal threw an undeclared checked exception", failure);
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
