package org.mesdag.opallight.light;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 一代完整 GPU 网格的只读引用计数所有者。
 *
 * <p>活动渲染状态、generation cache 和 staging 协调器只能各自持有 {@link Lease}，
 * 不能直接共享可变 VBO map。缓存淘汰只关闭自己的租约；最后一个租约释放时，集合内
 * 每个 GPU 资源才恰好关闭一次。这为后续“旧代继续绘制、新代不可见上传、完成后原子交换”
 * 提供明确的资源所有权。</p>
 *
 * <p>所有 acquire、读取、retain 和 release 都执行调用方提供的线程断言。生产接入时
 * 该断言必须绑定 Render thread；测试使用可注入断言验证越线程访问不会悄悄成功。</p>
 */
final class GpuMeshSet<T> {
    private final Map<Long, T> resources;
    private final long gpuBytes;
    private final Consumer<? super T> disposer;
    private final Runnable threadAssertion;
    private int leaseCount;
    private boolean released;

    GpuMeshSet(
            Map<Long, T> resources,
            long gpuBytes,
            Consumer<? super T> disposer,
            Runnable threadAssertion
    ) {
        if (gpuBytes < 0L) {
            throw new IllegalArgumentException("GPU 字节数不能为负数");
        }
        Objects.requireNonNull(resources, "resources");
        this.disposer = Objects.requireNonNull(disposer, "disposer");
        this.threadAssertion = Objects.requireNonNull(threadAssertion, "threadAssertion");
        this.gpuBytes = gpuBytes;

        // 排序后的防御性副本既不受构造参数后续修改影响，也让最终释放顺序可复现。
        List<Map.Entry<Long, T>> ordered = new ArrayList<>(resources.entrySet());
        ordered.sort(Comparator.comparingLong(Map.Entry::getKey));
        Map<Long, T> copied = new LinkedHashMap<>(ordered.size());
        for (Map.Entry<Long, T> entry : ordered) {
            copied.put(
                    Objects.requireNonNull(entry.getKey(), "sectionKey"),
                    Objects.requireNonNull(entry.getValue(), "resource")
            );
        }
        this.resources = copied;
    }

    Lease acquire() {
        assertThread();
        if (released) {
            throw new IllegalStateException("GPU 网格集合已经释放");
        }
        // 先分配 owner，再增加计数；即使极端 OOM，也不会留下永远无法释放的幽灵租约。
        Lease lease = new Lease();
        leaseCount++;
        return lease;
    }

    /**
     * 构造阶段尚未成功交给任何 {@link Lease} 时释放集合。该入口只服务 RAII 失败收尾，
     * 正常 active/cache owner 必须继续通过租约计数释放。
     */
    void discardUnleased() {
        assertThread();
        if (released) {
            return;
        }
        if (leaseCount != 0) {
            throw new IllegalStateException("仍有 GPU 网格租约，不能按未接管资源释放");
        }
        released = true;
        disposeResources();
    }

    private void release() {
        if (--leaseCount != 0) {
            return;
        }
        released = true;
        disposeResources();
    }

    private void disposeResources() {
        List<T> removed = new ArrayList<>(resources.values());
        resources.clear();

        RuntimeException failure = null;
        for (T resource : removed) {
            try {
                disposer.accept(resource);
            } catch (RuntimeException currentFailure) {
                if (failure == null) {
                    failure = currentFailure;
                } else {
                    failure.addSuppressed(currentFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void assertThread() {
        threadAssertion.run();
    }

    /** 对某个 owner 暴露的只读租约；close 幂等，不能在关闭后继续读取或 retain。 */
    final class Lease implements AutoCloseable {
        private boolean closed;

        Lease retain() {
            requireOpen();
            return GpuMeshSet.this.acquire();
        }

        T get(long sectionKey) {
            requireOpen();
            return resources.get(sectionKey);
        }

        int size() {
            requireOpen();
            return resources.size();
        }

        long gpuBytes() {
            requireOpen();
            return gpuBytes;
        }

        boolean allMatch(Predicate<? super T> predicate) {
            requireOpen();
            Objects.requireNonNull(predicate, "predicate");
            return resources.values().stream().allMatch(predicate);
        }

        void forEach(BiConsumer<Long, ? super T> consumer) {
            requireOpen();
            Objects.requireNonNull(consumer, "consumer");
            resources.forEach(consumer);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            assertThread();
            closed = true;
            release();
        }

        /**
         * 仅供外层 owner 在 close 异常后判断所有权是否已经转移完毕。
         *
         * <p>这里故意不执行线程断言：若断言本身就是本次失败原因，外层仍必须区分
         * “lease 尚可在 Render thread 重试”和“lease 已关闭但 disposer 报错”两种状态。</p>
         */
        boolean closedForOwner() {
            return closed;
        }

        private void requireOpen() {
            assertThread();
            if (closed) {
                throw new IllegalStateException("GPU 网格租约已经关闭");
            }
        }
    }
}
