package org.mesdag.opallight.light;

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
    interface Builder<I, O> {
        O build(I input) throws Exception;
    }

    @FunctionalInterface
    interface CancellableBuilder<I, O> {
        O build(I input, CancellationToken cancellation) throws Exception;
    }

    @FunctionalInterface
    interface CancellationToken {
        boolean isCancelled();
    }

    enum SubmitState {
        STARTED,
        QUEUED,
        REPLACED
    }

    record Completed<K, O>(K key, O value, long workerNanos) {
    }

    record State(
            boolean running,
            int pendingTasks,
            int completedTasks,
            long submittedTasks,
            long discardedTasks,
            long cancelledTasks
    ) {
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

        boolean cancel() {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            return true;
        }

        boolean isCancelled() {
            return cancelled;
        }
    }

    private final ExecutorService executor;
    private final CancellableBuilder<I, O> builder;
    private final Consumer<I> inputDisposer;
    private final Consumer<O> resultDisposer;

    private Task<K, I> running;
    private Task<K, I> pending;
    private SequencedCompleted<K, O> completed;
    private Throwable failure;
    private long failureSequence = -1L;
    private long nextSequence;
    private long latestSequence;
    private long submittedTasks;
    private long discardedTasks;
    private long cancelledTasks;
    private boolean closed;

    GenerationCoordinator(
            String threadName,
            Builder<I, O> builder,
            Consumer<I> inputDisposer,
            Consumer<O> resultDisposer
    ) {
        this(threadName, adapt(builder), inputDisposer, resultDisposer);
    }

    GenerationCoordinator(
            String threadName,
            CancellableBuilder<I, O> builder,
            Consumer<I> inputDisposer,
            Consumer<O> resultDisposer
    ) {
        Objects.requireNonNull(threadName, "threadName");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.inputDisposer = Objects.requireNonNull(inputDisposer, "inputDisposer");
        this.resultDisposer = Objects.requireNonNull(resultDisposer, "resultDisposer");
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newSingleThreadExecutor(threadFactory);
    }

    SubmitState submit(K key, I input) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(input, "input");
        Task<K, I> discardedInput = null;
        SequencedCompleted<K, O> discardedResult;
        Task<K, I> task;
        SubmitState state;
        boolean start;
        synchronized (this) {
            ensureOpen();
            long sequence = ++nextSequence;
            latestSequence = sequence;
            clearFailure();
            submittedTasks++;
            discardedResult = detachCompleted();
            task = new Task<>(sequence, key, input);
            start = running == null;
            if (start) {
                running = task;
                state = SubmitState.STARTED;
            } else {
                cancelRunning();
                state = pending == null ? SubmitState.QUEUED : SubmitState.REPLACED;
                discardedInput = pending;
                pending = task;
            }
        }
        scheduleResultDisposal(discardedResult);
        scheduleInputDisposal(discardedInput);
        if (start) {
            execute(task);
        }
        return state;
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
            discardedTasks++;
        }
        scheduleResultDisposal(candidate);
        return Optional.empty();
    }

    /**
     * 使所有已提交 revision 过期；运行中的任务允许自然结束，但其结果只能被销毁。
     */
    void invalidate() {
        Task<K, I> discardedInput;
        SequencedCompleted<K, O> discardedResult;
        synchronized (this) {
            latestSequence = ++nextSequence;
            clearFailure();
            cancelRunning();
            discardedInput = pending;
            pending = null;
            discardedResult = detachCompleted();
            if (discardedInput != null) {
                discardedTasks++;
            }
        }
        scheduleInputDisposal(discardedInput, false);
        scheduleResultDisposal(discardedResult);
    }

    synchronized State state() {
        return new State(
                running != null,
                pending == null ? 0 : 1,
                completed == null ? 0 : 1,
                submittedTasks,
                discardedTasks,
                cancelledTasks
        );
    }

    synchronized Optional<Throwable> takeFailure() {
        Throwable result = failureSequence == latestSequence ? failure : null;
        clearFailure();
        return Optional.ofNullable(result);
    }

    @Override
    public void close() {
        Task<K, I> discardedInput;
        SequencedCompleted<K, O> discardedResult;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            latestSequence = ++nextSequence;
            clearFailure();
            cancelRunning();
            discardedInput = pending;
            pending = null;
            if (discardedInput != null) {
                discardedTasks++;
            }
            discardedResult = detachCompleted();
        }
        scheduleInputDisposal(discardedInput, false);
        scheduleResultDisposal(discardedResult);
        // running 任务通过 cancellation token 自行退出；保留队列中的资源清理，不用 shutdownNow 丢弃 owner。
        executor.shutdown();
    }

    private void execute(Task<K, I> task) {
        executor.execute(() -> build(task));
    }

    private void build(Task<K, I> task) {
        long started = System.nanoTime();
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
        long elapsed = System.nanoTime() - started;

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
                    discardedTasks++;
                    disposeInline = closed;
                } else {
                    discardedCompleted = detachCompleted();
                    completed = new SequencedCompleted<>(
                            task.sequence(),
                            new Completed<>(task.key(), result, elapsed)
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
            throw new IllegalStateException("GenerationCoordinator 已关闭");
        }
    }

    /** 只在锁内摘除 owner；潜在昂贵的 native 资源释放始终在协调器 worker 上执行。 */
    private SequencedCompleted<K, O> detachCompleted() {
        SequencedCompleted<K, O> result = completed;
        completed = null;
        if (result != null) {
            discardedTasks++;
        }
        return result;
    }

    private void scheduleInputDisposal(Task<K, I> task) {
        if (task != null) {
            synchronized (this) {
                discardedTasks++;
            }
            scheduleInputDisposal(task, true);
        }
    }

    private void scheduleInputDisposal(Task<K, I> task, boolean alreadyCounted) {
        if (task == null) {
            return;
        }
        if (!alreadyCounted) {
            // 该重载的现有调用方都在摘除 owner 的临界区内计数；保留参数让约束在调用点可见。
        }
        scheduleCleanup(() -> disposeInput(task.sequence(), task.input()));
    }

    private void disposeInput(long sequence, I input) {
        try {
            inputDisposer.accept(input);
        } catch (RuntimeException exception) {
            synchronized (this) {
                recordFailure(sequence, exception);
            }
        }
    }

    private void scheduleResultDisposal(SequencedCompleted<K, O> result) {
        if (result != null) {
            scheduleResultDisposal(result.sequence(), result.completed().value());
        }
    }

    private void scheduleResultDisposal(long sequence, O result) {
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
        try {
            resultDisposer.accept(result);
        } catch (RuntimeException exception) {
            synchronized (this) {
                recordFailure(sequence, exception);
            }
        }
    }

    /** 统计已发出的 running 协作取消请求；同一任务无论被重复失效多少次只计一次。 */
    private void cancelRunning() {
        if (running != null && running.cancel()) {
            cancelledTasks++;
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

    private static <I, O> CancellableBuilder<I, O> adapt(Builder<I, O> builder) {
        Objects.requireNonNull(builder, "builder");
        return (input, cancellation) -> builder.build(input);
    }

    private record SequencedCompleted<K, O>(long sequence, Completed<K, O> completed) {
    }
}
