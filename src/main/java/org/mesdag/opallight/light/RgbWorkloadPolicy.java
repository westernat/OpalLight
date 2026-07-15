package org.mesdag.opallight.light;

/// 只决定 RGB 计算放在 owner 线程还是专用 worker，不限制光源数量或传播结果。
final class RgbWorkloadPolicy {
    private static final long ASYNC_WORK_THRESHOLD = 200_000L;
    /// 15 级光源在无遮挡三维曼哈顿空间中最多检查 4991 个位置。
    /// 这里必须按传播体积估算，不能只按方块回调次数估算，否则稀疏光源会绕过分帧保护。
    private static final long MAX_PROPAGATION_VISITS_PER_CHANGE = 4_991L;

    private RgbWorkloadPolicy() {}

    static boolean shouldOffload(
            int changedBlocks,
            int allocatedLightSections,
            boolean touchesExistingLight
    ) {
        if (changedBlocks <= 0) {
            return false;
        }
        long changedFieldWork = (long) changedBlocks * MAX_PROPAGATION_VISITS_PER_CHANGE;
        // 放置在暗处的单个新光源仍走即时同步路径；移除光源或在既有大光场中放置遮挡物时，
        // 一次变化可能触发覆盖整个已分配光场的 decrease，不能再用“变化数很少”误判为小任务。
        long existingFieldWork = touchesExistingLight
                ? (long) allocatedLightSections * 4_096L
                : 0L;
        return changedFieldWork + existingFieldWork >= ASYNC_WORK_THRESHOLD;
    }

    static boolean shouldSliceQueuedPropagation(int queuedPositions) {
        return (long) queuedPositions * MAX_PROPAGATION_VISITS_PER_CHANGE >= ASYNC_WORK_THRESHOLD;
    }

    static boolean shouldSliceLifecycleWork(boolean lifecycleChanged, int queuedPositions) {
        return lifecycleChanged && queuedPositions > 0;
    }
}
