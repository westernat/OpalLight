package org.mesdag.opallight.light;

import java.util.Objects;
import java.util.function.Consumer;

/// 在原子发布表与 generation cache 之间共享单个 GPU 资源的显式租约。
///
/// 两个容器不能直接共同拥有同一个 VBO，否则任意一侧替换或淘汰都会让另一侧持有
/// 已关闭的 OpenGL 对象。每个容器因此持有独立 [Lease]；最后一份租约关闭时才执行
/// 真正的资源释放。所有 acquire、读取和 close 都受 Render-thread 断言保护。
final class RetainedGpuResource<T> {
    private final T value;
    private final long bytes;
    private final Consumer<? super T> disposer;
    private final Runnable threadAssertion;
    private int owners;
    private boolean released;

    private RetainedGpuResource(
            T value,
            long bytes,
            Consumer<? super T> disposer,
            Runnable threadAssertion
    ) {
        if (bytes < 0L) {
            throw new IllegalArgumentException("GPU byte count must not be negative");
        }
        this.value = Objects.requireNonNull(value, "value");
        this.bytes = bytes;
        this.disposer = Objects.requireNonNull(disposer, "disposer");
        this.threadAssertion = Objects.requireNonNull(threadAssertion, "threadAssertion");
    }

    static <T> RetainedGpuResource<T>.Lease create(
            T value,
            long bytes,
            Consumer<? super T> disposer,
            Runnable threadAssertion
    ) {
        RetainedGpuResource<T> resource = new RetainedGpuResource<>(
                value, bytes, disposer, threadAssertion
        );
        return resource.acquire();
    }

    private Lease acquire() {
        threadAssertion.run();
        if (released) {
            throw new IllegalStateException("GPU resource has already been released");
        }
        owners++;
        return new Lease();
    }

    /** 一个容器独占的、可幂等关闭的资源所有权。 */
    final class Lease implements AutoCloseable {
        private boolean closed;

        Lease retain() {
            requireOpen();
            return acquire();
        }

        T value() {
            requireOpen();
            return value;
        }

        long bytes() {
            requireOpen();
            return bytes;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            threadAssertion.run();
            closed = true;
            owners--;
            if (owners == 0) {
                released = true;
                disposer.accept(value);
            }
        }

        private void requireOpen() {
            threadAssertion.run();
            if (closed || released) {
                throw new IllegalStateException("GPU resource lease is already closed");
            }
        }
    }
}
