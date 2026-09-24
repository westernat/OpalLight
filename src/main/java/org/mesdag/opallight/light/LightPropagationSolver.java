package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.function.LongPredicate;

/// 按亮度从高到低传播，每个光源对已确定的节点只累加一次颜色。
final class LightPropagationSolver {
    private static final Direction[] DIRECTIONS = Direction.values();

    record Result(List<LightColorCache.SectionUpdate> updates, boolean unsupported) {}

    static Result compute(LightPropagationSnapshot input, BlockGetter view,
                          LongPredicate loadedChunk, boolean snapshotRead) {
        LongOpenHashSet targets = input.targets();
        List<LightSource> sources = input.sources();
        if (snapshotRead && input.unsupported()) {
            return new Result(List.of(), true);
        }
        Long2ObjectOpenHashMap<AccumulatedSection> accumulated = new Long2ObjectOpenHashMap<>();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long target : targets) {
            minX = Math.min(minX, ChunkPos.getX(target) << 4);
            minZ = Math.min(minZ, ChunkPos.getZ(target) << 4);
            maxX = Math.max(maxX, (ChunkPos.getX(target) << 4) + 15);
            maxZ = Math.max(maxZ, (ChunkPos.getZ(target) << 4) + 15);
        }
        int maxEmission = 0;
        for (LightSource source : sources) maxEmission = Math.max(maxEmission, source.emission());
        LongArrayFIFOQueue[] queues = new LongArrayFIFOQueue[maxEmission + 1];
        for (int level = 1; level < queues.length; level++) queues[level] = new LongArrayFIFOQueue();
        /// 每步至少衰减一级；单个光源的可达坐标跨度不超过数组边长，低位索引不会重叠。
        int side = 1;
        while (side < maxEmission * 2 - 1) side <<= 1;
        int coordinateMask = side - 1;
        int coordinateBits = Integer.numberOfTrailingZeros(side);
        int[] bestLevels = new int[side * side * side];
        int levelMask = (Integer.highestOneBit(maxEmission) << 1) - 1;
        int generation = 0;
        /// 同一批光源共用边界遮挡结果，避免反复查询相同方块面的形状。
        Long2IntOpenHashMap edgeCosts = new Long2IntOpenHashMap();
        BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos nextPos = new BlockPos.MutableBlockPos();
        int minBuildHeight = input.minBuildHeight(), height = input.height();
        for (LightSource source : sources) {
            input.checkCancelled();
            if (!input.canStillReachTarget(source)) {
                continue;
            }
            int radius = source.emission() - 1;
            int sourceX = BlockPos.getX(source.pos()), sourceZ = BlockPos.getZ(source.pos());
            boolean trim = sourceX - radius < minX || sourceX + radius > maxX
                    || sourceZ - radius < minZ || sourceZ + radius > maxZ;
            /// 高位标记当前光源，低位保存亮度；避免为封闭光源也清空整块数组。
            generation += levelMask + 1;
            if (generation == 0) Arrays.fill(bestLevels, 0);
            queues[source.emission()].enqueue(source.pos());
            bestLevels[localIndex(BlockPos.getX(source.pos()), BlockPos.getY(source.pos()),
                    BlockPos.getZ(source.pos()), coordinateMask, coordinateBits)] = generation | source.emission();
            for (int lightLevel = source.emission(); lightLevel >= 1; lightLevel--) {
                LongArrayFIFOQueue queue = queues[lightLevel];
                float factor = (float) lightLevel / source.emission();
                float red = source.color().r() * factor;
                float green = source.color().g() * factor;
                float blue = source.color().b() * factor;
                while (!queue.isEmpty()) {
                    long pos = queue.dequeueLong();
                    int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
                    if ((generation | lightLevel) != bestLevels[localIndex(x, y, z, coordinateMask, coordinateBits)]) continue;
                    /// 每步至少衰减一级；连目标包围范围都无法到达的节点不再扩展。
                    if (trim) {
                        int distanceX = Math.max(0, Math.max(minX - x, x - maxX));
                        int distanceZ = Math.max(0, Math.max(minZ - z, z - maxZ));
                        if (distanceX + distanceZ >= lightLevel) continue;
                    }
                    long chunkKey = ChunkPos.asLong(x >> 4, z >> 4);
                    if (targets.contains(chunkKey)) {
                        long sectionKey = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
                        if (input.targetsSection(sectionKey)) {
                            var section = accumulated.computeIfAbsent(sectionKey, unused -> new AccumulatedSection());
                            int index = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
                            section.colors[index] = LightColorCache.addPacked(section.colors[index], red, green, blue);
                            section.occupied.set(index);
                        }
                    }
                    if (lightLevel <= 1) continue;
                    BlockState currentState = null;
                    int encodedEdges = edgeCosts.get(pos);
                    int previousEdges = encodedEdges;
                    for (int direction = 0; direction < DIRECTIONS.length; direction++) {
                        Direction dir = DIRECTIONS[direction];
                        int nx = x + dir.getStepX(), ny = y + dir.getStepY(), nz = z + dir.getStepZ();
                        int nextIndex = localIndex(nx, ny, nz, coordinateMask, coordinateBits);
                        int stored = bestLevels[nextIndex];
                        int previousLevel = (stored & ~levelMask) == generation ? stored & levelMask : 0;
                        if (lightLevel - 1 <= previousLevel) continue;
                        int shift = direction * 5;
                        int edge = encodedEdges >>> shift & 31;
                        if (edge == 0) {
                            if (ny < minBuildHeight || ny >= minBuildHeight + height
                                    || !loadedChunk.test(ChunkPos.asLong(nx >> 4, nz >> 4))) {
                                edge = 1;
                            } else {
                                nextPos.set(nx, ny, nz);
                                BlockState nextState = view.getBlockState(nextPos);
                                int attenuation = Math.max(1, nextState.getLightBlock(view, nextPos));
                                if (attenuation >= 16) {
                                    edge = 1;
                                } else if (lightLevel - attenuation <= previousLevel) {
                                    continue;
                                } else {
                                    if (currentState == null) {
                                        currentPos.set(x, y, z);
                                        currentState = view.getBlockState(currentPos);
                                    }
                                    edge = Shapes.faceShapeOccludes(
                                            LightEngine.getOcclusionShape(view, currentPos, currentState, dir),
                                            LightEngine.getOcclusionShape(view, nextPos, nextState, dir.getOpposite()))
                                            ? 1 : attenuation + 1;
                                }
                            }
                            encodedEdges |= edge << shift;
                        }
                        if (edge == 1) continue;
                        int nextLevel = lightLevel - (edge - 1);
                        if (nextLevel <= previousLevel) continue;
                        bestLevels[nextIndex] = generation | nextLevel;
                        queues[nextLevel].enqueue(BlockPos.asLong(nx, ny, nz));
                    }
                    if (encodedEdges != previousEdges) edgeCosts.put(pos, encodedEdges);
                }
            }
        }
        Long2ObjectOpenHashMap<Long2LongOpenHashMap> output = new Long2ObjectOpenHashMap<>();
        for (var entry : accumulated.long2ObjectEntrySet()) {
            input.checkCancelled();
            long key = entry.getLongKey();
            AccumulatedSection section = entry.getValue();
            Long2LongOpenHashMap values = new Long2LongOpenHashMap(section.occupied.cardinality());
            int sx = SectionPos.x(key) << 4, sy = SectionPos.y(key) << 4, sz = SectionPos.z(key) << 4;
            for (int i = section.occupied.nextSetBit(0); i >= 0; i = section.occupied.nextSetBit(i + 1)) {
                values.put(BlockPos.asLong(sx + (i & 15), sy + (i >> 8), sz + (i >> 4 & 15)), section.colors[i]);
            }
            output.put(key, values);
        }
        var updates = input.changes(output);
        return new Result(updates, snapshotRead && input.unsupported());
    }

    /// 传播期间按分段局部坐标累加，完成后一次生成稀疏结果，避免每个光源重复哈希同一体素。
    private static final class AccumulatedSection {
        final long[] colors = new long[4096];
        final BitSet occupied = new BitSet(4096);
    }

    private static int localIndex(int x, int y, int z, int mask, int bits) {
        return (x & mask) | (z & mask) << bits | (y & mask) << (bits * 2);
    }
}
