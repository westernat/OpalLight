package org.mesdag.opallight.light;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

/**
 * 有界的单 worker generation 协调器。
 *
 * <p>任何时刻最多存在一个正在计算的任务、一个等待任务和一个已完成结果。新的提交不会
 * 堆积无上限队列，而是替换尚未开始的旧任务。这里限制的是可丢弃、可重算的加速任务，
 * 不限制光源注册表、世界快照覆盖范围或最终正确结果的大小。</p>
 *
 * <p>所有结果都携带调用方提供的 revision key。owner 轮询时若 key 已变化，候选结果会被
 * 立即销毁，绝不会进入 staging。worker 使用 Mod 私有线程，不占用 JVM common pool。</p>
 */
final class GenerationCoordinator<K, I, O> implements AutoCloseable {
    @FunctionalInterface
    interface CancellableBuilder<I, O> {
        O build(I input, CancellationToken cancellation) throws Exception;
    }

    @FunctionalInterface
    interface CancellationToken {
        boolean isCancelled();
    }

    record Completed<K, O>(K key, O value) {
    }

    private static final class Task<K, I> {
        private final long sequence;
        private final K key;
        private final I input;
        private volatile boolean cancelled;

        private Task(long sequence, K key, I input) {
            this.sequence = sequence;
            this.key = key;
            this.input = input;
        }

        long sequence() {
            return sequence;
        }

        K key() {
            return key;
        }

        I input() {
            return input;
        }

        void cancel() {
            cancelled = true;
        }

        boolean isCancelled() {
            return cancelled;
        }
    }

    private final ExecutorService executor;
    private final CancellableBuilder<I, O> builder;
    private final @Nullable Consumer<O> resultDisposer;

    private Task<K, I> running;
    private Task<K, I> pending;
    private SequencedCompleted<K, O> completed;
    private Throwable failure;
    private long failureSequence = -1L;
    private long nextSequence;
    private long latestSequence;
    private boolean closed;

    GenerationCoordinator(
            String threadName,
            CancellableBuilder<I, O> builder
    ) {
        this(threadName, builder, null);
    }

