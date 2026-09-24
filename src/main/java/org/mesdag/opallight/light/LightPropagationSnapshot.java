package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CancellationException;

/// 在客户端线程复制传播所需的方块状态，工作线程只读取此快照。
final class LightPropagationSnapshot implements BlockGetter {
    record Source(long pos, OpalColor color, int emission) {}

    private record ChunkSections(PalettedContainer<BlockState>[] states, BitSet copied) {
        @Nullable BlockState get(BlockPos pos, int minSection) {
            int index = (pos.getY() >> 4) - minSection;
            if (index < 0 || index >= states.length) return Blocks.AIR.defaultBlockState();
            if (!copied.get(index)) return null;
            if (states[index] == null) return Blocks.AIR.defaultBlockState();
            return states[index].get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
    }

    private final Long2ObjectOpenHashMap<ChunkSections> chunks;
    private final LongOpenHashSet targets;
    /// 主线程发布替换集合，工作线程不读取正在修改的哈希表。
    private volatile LongOpenHashSet remainingTargets;
    private final Long2ObjectOpenHashMap<LongOpenHashSet> restrictedSections;
    private final List<Source> sources;
    private final Long2ObjectOpenHashMap<Long2LongOpenHashMap> previousColors;
    private final int minBuildHeight;
    private final int height;
    private final int minSection;
    private boolean unsupported;
    private volatile boolean cancelled;

    private LightPropagationSnapshot(Long2ObjectOpenHashMap<ChunkSections> chunks, LongOpenHashSet targets,
                                     Long2ObjectOpenHashMap<LongOpenHashSet> restrictedSections,
                                     List<Source> sources, int minBuildHeight, int height, boolean unsupported) {
        this.chunks = chunks;
        this.targets = targets;
        this.remainingTargets = targets;
        this.restrictedSections = restrictedSections;
        this.sources = sources;
        this.minBuildHeight = minBuildHeight;
        this.height = height;
        this.minSection = SectionPos.blockToSectionCoord(minBuildHeight);
        this.unsupported = unsupported;
        this.previousColors = new Long2ObjectOpenHashMap<>();
        for (long key : targets) {
            for (int sy = minSection; sy <= (minBuildHeight + height - 1) >> 4; sy++) {
                long sectionKey = SectionPos.asLong(ChunkPos.getX(key), sy, ChunkPos.getZ(key));
                if (!targetsSection(sectionKey)) continue;
                var colors = LightColorCache.INSTANCE.getSection(sectionKey);
                if (colors != null) previousColors.put(sectionKey, colors);
            }
        }
    }

    List<LightColorCache.SectionUpdate> changes(Long2ObjectOpenHashMap<Long2LongOpenHashMap> colors) {
        List<LightColorCache.SectionUpdate> changes = new ArrayList<>();
        for (var entry : colors.long2ObjectEntrySet()) {
            checkCancelled();
            var update = LightColorCache.difference(entry.getLongKey(), previousColors.get(entry.getLongKey()), entry.getValue());
            if (update != null) changes.add(update);
        }
        for (var entry : previousColors.long2ObjectEntrySet()) {
            checkCancelled();
            if (colors.containsKey(entry.getLongKey())) continue;
            var update = LightColorCache.difference(entry.getLongKey(), entry.getValue(), null);
            if (update != null) changes.add(update);
        }
        return changes;
    }

    boolean isEmpty() {
        return sources.isEmpty() && previousColors.isEmpty();
    }

