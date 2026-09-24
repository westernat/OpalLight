package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class LightPropagator {
    record Commit(List<LightColorCache.SectionUpdate> updates, boolean dynamic) {}
    private static final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<ObjectIntPair<OpalColor>>> sourcesByChunk = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<LongOpenHashSet> emittersByChunk = new Long2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<LightSource> dynamicSources = new Int2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<List<LightSource>> dynamicByChunk = new Long2ObjectOpenHashMap<>();
    private static final LongOpenHashSet pendingChunks = new LongOpenHashSet();
    private static final LongOpenHashSet dynamicPendingChunks = new LongOpenHashSet();
    /// 仅局部更新保存高度分段；缺少条目表示整根区块柱需要重算。
    private static final Long2ObjectOpenHashMap<LongOpenHashSet> pendingSections = new Long2ObjectOpenHashMap<>();
    private static final Long2LongOpenHashMap chunkVersions = new Long2LongOpenHashMap();
    private static final ExecutorService propagationWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "OpalLight propagation");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService dynamicWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "OpalLight dynamic propagation");
        thread.setDaemon(true);
        return thread;
    });
    private static final int ASYNC_BATCH_CHUNKS = 64;
    private record ActiveBatch(Level level, Long2LongOpenHashMap versions, LightPropagationSnapshot snapshot,
                               CompletableFuture<LightPropagationSolver.Result> future, boolean dynamic) {}
    private static ActiveBatch activeBatch;

    public static boolean isNearAffectedSection(long sectionPos) {
        int sx = SectionPos.x(sectionPos), sy = SectionPos.y(sectionPos), sz = SectionPos.z(sectionPos);
        if (activeBatch != null || !pendingChunks.isEmpty()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = sx + dx, cz = sz + dz;
                    if (activeBatch != null && activeBatch.versions().containsKey(ChunkPos.asLong(cx, cz))
                            || pendingChunks.contains(ChunkPos.asLong(cx, cz))) return true;
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (LightColorCache.INSTANCE.hasSection(SectionPos.asLong(sx + dx, sy + dy, sz + dz))) return true;
                }
            }
        }
        return false;
    }

    public static void clearAllSources() {
        if (activeBatch != null) activeBatch.snapshot().cancel();
        sourcesByChunk.clear();
        emittersByChunk.clear();
        dynamicSources.clear();
        dynamicByChunk.clear();
        pendingChunks.clear();
        dynamicPendingChunks.clear();
        pendingSections.clear();
        chunkVersions.clear();
        activeBatch = null;
    }

    public static void indexChunk(Level level, ChunkPos cp) {
        var chunk = level.getChunkSource().getChunkForLighting(cp.x, cp.z);
        if (chunk == null) {
            sourcesByChunk.remove(ChunkPos.asLong(cp.x, cp.z));
            emittersByChunk.remove(ChunkPos.asLong(cp.x, cp.z));
            return;
        }
        Long2ObjectOpenHashMap<ObjectIntPair<OpalColor>> sources = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet emitters = new LongOpenHashSet();
        chunk.findBlockLightSources((pos, state) -> {
            emitters.add(pos.asLong());
            ObjectIntPair<OpalColor> color = LightSourceDefinitions.colorWithEmissive(level, pos, state);
            if (color != null) sources.put(pos.asLong(), color);
        });
        long chunkKey = ChunkPos.asLong(cp.x, cp.z);
        sourcesByChunk.put(chunkKey, sources);
        emittersByChunk.put(chunkKey, emitters);
    }

    public static void forgetChunk(ChunkPos cp) {
        sourcesByChunk.remove(ChunkPos.asLong(cp.x, cp.z));
        emittersByChunk.remove(ChunkPos.asLong(cp.x, cp.z));
    }

    /// 动态光源按实体和手槽跟踪；一次替换同时标记旧位置和新位置，避免移动残影。
    static int replaceDynamicSources(Int2ObjectMap<LightSource> current) {
        int changed = 0;
        for (var entry : dynamicSources.int2ObjectEntrySet()) {
            LightSource next = current.get(entry.getIntKey());
            if (entry.getValue().equals(next)) continue;
            scheduleAround(BlockPos.of(entry.getValue().pos()), entry.getValue().emission(), true);
            changed++;
        }
        for (var entry : current.int2ObjectEntrySet()) {
            LightSource old = dynamicSources.get(entry.getIntKey());
            if (entry.getValue().equals(old)) continue;
            /// 同位置同半径的颜色变化已在旧源范围入队，不再重复合并九个区块。
            if (old == null || old.pos() != entry.getValue().pos() || old.emission() != entry.getValue().emission()) {
                scheduleAround(BlockPos.of(entry.getValue().pos()), entry.getValue().emission(), true);
            }
            if (old == null) changed++;
        }
        if (changed == 0) return 0;
        dynamicSources.clear();
        dynamicSources.putAll(current);
        dynamicByChunk.clear();
        for (LightSource source : dynamicSources.values()) {
            long chunk = ChunkPos.asLong(BlockPos.getX(source.pos()) >> 4, BlockPos.getZ(source.pos()) >> 4);
            dynamicByChunk.computeIfAbsent(chunk, unused -> new ArrayList<>()).add(source);
        }
        prioritizeDynamicPropagation();
        return changed;
    }

    private static void prioritizeDynamicPropagation() {
        ActiveBatch previous = activeBatch;
        if (previous == null || previous.future().isDone()) return;
        /// 原批次未完成时保留全部目标和高度范围，再让动态光先计算。
        previous.snapshot().cancel();
        for (long key : previous.versions().keySet()) {
            LongOpenHashSet oldSections = previous.snapshot().targetSections(key);
            if (pendingChunks.add(key)) {
                if (oldSections != null) pendingSections.put(key, new LongOpenHashSet(oldSections));
            } else {
                LongOpenHashSet pending = pendingSections.get(key);
                if (pending == null || oldSections == null) pendingSections.remove(key);
                else pending.addAll(oldSections);
            }
        }
        activeBatch = null;
    }

    public static boolean updateSource(Level level, BlockPos pos, BlockState state) {
        long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        if (state.getLightEmission(level, pos) > 0) {
            emittersByChunk.computeIfAbsent(chunkKey, unused -> new LongOpenHashSet()).add(pos.asLong());
        } else {
            LongOpenHashSet emitters = emittersByChunk.get(chunkKey);
            if (emitters != null) emitters.remove(pos.asLong());
        }
        Long2ObjectOpenHashMap<ObjectIntPair<OpalColor>> sources = sourcesByChunk.get(chunkKey);
        ObjectIntPair<OpalColor> previous = sources == null ? null : sources.get(pos.asLong());
        ObjectIntPair<OpalColor> current = LightSourceDefinitions.colorWithEmissive(level, pos, state);
        if (current == null) {
            if (sources != null) sources.remove(pos.asLong());
        } else {
            if (sources == null) {
                sources = new Long2ObjectOpenHashMap<>();
                sourcesByChunk.put(chunkKey, sources);
            }
            sources.put(pos.asLong(), current);
        }
        return previous == null ? current != null
                : current == null || previous.rightInt() != current.rightInt() || !previous.left().equals(current.left());
    }

    public static void scheduleAround(BlockPos pos) {
        scheduleAround(pos, 15, false);
    }

    private static void scheduleAround(BlockPos pos, int emission, boolean dynamic) {
        int cx = SectionPos.blockToSectionCoord(pos.getX());
        int cz = SectionPos.blockToSectionCoord(pos.getZ());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int minX = (cx + dx) << 4, minZ = (cz + dz) << 4;
                int distanceX = pos.getX() < minX ? minX - pos.getX() : Math.max(0, pos.getX() - minX - 15);
                int distanceZ = pos.getZ() < minZ ? minZ - pos.getZ() : Math.max(0, pos.getZ() - minZ - 15);
                int vertical = emission - 1 - distanceX - distanceZ;
                if (vertical >= 0) {
                    LongOpenHashSet sections = new LongOpenHashSet();
                    for (int sy = (pos.getY() - vertical) >> 4; sy <= (pos.getY() + vertical) >> 4; sy++) {
                        sections.add(SectionPos.asLong(cx + dx, sy, cz + dz));
                    }
                    scheduleChunk(cx + dx, cz + dz, sections);
                    if (dynamic) dynamicPendingChunks.add(ChunkPos.asLong(cx + dx, cz + dz));
                }
            }
        }
    }

    public static void scheduleChunkAndNeighbors(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                scheduleChunk(cx + dx, cz + dz);
            }
        }
    }

    private static void scheduleChunk(int cx, int cz) {
        scheduleChunk(cx, cz, null);
    }

    private static void scheduleChunk(int cx, int cz, @Nullable LongOpenHashSet sections) {
        long key = ChunkPos.asLong(cx, cz);
        if (pendingChunks.add(key)) {
            if (activeBatch != null && activeBatch.versions().containsKey(key)) {
                /// 整个区块版本会失效，重算必须包含旧任务尚未提交的所有高度。
                var activeSections = activeBatch.snapshot().targetSections(key);
                if (sections == null || activeSections == null) sections = null;
                else sections.addAll(activeSections);
                activeBatch.snapshot().invalidateTarget(key);
            }
            if (sections != null) pendingSections.put(key, sections);
            chunkVersions.addTo(key, 1);
            if (activeBatch != null && activeBatch.versions().containsKey(key)
                    && pendingChunks.containsAll(activeBatch.versions().keySet())) {
                /// 所有目标都已重新排队，旧批次已不可能提交任何结果。
                activeBatch.snapshot().cancel();
                activeBatch = null;
            }
        } else if (sections == null) {
            pendingSections.remove(key);
        } else {
            var pending = pendingSections.get(key);
            if (pending != null) pending.addAll(sections);
        }
    }

    public static int refreshDefinitions(Level level) {
        int changed = 0;
        LongOpenHashSet indexedChunks = new LongOpenHashSet();
        for (var chunkEntry : emittersByChunk.long2ObjectEntrySet()) {
            long chunkKey = chunkEntry.getLongKey();
            indexedChunks.add(chunkKey);
            Long2ObjectOpenHashMap<ObjectIntPair<OpalColor>> oldSources = sourcesByChunk.get(chunkKey);
            Long2ObjectOpenHashMap<ObjectIntPair<OpalColor>> refreshed = new Long2ObjectOpenHashMap<>();
            for (long packedPos : chunkEntry.getValue()) {
                BlockPos pos = BlockPos.of(packedPos);
                ObjectIntPair<OpalColor> current = LightSourceDefinitions.colorWithEmissive(level, pos, level.getBlockState(pos));
                ObjectIntPair<OpalColor> previous = oldSources == null ? null : oldSources.get(packedPos);
                if (current != null) refreshed.put(packedPos, current);
                if (!sameSource(previous, current)) {
                    scheduleAround(pos);
                    changed++;
                }
            }
            if (oldSources != null) {
                for (long packedPos : oldSources.keySet()) {
                    if (chunkEntry.getValue().contains(packedPos)) continue;
                    scheduleAround(BlockPos.of(packedPos));
                    changed++;
                }
            }
            sourcesByChunk.put(chunkKey, refreshed);
        }
        LongOpenHashSet staleChunks = new LongOpenHashSet();
        for (var chunkEntry : sourcesByChunk.long2ObjectEntrySet()) {
            if (indexedChunks.contains(chunkEntry.getLongKey())) continue;
            for (long packedPos : chunkEntry.getValue().keySet()) {
                scheduleAround(BlockPos.of(packedPos));
                changed++;
            }
            staleChunks.add(chunkEntry.getLongKey());
        }
        for (long chunkKey : staleChunks) sourcesByChunk.remove(chunkKey);
        return changed;
    }

    private static boolean sameSource(ObjectIntPair<OpalColor> previous, ObjectIntPair<OpalColor> current) {
        return previous == null ? current == null : current != null && previous.rightInt() == current.rightInt() && previous.left().equals(current.left());
    }

    public static boolean hasPendingUpdates() {
        return !pendingChunks.isEmpty() || activeBatch != null;
    }

    static boolean isGroupPropagationPending(long groupKey) {
        if (pendingChunks.isEmpty() && activeBatch == null) return false;
        int sx = SectionPos.x(groupKey) << LightMeshLayout.GROUP_XZ_SECTION_SHIFT;
        int sz = SectionPos.z(groupKey) << LightMeshLayout.GROUP_XZ_SECTION_SHIFT;
        for (int cx = sx - 1; cx <= sx + 2; cx++) {
            for (int cz = sz - 1; cz <= sz + 2; cz++) {
                long chunkKey = ChunkPos.asLong(cx, cz);
                if (pendingChunks.contains(chunkKey)
                        || activeBatch != null && activeBatch.versions().containsKey(chunkKey)) return true;
            }
        }
        return false;
    }

    static @Nullable Commit flushPending(Level level, boolean firstMeshPending,
                                                Supplier<OptionalLong> preferredGroup) {
        Commit commit = null;
        if (activeBatch != null) {
            if (!activeBatch.future().isDone()) return null;
            ActiveBatch finished = activeBatch;
            activeBatch = null;
            if (finished.level() == level) commit = commitAsync(level, finished);
        }
        /// 下一份快照必须在调用方发布本次颜色结果后才能捕获旧颜色。
        if (commit != null) return commit;
        if (pendingChunks.isEmpty()) return commit;
        boolean dynamicBatch = !dynamicPendingChunks.isEmpty();
        Long2LongOpenHashMap versions = takeBatch(level, firstMeshPending,
                firstMeshPending && !dynamicBatch ? preferredGroup.get() : OptionalLong.empty());
        if (versions.isEmpty()) return commit;
        LongOpenHashSet requested = new LongOpenHashSet(versions.keySet());
        Long2ObjectOpenHashMap<LongOpenHashSet> sections = new Long2ObjectOpenHashMap<>();
        for (long key : requested) {
            var range = pendingSections.remove(key);
            if (range != null) sections.put(key, range);
        }
        LightPropagationSnapshot snapshot;
        try {
            snapshot = LightPropagationSnapshot.capture(level, requested, sections, sourcesByChunk, dynamicByChunk);
        } catch (RuntimeException error) {
            org.mesdag.opallight.OpalLight.LOGGER.error("Failed to snapshot colored light propagation", error);
            pendingChunks.addAll(requested);
            pendingSections.putAll(sections);
            return commit;
        }
        /// 没有光源且没有旧颜色需要清除时，不产生工作线程任务。
        if (snapshot.isEmpty()) {
            for (long key : requested) chunkVersions.remove(key);
            return commit;
        }
        activeBatch = new ActiveBatch(level, versions, snapshot,
                CompletableFuture.supplyAsync(snapshot::compute, dynamicBatch ? dynamicWorker : propagationWorker), dynamicBatch);
        return commit;
    }

    private static Long2LongOpenHashMap takeBatch(Level level, boolean firstMeshPending, OptionalLong preferredGroup) {
        Long2LongOpenHashMap versions = new Long2LongOpenHashMap();
        int centerX = Minecraft.getInstance().player == null ? 0 : Minecraft.getInstance().player.getBlockX() >> 4;
        int centerZ = Minecraft.getInstance().player == null ? 0 : Minecraft.getInstance().player.getBlockZ() >> 4;
        LongArrayList nearest = new LongArrayList(pendingChunks);
        boolean dynamicBatch = !dynamicPendingChunks.isEmpty();
        if (dynamicBatch) firstMeshPending = false;
        nearest.sort((a, b) -> {
            long ax = (long) ChunkPos.getX(a) - centerX, az = (long) ChunkPos.getZ(a) - centerZ;
            long bx = (long) ChunkPos.getX(b) - centerX, bz = (long) ChunkPos.getZ(b) - centerZ;
            return Long.compare(ax * ax + az * az, bx * bx + bz * bz);
        });
        nearest = prioritizeLoadedNeighborhood(nearest);
        /// 已有颜色结果时优先补齐可见实体分段，避免继续处理近处空中区块。
        OptionalLong preferred = firstMeshPending ? preferredGroup : OptionalLong.empty();
        boolean anchored = preferred.isPresent();
        int groupX = anchored ? SectionPos.x(preferred.getAsLong()) << LightMeshLayout.GROUP_XZ_SECTION_SHIFT : 0;
        int groupZ = anchored ? SectionPos.z(preferred.getAsLong()) << LightMeshLayout.GROUP_XZ_SECTION_SHIFT : 0;
        int groupSize = 1 << LightMeshLayout.GROUP_XZ_SECTION_SHIFT;
        for (int i = 0; i < nearest.size() && versions.size() < ASYNC_BATCH_CHUNKS; i++) {
            long key = nearest.getLong(i);
            if (dynamicBatch && !dynamicPendingChunks.contains(key)) continue;
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            if (firstMeshPending && anchored && (cx < groupX - 1 || cx > groupX + groupSize
                    || cz < groupZ - 1 || cz > groupZ + groupSize)) continue;
            pendingChunks.remove(key);
            dynamicPendingChunks.remove(key);
            if (level.getChunkSource().getChunkForLighting(cx, cz) == null) {
                chunkVersions.remove(key);
                pendingSections.remove(key);
                continue;
            }
            if (firstMeshPending && !anchored) {
                /// 首屏优先完成最近网格及其采样边界，范围与网格的传播等待条件一致。
                groupX = (cx >> LightMeshLayout.GROUP_XZ_SECTION_SHIFT) << LightMeshLayout.GROUP_XZ_SECTION_SHIFT;
                groupZ = (cz >> LightMeshLayout.GROUP_XZ_SECTION_SHIFT) << LightMeshLayout.GROUP_XZ_SECTION_SHIFT;
                anchored = true;
            }
            versions.put(key, chunkVersions.get(key));
        }
        return versions;
    }

    private static LongArrayList prioritizeLoadedNeighborhood(LongArrayList nearest) {
        LongArrayList ordered = new LongArrayList(nearest.size());
        BitSet frontier = new BitSet(nearest.size());
        for (int i = 0; i < nearest.size(); i++) {
            long key = nearest.getLong(i);
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            boolean ready = true;
            for (int dx = -1; dx <= 1 && ready; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!emittersByChunk.containsKey(ChunkPos.asLong(cx + dx, cz + dz))) {
                        ready = false;
                        break;
                    }
                }
            }
            if (ready) ordered.add(key);
            else frontier.set(i);
        }
        /// 邻域已索引的区块先计算，边缘任务仍补入本批，不等待后续加载。
        for (int i = frontier.nextSetBit(0); i >= 0; i = frontier.nextSetBit(i + 1)) {
            ordered.add(nearest.getLong(i));
        }
        return ordered;
    }

    private static @Nullable Commit commitAsync(Level level, ActiveBatch finished) {
        LongOpenHashSet valid = new LongOpenHashSet();
        for (var entry : finished.versions().long2LongEntrySet()) {
            long key = entry.getLongKey();
            if (chunkVersions.get(key) == entry.getLongValue()) {
                valid.add(key);
            }
        }
        for (long key : valid) chunkVersions.remove(key);
        if (valid.isEmpty()) return null;
        LightPropagationSolver.Result result;
        try {
            result = finished.future().join();
        } catch (RuntimeException error) {
            org.mesdag.opallight.OpalLight.LOGGER.warn("Async colored light propagation failed; rebuilding on client thread", error);
            result = finished.snapshot().computeLive(level);
        }
        if (result.unsupported()) {
            result = finished.snapshot().computeLive(level);
        }
        List<LightColorCache.SectionUpdate> updates = new ArrayList<>();
        for (var update : result.updates()) {
            long key = ChunkPos.asLong(SectionPos.x(update.key()), SectionPos.z(update.key()));
            if (valid.contains(key)) updates.add(update);
        }
        return updates.isEmpty() ? null : new Commit(updates, finished.dynamic());
    }

}
