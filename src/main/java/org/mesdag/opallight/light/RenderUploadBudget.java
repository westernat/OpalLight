package org.mesdag.opallight.light;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/// 在 Render thread 上执行有界的一段 staging 工作。
///
/// 预算只限制本次调用，不限制最终任务数量。每次调用至少处理一项，避免单项成本已经超过
/// 预算时永久无进展；完成的资源在外部 staging 中保持不可见，直到最后一次原子提交。
final class RenderUploadBudget {
    record Slice(int processedItems, boolean complete, long elapsedNanos) {
    }

    private final long budgetNanos;
    private final LongSupplier clock;

    RenderUploadBudget(long budgetNanos, LongSupplier clock) {
        if (budgetNanos <= 0L) {
            throw new IllegalArgumentException("budgetNanos must be greater than zero");
        }
        this.budgetNanos = budgetNanos;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Slice run(BooleanSupplier hasNext, Runnable processNext) {
        Objects.requireNonNull(hasNext, "hasNext");
        Objects.requireNonNull(processNext, "processNext");
        long started = clock.getAsLong();
        int processed = 0;
        while (hasNext.getAsBoolean()) {
            if (processed != 0 && elapsed(started) >= budgetNanos) {
                break;
            }
            processNext.run();
            processed++;
        }
        return new Slice(processed, !hasNext.getAsBoolean(), elapsed(started));
    }

    private long elapsed(long started) {
        long now = clock.getAsLong();
        return now >= started ? now - started : Long.MAX_VALUE;
    }
}