    GenerationCoordinator(
            String threadName,
            CancellableBuilder<I, O> builder,
            @Nullable Consumer<O> resultDisposer
    ) {
        Objects.requireNonNull(threadName, "threadName");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.resultDisposer = resultDisposer;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
         };
        executor = Executors.newSingleThreadExecutor(threadFactory);
    }

    void submit(K key, I input) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(input, "input");
        SequencedCompleted<K, O> discardedResult;
        Task<K, I> task;
        boolean start;
        synchronized (this) {
            ensureOpen();
            long sequence = ++nextSequence;
            latestSequence = sequence;
            clearFailure();
            discardedResult = detachCompleted();
            task = new Task<>(sequence, key, input);
            start = running == null;
            if (start) {
                running = task;
            } else {
                cancelRunning();
                pending = task;
            }
        }
        scheduleResultDisposal(discardedResult);
        if (start) {
            execute(task);
        }
    }

    /**
     * 仅返回仍与 owner 当前 revision 完全一致的最新结果。
     */
    Optional<Completed<K, O>> poll(K currentKey) {
        Objects.requireNonNull(currentKey, "currentKey");
        SequencedCompleted<K, O> candidate;
        synchronized (this) {
            if (completed == null) {
                return Optional.empty();
            }
            candidate = completed;
            completed = null;
            if (candidate.sequence == latestSequence && candidate.completed.key().equals(currentKey)) {
                return Optional.of(candidate.completed);
            }
        }
        scheduleResultDisposal(candidate);
        return Optional.empty();
    }

    /**
     * 使所有已提交 revision 过期；运行中的任务允许自然结束，但其结果只能被销毁。
     */
    void invalidate() {
        SequencedCompleted<K, O> discardedResult;
        synchronized (this) {
            latestSequence = ++nextSequence;
            clearFailure();
            cancelRunning();
            pending = null;
            discardedResult = detachCompleted();
        }
        scheduleResultDisposal(discardedResult);
    }

    synchronized boolean hasWork() {
        return running != null || pending != null || completed != null;
    }

    synchronized Optional<Throwable> takeFailure() {
        Throwable result = failureSequence == latestSequence ? failure : null;
        clearFailure();
        return Optional.ofNullable(result);
    }

    @Override
    public void close() {
        SequencedCompleted<K, O> discardedResult;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            latestSequence = ++nextSequence;
            clearFailure();
            cancelRunning();
            pending = null;
            discardedResult = detachCompleted();
        }
        scheduleResultDisposal(discardedResult);
        // running 任务通过 cancellation token 自行退出；保留队列中的资源清理，不用 shutdownNow 丢弃 owner。
        executor.shutdown();
    }

    private void execute(Task<K, I> task) {
        executor.execute(() -> build(task));
    }

    private void build(Task<K, I> task) {
        O result = null;
        Throwable buildFailure = null;
        try {
            result = builder.build(task.input(), task::isCancelled);
        } catch (CancellationException cancelled) {
            buildFailure = cancelled;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            buildFailure = interrupted;
        } catch (Throwable throwable) {
            // 方块/模型扩展也可能抛出 Error；无论何种失败都必须进入统一收尾，不能把 running 永久卡住。
            buildFailure = throwable;
        }
        SequencedCompleted<K, O> discardedCompleted = null;
        O discardedResult = null;
        long discardedResultSequence = task.sequence();
        Task<K, I> next = null;
        boolean disposeInline = false;
        synchronized (this) {
            running = null;
            if (buildFailure != null) {
                if (!(buildFailure instanceof CancellationException)
                        && !closed && task.sequence() == latestSequence) {
                    recordFailure(task.sequence(), buildFailure);
                }
            } else if (result != null) {
                if (closed || task.sequence() != latestSequence) {
                    discardedResult = result;
                    disposeInline = closed;
                } else {
                    discardedCompleted = detachCompleted();
                    completed = new SequencedCompleted<>(
                            task.sequence(),
                            new Completed<>(task.key(), result)
                    );
                }
            }

            if (!closed && pending != null) {
                next = pending;
                pending = null;
                running = next;
            }
        }
        scheduleResultDisposal(discardedCompleted);
        if (discardedResult != null) {
            if (disposeInline) {
                disposeResult(discardedResultSequence, discardedResult);
            } else {
                scheduleResultDisposal(discardedResultSequence, discardedResult);
            }
        }
        if (next != null) {
            execute(next);
        }
        if (buildFailure instanceof Error fatal && !(fatal instanceof AssertionError)) {
            throw fatal;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("GenerationCoordinator is closed");
        }
    }

    /** 只在锁内摘除 owner；潜在昂贵的 native 资源释放始终在协调器 worker 上执行。 */
    private SequencedCompleted<K, O> detachCompleted() {
        SequencedCompleted<K, O> result = completed;
        completed = null;
        return result;
    }

    private void scheduleResultDisposal(SequencedCompleted<K, O> result) {
        if (result != null) {
            scheduleResultDisposal(result.sequence(), result.completed().value());
        }
    }

    private void scheduleResultDisposal(long sequence, O result) {
        if (resultDisposer == null) {
            return;
        }
        scheduleCleanup(() -> disposeResult(sequence, result));
    }

    private void scheduleCleanup(Runnable cleanup) {
        try {
            executor.execute(cleanup);
        } catch (RejectedExecutionException closedRace) {
            // submit/invalidate 与 close 极端并发时，owner 已从状态摘除但 executor 可能刚关闭；
            // 此时同步收尾优先于泄漏，正常 Render-thread 生命周期不会进入该竞争分支。
            cleanup.run();
        }
    }

    private void disposeResult(long sequence, O result) {
        Consumer<O> disposer = resultDisposer;
        if (disposer == null) {
            return;
        }
        try {
            disposer.accept(result);
        } catch (RuntimeException exception) {
            synchronized (this) {
                recordFailure(sequence, exception);
            }
        }
    }

    /** 使正在运行的任务尽快在协作取消点退出。 */
    private void cancelRunning() {
        if (running != null) {
            running.cancel();
        }
    }

    private void recordFailure(long sequence, Throwable throwable) {
        if (closed || sequence != latestSequence) {
            return;
        }
        if (failure == null || failureSequence != sequence) {
            failure = throwable;
            failureSequence = sequence;
        } else if (failure != throwable) {
            failure.addSuppressed(throwable);
        }
    }

    private void clearFailure() {
        failure = null;
        failureSequence = -1L;
    }

    private record SequencedCompleted<K, O>(long sequence, Completed<K, O> completed) {
    }
}
