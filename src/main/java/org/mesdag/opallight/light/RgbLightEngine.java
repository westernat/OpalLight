package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** 采用原版先衰减、后增强流程的增量三通道光照引擎。 */
final class RgbLightEngine {
    record ChunkSnapshot(RgbLightVolume.ChunkSnapshot light, RgbLightVolume.ChunkSnapshot directEmissions) {
        int sectionCount() {
            return light.sectionCount() + directEmissions.sectionCount();
        }

        /** CPU 网格快照读取冻结光场，不允许回到仍可变化的 owner 引擎。 */
        int lightAt(long position) {
            return light.get(position);
        }

        /** 已冻结的直接发光值用于排除光源方块自身的遮罩几何。 */
        int directEmissionAt(long position) {
            return directEmissions.get(position);
        }

        void collectLightSectionKeys(LongSet output) {
            light.forEachSectionKey(output::add);
        }
    }

    /**
     * 提交给 worker 的只读输入。base 使用冻结 section 视图，变化位置数组由构造器防御性复制。
     */
    static final class PendingWork {
        private final ChunkSnapshot base;
        private final long[] changedPositions;

        private PendingWork(ChunkSnapshot base, long[] changedPositions) {
            this.base = base;
            // 调用方传入刚从 owner 集合生成的私有数组，直接接管可避免大批量更新再复制一次。
            this.changedPositions = changedPositions;
        }

        int changedBlockCount() {
            return changedPositions.length;
        }

        /** 仅供同包快照捕获顺序读取；返回的私有视图不得修改或保留。 */
        long[] changedPositions() {
            return changedPositions;
        }

        int baseLight(long pos) {
            return base.light.get(pos);
        }

        boolean hasBaseLight() {
            return base.light.sectionCount() != 0;
        }
    }

    /**
     * worker 完成的一代 RGB 候选。候选在发布前与 owner 引擎完全隔离。
     */
    static final class Candidate {
        private final ChunkSnapshot snapshot;
        private final Stats stats;
        private final LongOpenHashSet dirtyMeshSections;

        private Candidate(ChunkSnapshot snapshot, Stats stats, LongOpenHashSet dirtyMeshSections) {
            this.snapshot = snapshot;
            this.stats = stats;
            this.dirtyMeshSections = new LongOpenHashSet(dirtyMeshSections);
        }

        Stats stats() {
            return stats;
        }

        ChunkSnapshot snapshot() {
            return snapshot;
        }
    }

    interface Access {
        @FunctionalInterface
        interface BoundarySeedConsumer {
            void accept(long pos, int light);
        }

        boolean isLoaded(long pos);

        int emission(long pos);

        /** 返回 1..15 的衰减值；公共面完全遮光时返回 16。 */
        int attenuation(long from, long to, int direction);

        /** 捕获域边缘来自未变化区域的冻结 RGB；同步 live access 默认没有显式边界。 */
        default void forEachBoundarySeed(BoundarySeedConsumer consumer) {
            Objects.requireNonNull(consumer, "consumer");
        }
    }

    record Stats(
            int checkedBlocks,
            long decreaseSteps,
            long increaseSteps,
            long valueChanges,
            long meshDirtySectionAttempts
    ) {
        long operations() {
            return checkedBlocks + decreaseSteps + increaseSteps + meshDirtySectionAttempts;
        }
    }

    /**
     * owner 线程分帧传播的一次切片结果。未完成时 CPU 光场仍只属于 staging，调用方不得
     * 排空 dirty section 或切换 GPU generation；完成后才可把累计统计与完整光场一起发布。
     */
    record SliceResult(boolean complete, Stats stats) {
    }

    private record PhaseResult(boolean complete, long steps) {
    }