    static LightPropagationSnapshot capture(Level level, LongOpenHashSet requested,
            Long2ObjectOpenHashMap<LongOpenHashSet> restrictedSections,
            Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.objects.ObjectIntPair<OpalColor>>> indexedSources) {
        LongOpenHashSet targets = new LongOpenHashSet(requested);
        LongOpenHashSet scanned = new LongOpenHashSet();
        for (long chunkKey : targets) {
            int cx = ChunkPos.getX(chunkKey), cz = ChunkPos.getZ(chunkKey);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) scanned.add(ChunkPos.asLong(cx + dx, cz + dz));
            }
        }
        List<Source> sources = new ArrayList<>();
        Long2ObjectOpenHashMap<BitSet> neededSections = new Long2ObjectOpenHashMap<>();
        for (long chunkKey : scanned) {
            var indexed = indexedSources.get(chunkKey);
            if (indexed == null) continue;
            for (var entry : indexed.long2ObjectEntrySet()) {
                long pos = entry.getLongKey();
                int emission = entry.getValue().rightInt();
                if (!canReachTarget(pos, emission, targets, restrictedSections)) continue;
                sources.add(new Source(pos, entry.getValue().left(), emission));
                includeSourceSections(pos, emission, level.getMinBuildHeight(), level.getMaxBuildHeight(), neededSections);
            }
        }
        Long2ObjectOpenHashMap<ChunkSections> chunks = new Long2ObjectOpenHashMap<>();
        boolean unsupported = false;
        for (var entry : neededSections.long2ObjectEntrySet()) {
            long chunkKey = entry.getLongKey();
            LightChunk chunk = level.getChunkSource().getChunkForLighting(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null) continue;
            if (!(chunk instanceof LevelChunk loaded)) {
                unsupported = true;
                continue;
            }
            try {
                LevelChunkSection[] sections = loaded.getSections();
                @SuppressWarnings("unchecked")
                PalettedContainer<BlockState>[] copy = new PalettedContainer[sections.length];
                BitSet needed = entry.getValue();
                for (int index = needed.nextSetBit(0); index >= 0 && index < sections.length; index = needed.nextSetBit(index + 1)) {
                    if (!sections[index].hasOnlyAir()) copy[index] = sections[index].getStates().copy();
                }
                chunks.put(chunkKey, new ChunkSections(copy, entry.getValue()));
            } catch (RuntimeException error) {
                unsupported = true;
            }
        }
        return new LightPropagationSnapshot(chunks, targets,
                restrictedSections, sources, level.getMinBuildHeight(), level.getHeight(), unsupported);
    }

    private static void includeSourceSections(long pos, int emission, int minHeight, int maxHeight,
                                              Long2ObjectOpenHashMap<BitSet> neededSections) {
        int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
        int radius = emission - 1;
        int margin = 2;
        int minSection = minHeight >> 4;
        /// 可达体素的曼哈顿范围，加两格相邻状态查询余量；避免复制立方体角落的无关分段。
        for (int cx = (x - radius - margin) >> 4; cx <= (x + radius + margin) >> 4; cx++) {
            int dx = Math.max(0, Math.max((cx << 4) - x, x - (cx << 4) - 15) - margin);
            for (int cz = (z - radius - margin) >> 4; cz <= (z + radius + margin) >> 4; cz++) {
                int dz = Math.max(0, Math.max((cz << 4) - z, z - (cz << 4) - 15) - margin);
                int vertical = radius - dx - dz;
                if (vertical < 0) continue;
                int fromY = Math.max(minHeight, y - vertical - margin) >> 4;
                int toY = Math.min(maxHeight - 1, y + vertical + margin) >> 4;
                if (fromY > toY) continue;
                neededSections.computeIfAbsent(ChunkPos.asLong(cx, cz), unused -> new BitSet())
                        .set(fromY - minSection, toY - minSection + 1);
            }
        }
    }

    private static boolean canReachTarget(long source, int emission, LongOpenHashSet targets,
                                         Long2ObjectOpenHashMap<LongOpenHashSet> restrictedSections) {
        int x = BlockPos.getX(source), y = BlockPos.getY(source), z = BlockPos.getZ(source);
        for (int cx = (x - emission + 1) >> 4; cx <= (x + emission - 1) >> 4; cx++) {
            int minX = cx << 4;
            int dx = x < minX ? minX - x : Math.max(0, x - minX - 15);
            for (int cz = (z - emission + 1) >> 4; cz <= (z + emission - 1) >> 4; cz++) {
                long chunkKey = ChunkPos.asLong(cx, cz);
                if (!targets.contains(chunkKey)) continue;
                int minZ = cz << 4;
                int dz = z < minZ ? minZ - z : Math.max(0, z - minZ - 15);
                if (dx + dz >= emission) continue;
                var sections = restrictedSections.get(chunkKey);
                if (sections == null) return true;
                for (long section : sections) {
                    int minY = SectionPos.y(section) << 4;
                    int dy = Math.max(0, Math.max(minY - y, y - minY - 15));
                    if (dx + dy + dz < emission) return true;
                }
            }
        }
        return false;
    }

    LongOpenHashSet targets() {
        return targets;
    }

    @Nullable LongOpenHashSet targetSections(long chunkKey) {
        return restrictedSections.get(chunkKey);
    }

    boolean targetsSection(long sectionKey) {
        var sections = restrictedSections.get(ChunkPos.asLong(SectionPos.x(sectionKey), SectionPos.z(sectionKey)));
        return sections == null || sections.contains(sectionKey);
    }

    List<Source> sources() {
        return sources;
    }

    int minBuildHeight() {
        return minBuildHeight;
    }

    int height() {
        return height;
    }

    boolean unsupported() {
        return unsupported;
    }

    void cancel() {
        cancelled = true;
    }

    void invalidateTarget(long key) {
        LongOpenHashSet remaining = new LongOpenHashSet(remainingTargets);
        remaining.remove(key);
        remainingTargets = remaining;
    }

    boolean canStillReachTarget(Source source) {
        LongOpenHashSet remaining = remainingTargets;
        return remaining == targets || canReachTarget(source.pos(), source.emission(), remaining, restrictedSections);
    }

    void checkCancelled() {
        if (cancelled) throw new CancellationException("Colored light world unloaded");
    }

    LightPropagationSolver.Result compute() {
        return LightPropagationSolver.compute(this, this, chunks::containsKey, true);
    }

    LightPropagationSolver.Result computeLive(Level level) {
        return LightPropagationSolver.compute(this, level, key -> level.getChunkSource().getChunkForLighting(
                ChunkPos.getX(key), ChunkPos.getZ(key)) != null, false);
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        /// 依赖方块实体的动态遮挡必须回到客户端线程计算。
        unsupported = true;
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        ChunkSections chunk = chunks.get(chunkKey);
        if (chunk == null) {
            unsupported = true;
            return Blocks.AIR.defaultBlockState();
        }
        BlockState state = chunk.get(pos, minSection);
        if (state == null) {
            unsupported = true;
            return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return minBuildHeight;
    }
}