    private final RgbLightVolume volume = new RgbLightVolume();
    private final RgbLightVolume directEmissions = new RgbLightVolume(false);
    private final LongOpenHashSet changedBlocks = new LongOpenHashSet();
    private final Long2IntOpenHashMap pendingDecrease = new Long2IntOpenHashMap();
    private final LongArrayFIFOQueue decreaseQueue = new LongArrayFIFOQueue();
    private final LongArrayFIFOQueue increaseQueue = new LongArrayFIFOQueue();
    private final LongOpenHashSet dirtyMeshSections = new LongOpenHashSet();
    private long runValueChanges;
    private long meshDirtySectionAttempts;
    private boolean boundarySeedsApplied;

    RgbLightEngine() {
        pendingDecrease.defaultReturnValue(0);
    }

    void queueBlockChange(long pos) {
        changedBlocks.add(pos);
        boundarySeedsApplied = false;
        markBlockMeshDirty(pos);
    }

    /**
     * 记录仅影响模型几何、不影响 RGB 传播的变化。
     *
     * <p>该入口不得写入 {@link #changedBlocks}，否则一个方块实体的纹理或连接模型刷新
     * 也会触发减光/增光遍历。只标记当前 section，以及方块恰好位于 section 面、棱或角
     * 边界时可能通过面角采样引用它的相邻 section。</p>
     */
    void queueMeshChange(long pos) {
        markBlockMeshDirty(pos);
    }

    int pendingChangeCount() {
        return changedBlocks.size();
    }

    int pendingPropagationCount() {
        return changedBlocks.size() + decreaseQueue.size() + increaseQueue.size();
    }

    /** 只有入口、减光和增光三个队列都为空时，当前光场才允许写入可恢复缓存。 */
    boolean isPropagationSettled() {
        return changedBlocks.isEmpty() && decreaseQueue.isEmpty() && increaseQueue.isEmpty();
    }

    boolean pendingChangesTouchExistingLight() {
        // 冷启动时 RGB 体积尚未分配任何 section，所有位置都必然为零；避免为大批量放置逐个探测七次。
        if (volume.allocatedSectionCount() == 0) {
            return false;
        }
        for (long pos : changedBlocks) {
            if (volume.get(pos) != 0) {
                return true;
            }
            for (int direction = 0; direction < PackedPosition.DIRECTION_COUNT; direction++) {
                if (volume.get(PackedPosition.offset(pos, direction)) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    int getLight(long pos) {
        return volume.get(pos);
    }

    int allocatedSectionCount() {
        return volume.allocatedSectionCount();
    }

    void collectLightSectionKeys(LongSet output) {
        volume.forEachSectionKey(output::add);
    }

    void queueChunkBoundary(int chunkX, int chunkZ) {
        volume.forEachSectionKey(sectionKey -> {
            int sectionX = PackedPosition.sectionX(sectionKey);
            int sectionY = PackedPosition.sectionY(sectionKey);
            int sectionZ = PackedPosition.sectionZ(sectionKey);
            int minY = sectionY << 4;
            if (sectionZ == chunkZ && (sectionX == chunkX - 1 || sectionX == chunkX + 1)) {
                int x = sectionX < chunkX ? (sectionX << 4) + 15 : sectionX << 4;
                for (int y = minY; y < minY + 16; y++) {
                    for (int offset = 0; offset < 16; offset++) {
                        queueExistingIncrease(PackedPosition.pack(x, y, (chunkZ << 4) + offset));
                    }
                }
            }
            if (sectionX == chunkX && (sectionZ == chunkZ - 1 || sectionZ == chunkZ + 1)) {
                int z = sectionZ < chunkZ ? (sectionZ << 4) + 15 : sectionZ << 4;
                for (int y = minY; y < minY + 16; y++) {
                    for (int offset = 0; offset < 16; offset++) {
                        queueExistingIncrease(PackedPosition.pack((chunkX << 4) + offset, y, z));
                    }
                }
            }
        });
    }

    void queueChunkBoundaries(LongSet loadedChunkBatch) {
        volume.forEachSectionKey(sectionKey -> {
            int sectionX = PackedPosition.sectionX(sectionKey);
            int sectionY = PackedPosition.sectionY(sectionKey);
            int sectionZ = PackedPosition.sectionZ(sectionKey);
            if (loadedChunkBatch.contains(chunkKey(sectionX, sectionZ))) {
                return;
            }
            int minY = sectionY << 4;
            if (loadedChunkBatch.contains(chunkKey(sectionX - 1, sectionZ))) {
                queueXFace(sectionX << 4, minY, sectionZ << 4);
            }
            if (loadedChunkBatch.contains(chunkKey(sectionX + 1, sectionZ))) {
                queueXFace((sectionX << 4) + 15, minY, sectionZ << 4);
            }
            if (loadedChunkBatch.contains(chunkKey(sectionX, sectionZ - 1))) {
                queueZFace(sectionX << 4, minY, sectionZ << 4);
            }
            if (loadedChunkBatch.contains(chunkKey(sectionX, sectionZ + 1))) {
                queueZFace(sectionX << 4, minY, (sectionZ << 4) + 15);
            }
        });
    }

    void queueRestoredChunkBoundaries(LongSet restoredChunkBatch) {
        volume.forEachSectionKey(sectionKey -> {
            int sectionX = PackedPosition.sectionX(sectionKey);
            int sectionY = PackedPosition.sectionY(sectionKey);
            int sectionZ = PackedPosition.sectionZ(sectionKey);
            if (!restoredChunkBatch.contains(chunkKey(sectionX, sectionZ))) {
                return;
            }
            int minY = sectionY << 4;
            // 同批快照也可能在不同卸载时刻捕获；所有边界都重算，避免拼接出不一致光场。
            queueXFaceChanges(sectionX << 4, minY, sectionZ << 4);
            queueXFaceChanges((sectionX << 4) + 15, minY, sectionZ << 4);
            queueZFaceChanges(sectionX << 4, minY, sectionZ << 4);
            queueZFaceChanges(sectionX << 4, minY, (sectionZ << 4) + 15);
        });
    }

    void unloadChunk(int chunkX, int chunkZ) {
        boundarySeedsApplied = false;
        directEmissions.clearChunk(chunkX, chunkZ);
        volume.removeChunk(chunkX, chunkZ, (pos, light) -> {
            queueDecrease(pos, light);
            markBlockMeshDirty(pos);
        });
    }

    void unloadChunks(LongSet chunkKeys) {
        if (!chunkKeys.isEmpty()) {
            boundarySeedsApplied = false;
        }
        directEmissions.removeChunks(chunkKeys, null);
        volume.removeChunks(chunkKeys, this::queueDecrease);
    }

    ChunkSnapshot snapshotChunk(int chunkX, int chunkZ) {
        return new ChunkSnapshot(
                volume.snapshotChunk(chunkX, chunkZ),
                directEmissions.snapshotChunk(chunkX, chunkZ)
        );
    }

    void restoreChunk(ChunkSnapshot snapshot) {
        volume.restoreChunk(snapshot.light);
        directEmissions.restoreChunk(snapshot.directEmissions);
    }

    ChunkSnapshot snapshotAll() {
        return new ChunkSnapshot(volume.snapshotAll(), directEmissions.snapshotAll());
    }

    /**
     * 捕获当前已发布光场和尚未处理的方块变化，但不清空 owner 队列。
     *
     * <p>因此 worker 运行期间若又收到变化，owner 仍保留完整事实；旧任务会被 revision 门控
     * 丢弃，下一次捕获自然包含合并后的全部变化。</p>
     */
    PendingWork snapshotPendingWork() {
        return new PendingWork(snapshotAll(), changedBlocks.toLongArray());
    }

    /**
     * 在任务私有引擎上计算 RGB candidate。该方法不会访问或修改全局引擎。
     */
    static Candidate buildCandidate(PendingWork work, Access access) {
        return buildCandidate(work, access, () -> false);
    }

    static Candidate buildCandidate(PendingWork work, Access access, BooleanSupplier cancellation) {
        RgbLightEngine candidate = new RgbLightEngine();
        candidate.activateAllWithoutMeshInvalidation(work.base);
        int queuedChanges = 0;
        for (long pos : work.changedPositions) {
            if ((queuedChanges++ & 255) == 0 && cancellation.getAsBoolean()) {
                throw new CancellationException("RGB candidate is stale");
            }
            candidate.queueBlockChange(pos);
        }
        Stats stats = candidate.process(access, cancellation);
        LongOpenHashSet dirtySections = new LongOpenHashSet();
        candidate.drainDirtyMeshSections(dirtySections::add);
        return new Candidate(candidate.snapshotAll(), stats, dirtySections);
    }

    /**
     * 在 owner 线程一次采用完整 candidate，不在发布点重新执行传播。
     */
    void publishCandidate(Candidate candidate) {
        LongOpenHashSet ownerDirtySections = new LongOpenHashSet(dirtyMeshSections);
        clear();
        volume.replaceAll(candidate.snapshot.light);
        directEmissions.replaceAll(candidate.snapshot.directEmissions);
        dirtyMeshSections.addAll(ownerDirtySections);
        dirtyMeshSections.addAll(candidate.dirtyMeshSections);
    }

    void restoreAll(ChunkSnapshot snapshot) {
        // 方块模型即使不改变传播结果，也必须保留其待重建分区，避免快照命中后显示旧几何。
        LongOpenHashSet pendingModelSections = new LongOpenHashSet(dirtyMeshSections);
        LongOpenHashSet replacedLightSections = new LongOpenHashSet();
        volume.forEachSectionKey(replacedLightSections::add);
        clear();
        volume.replaceAll(snapshot.light);
        directEmissions.replaceAll(snapshot.directEmissions);
        volume.forEachSectionKey(replacedLightSections::add);
        dirtyMeshSections.addAll(pendingModelSections);
        replacedLightSections.forEach(this::markLightSectionDirty);
    }

    /**
     * CPU 光场与对应 GPU 网格都精确命中时直接激活不可变状态。
     *
     * <p>调用方必须已经证明 GPU cache 中存在同一 exact identity 的完整有效网格；只有
     * 这种成对命中才允许丢弃旧传播队列与 dirty 集合。若 GPU 侧缺失或失效，必须改用
     * {@link #restoreAll(ChunkSnapshot)}，让渲染层重建网格。</p>
     */
    void activateAllWithoutMeshInvalidation(ChunkSnapshot snapshot) {
        clear();
        volume.replaceAll(snapshot.light);
        directEmissions.replaceAll(snapshot.directEmissions);
    }

    /**
     * GPU staging 被新 revision 取消时，把已经完成的整批 CPU 候选接回 owner，并保留取消后
     * 已排队的方块/模型变化。下一轮传播因此从“完整候选 + 新增 delta”继续，而不是退回旧代后
     * 只处理 delta，后者会永久丢失刚完成的大批量目标。
     *
     * <p>该入口只用于 CPU 候选已经收敛、owner 尚未开始处理新增变化的 staging 窗口；减光和
     * 增光队列此时必须为空，新变化仍停留在 {@link #changedBlocks}。</p>
     */
    void rebasePendingChanges(ChunkSnapshot snapshot) {
        if (!decreaseQueue.isEmpty() || !increaseQueue.isEmpty()) {
            throw new IllegalStateException("Cannot reset the GPU staging CPU base while propagation queues are active");
        }
        LongOpenHashSet pendingChanges = new LongOpenHashSet(changedBlocks);
        LongOpenHashSet pendingMeshSections = new LongOpenHashSet(dirtyMeshSections);
        clear();
        volume.replaceAll(snapshot.light);
        directEmissions.replaceAll(snapshot.directEmissions);
        changedBlocks.addAll(pendingChanges);
        dirtyMeshSections.addAll(pendingMeshSections);
    }

    /**
     * 将已收敛光场直接切换到规范全零状态。
     *
     * <p>只有上层已经用完整的已加载域光源索引证明“当前没有任何直射彩光源”，并且没有
     * 待恢复/待扫描区块时才能调用。此时继续执行 decrease BFS 的数学结果必然仍是全零，
     * 因而可以清空光场和传播队列；但旧光场覆盖过的 section 及其 26 个相邻 section 仍必须
     * 标脏，让渲染层一次性撤销旧 VBO。调用前由方块/模型回调积累的 dirty section 也必须
     * 保留，否则灯笼替换为空气时可能留下旧几何。</p>
     */
    Stats clearToCanonicalEmpty() {
        LongOpenHashSet pendingModelSections = new LongOpenHashSet(dirtyMeshSections);
        LongOpenHashSet replacedLightSections = new LongOpenHashSet();
        volume.forEachSectionKey(replacedLightSections::add);

        clear();
        dirtyMeshSections.addAll(pendingModelSections);
        replacedLightSections.forEach(this::markLightSectionDirty);

        Stats stats = new Stats(0, 0, 0, 0, meshDirtySectionAttempts);
        meshDirtySectionAttempts = 0;
        return stats;
    }

    Stats process(Access access) {
        return process(access, () -> false);
    }

    private Stats process(Access access, BooleanSupplier cancellation) {
        SliceResult result = processUntil(access, cancellation, Long.MAX_VALUE);
        if (!result.complete()) {
            throw new IllegalStateException("Unbounded RGB propagation must not be interrupted");
        }
        return result.stats();
    }

    /**
     * 在 owner 线程执行一个有界传播切片。时间预算只限制本次调用，不限制最终工作量；
     * 队列会原样保留到下一帧，直至得到与一次性 {@link #process(Access)} 相同的收敛结果。
     */
    SliceResult processSlice(Access access, long budgetNanos) {
        if (budgetNanos <= 0L) {
            throw new IllegalArgumentException("budgetNanos must be positive");
        }
        long started = System.nanoTime();
        long deadline = budgetNanos >= Long.MAX_VALUE - started
                ? Long.MAX_VALUE
                : started + budgetNanos;
        return processUntil(access, () -> false, deadline);
    }

    private SliceResult processUntil(Access access, BooleanSupplier cancellation, long deadlineNanos) {
        runValueChanges = 0;
        int checked = 0;
        LongIterator changedIterator = changedBlocks.iterator();
        while (changedIterator.hasNext()) {
            if (checked != 0 && (checked & 63) == 0
                    && shouldYield(cancellation, deadlineNanos)) {
                return incompleteSlice(checked, 0L, 0L);
            }
            long pos = changedIterator.nextLong();
            checkChangedBlock(access, pos);
            changedIterator.remove();
            checked++;
        }

        PhaseResult decreases = runDecreases(access, cancellation, deadlineNanos);
        if (!decreases.complete()) {
            return incompleteSlice(checked, decreases.steps(), 0L);
        }
        if (!boundarySeedsApplied) {
            access.forEachBoundarySeed((pos, light) -> {
                if (!access.isLoaded(pos) || light == 0) {
                    return;
                }
                int merged = PackedLight.max(volume.get(pos), light);
                setLight(pos, merged);
                queueIncrease(pos);
            });
            boundarySeedsApplied = true;
        }
        PhaseResult increases = runIncreases(access, cancellation, deadlineNanos);
        if (!increases.complete()) {
            return incompleteSlice(checked, decreases.steps(), increases.steps());
        }
        volume.drainDirtySections(this::markLightSectionDirty);
        // 一个传播批次结束即发布不可变 CPU 状态；下一批只复制实际首次写入的 section。
        volume.freezePublished();
        directEmissions.freezePublished();
        Stats stats = new Stats(
                checked,
                decreases.steps(),
                increases.steps(),
                runValueChanges,
                meshDirtySectionAttempts
        );
        meshDirtySectionAttempts = 0;
        return new SliceResult(true, stats);
    }

    private SliceResult incompleteSlice(int checked, long decreases, long increases) {
        Stats stats = new Stats(checked, decreases, increases, runValueChanges, meshDirtySectionAttempts);
        meshDirtySectionAttempts = 0;
        return new SliceResult(false, stats);
    }

    void clear() {
        volume.clear();
        directEmissions.clear();
        changedBlocks.clear();
        pendingDecrease.clear();
        decreaseQueue.clear();
        increaseQueue.clear();
        dirtyMeshSections.clear();
        meshDirtySectionAttempts = 0;
        boundarySeedsApplied = false;
    }

    void drainDirtyMeshSections(LongConsumer consumer) {
        for (long section : dirtyMeshSections) {
            consumer.accept(section);
        }
        dirtyMeshSections.clear();
    }

    private void checkChangedBlock(Access access, long pos) {
        int oldLight = volume.get(pos);
        int emission = access.isLoaded(pos) ? access.emission(pos) & 0xFFF : 0;
        directEmissions.set(pos, emission);
        if (oldLight != emission) {
            setLight(pos, emission);
        }
        if (oldLight != 0) {
            queueDecrease(pos, oldLight);
        }
        if (emission != 0) {
            queueIncrease(pos);
        }

        for (int direction = 0; direction < PackedPosition.DIRECTION_COUNT; direction++) {
            long neighbor = PackedPosition.offset(pos, direction);
            if (access.isLoaded(neighbor)) {
                int neighborLight = volume.get(neighbor);
                if (neighborLight != 0) {
                    queueIncrease(neighbor);
                }
            }
        }
    }

    private PhaseResult runDecreases(
            Access access,
            BooleanSupplier cancellation,
            long deadlineNanos
    ) {
        long steps = 0;
        long dequeued = 0;
        while (!decreaseQueue.isEmpty()) {
            if (dequeued != 0L && (dequeued & 63L) == 0L
                    && shouldYield(cancellation, deadlineNanos)) {
                return new PhaseResult(false, steps);
            }
            long pos = decreaseQueue.dequeueLong();
            dequeued++;
            int oldLight = pendingDecrease.remove(pos);
            if (oldLight == 0) {
                continue;
            }
            steps++;

            for (int direction = 0; direction < PackedPosition.DIRECTION_COUNT; direction++) {
                long neighbor = PackedPosition.offset(pos, direction);
                if (!access.isLoaded(neighbor)) {
                    continue;
                }
                int neighborLight = volume.get(neighbor);
                if (neighborLight == 0) {
                    continue;
                }

                int cleared = dependentChannels(neighborLight, oldLight);
                if (cleared == 0) {
                    queueIncrease(neighbor);
                    continue;
                }

                int remaining = removeChannels(neighborLight, cleared);
                int reseeded = PackedLight.max(remaining, directEmissions.get(neighbor));
                setLight(neighbor, reseeded);
                queueDecrease(neighbor, cleared);
                if (reseeded != 0) {
                    queueIncrease(neighbor);
                }
            }
        }
        return new PhaseResult(true, steps);
    }

    private PhaseResult runIncreases(
            Access access,
            BooleanSupplier cancellation,
            long deadlineNanos
    ) {
        long steps = 0;
        long dequeued = 0;
        while (!increaseQueue.isEmpty()) {
            if (dequeued != 0L && (dequeued & 63L) == 0L
                    && shouldYield(cancellation, deadlineNanos)) {
                return new PhaseResult(false, steps);
            }
            long pos = increaseQueue.dequeueLong();
            dequeued++;
            volume.clearIncreaseQueued(pos);
            if (!access.isLoaded(pos)) {
                continue;
            }
            int light = volume.get(pos);
            if (light == 0) {
                continue;
            }
            steps++;

            for (int direction = 0; direction < PackedPosition.DIRECTION_COUNT; direction++) {
                long neighbor = PackedPosition.offset(pos, direction);
                if (!access.isLoaded(neighbor)) {
                    continue;
                }
                int attenuation = Math.max(1, Math.min(16, access.attenuation(pos, neighbor, direction)));
                int candidate = PackedLight.attenuate(light, attenuation);
                if (candidate == 0) {
                    continue;
                }
                int current = volume.get(neighbor);
                int merged = PackedLight.max(current, candidate);
                if (merged != current) {
                    setLight(neighbor, merged);
                    queueIncrease(neighbor);
                }
            }
        }
        return new PhaseResult(true, steps);
    }

    private static boolean shouldYield(BooleanSupplier cancellation, long deadlineNanos) {
        if (cancellation.getAsBoolean()) {
            throw new CancellationException("RGB candidate is stale");
        }
        return deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos;
    }

    private void setLight(long pos, int light) {
        if (volume.set(pos, light)) {
            runValueChanges++;
        }
    }

    private void queueDecrease(long pos, int oldLight) {
        if (oldLight == 0) {
            return;
        }
        int previous = pendingDecrease.put(pos, PackedLight.max(pendingDecrease.get(pos), oldLight));
        if (previous == 0) {
            decreaseQueue.enqueue(pos);
        }
    }

    private void queueIncrease(long pos) {
        if (volume.markIncreaseQueued(pos)) {
            increaseQueue.enqueue(pos);
        }
    }

    private void queueExistingIncrease(long pos) {
        int light = volume.get(pos);
        if (light != 0) {
            queueIncrease(pos);
        }
    }

    private void queueXFace(int x, int minY, int minZ) {
        for (int y = minY; y < minY + 16; y++) {
            for (int z = minZ; z < minZ + 16; z++) {
                queueExistingIncrease(PackedPosition.pack(x, y, z));
            }
        }
    }

    private void queueZFace(int minX, int minY, int z) {
        for (int y = minY; y < minY + 16; y++) {
            for (int x = minX; x < minX + 16; x++) {
                queueExistingIncrease(PackedPosition.pack(x, y, z));
            }
        }
    }

    private void queueXFaceChanges(int x, int minY, int minZ) {
        for (int y = minY; y < minY + 16; y++) {
            for (int z = minZ; z < minZ + 16; z++) {
                queueBlockChange(PackedPosition.pack(x, y, z));
            }
        }
    }

    private void queueZFaceChanges(int minX, int minY, int z) {
        for (int y = minY; y < minY + 16; y++) {
            for (int x = minX; x < minX + 16; x++) {
                queueBlockChange(PackedPosition.pack(x, y, z));
            }
        }
    }

    private void markBlockMeshDirty(long pos) {
        LightMaskMeshPrefilter.forEachBlockMeshSection(pos, this::addDirtySection);
    }

    private void markLightSectionDirty(long sectionKey) {
        LightMaskMeshPrefilter.forEachSectionSamplingHalo(sectionKey, this::addDirtySection);
    }

    private void addDirtySection(long sectionKey) {
        meshDirtySectionAttempts++;
        dirtyMeshSections.add(sectionKey);
    }

    private static int dependentChannels(int value, int parent) {
        int red = dependent(PackedLight.red(value), PackedLight.red(parent));
        int green = dependent(PackedLight.green(value), PackedLight.green(parent));
        int blue = dependent(PackedLight.blue(value), PackedLight.blue(parent));
        return PackedLight.pack(red, green, blue);
    }

    private static int dependent(int value, int parent) {
        return value != 0 && parent > 1 && value < parent ? value : 0;
    }

    private static int removeChannels(int value, int channels) {
        return PackedLight.pack(
                PackedLight.red(channels) == 0 ? PackedLight.red(value) : 0,
                PackedLight.green(channels) == 0 ? PackedLight.green(value) : 0,
                PackedLight.blue(channels) == 0 ? PackedLight.blue(value) : 0
        );
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkZ << 32 | chunkX & 0xFFFF_FFFFL;
    }

}
