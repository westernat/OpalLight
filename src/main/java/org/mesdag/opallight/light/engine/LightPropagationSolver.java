package org.mesdag.opallight.light.engine;

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
import org.mesdag.opallight.light.color.LightColorData;
import org.mesdag.opallight.light.color.OpalColor;
import org.mesdag.opallight.light.data.LightDataLoader;

import java.util.*;
import java.util.function.LongPredicate;

final class LightPropagationSolver {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final ThreadLocal<PropagationScratch> scratch = ThreadLocal.withInitial(PropagationScratch::new);

    record Result(List<LightColorCache.SectionUpdate> updates, boolean unsupported) {}

    private record SourceKey(long pos, int emission) {
    }

    // 同位置、同范围的光共享遮挡路径，合并遍历仍保留总光强。
    static final class CombinedSource {
        final LightSource original;
        double red, green, blue;

        CombinedSource(LightSource original) {
            this.original = original;
        }

        long pos() {
            return original.pos();
        }

        int emission() {
            return original.emission();
        }
    }

    static List<CombinedSource> coalesce(List<LightSource> sources, long gameTime) {
        var combined = new LinkedHashMap<SourceKey, CombinedSource>();
        for (LightSource source : sources) {
            var cycle = source.profile().cycle();
            OpalColor color = cycle == null ? source.profile().color() : cycleColor(cycle, gameTime);
            float peak = Math.max(color.r(), Math.max(color.g(), color.b()));
            if (peak <= 0 || source.emission() <= 0) continue;
            var entry = combined.computeIfAbsent(new SourceKey(source.pos(), source.emission()),
                unused -> new CombinedSource(source));
            entry.red += color.r();
            entry.green += color.g();
            entry.blue += color.b();
        }
        return new ArrayList<>(combined.values());
    }

    static Result compute(LightPropagationSnapshot input, BlockGetter view,
                          LongPredicate loadedChunk, boolean snapshotRead) {
        LongOpenHashSet targets = input.targets();
        List<CombinedSource> sources = coalesce(input.sources(), input.gameTime());
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
        for (CombinedSource source : sources) maxEmission = Math.max(maxEmission, source.emission());
        // 每步至少衰减一级；单个光源的可达坐标跨度不超过数组边长，低位索引不会重叠。
        int side = 1;
        while (side < maxEmission * 2 - 1) side <<= 1;
        int coordinateMask = side - 1;
        int coordinateBits = Integer.numberOfTrailingZeros(side);
        PropagationScratch workspace = scratch.get();
        workspace.prepare(maxEmission, side * side * side);
        LongArrayFIFOQueue[] queues = workspace.queues;
        int[] bestLevels = workspace.bestLevels;
        int levelMask = (Integer.highestOneBit(maxEmission) << 1) - 1;
        int generation = 0;
        BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos nextPos = new BlockPos.MutableBlockPos();
        int minBuildHeight = input.minBuildHeight(), height = input.height();
        for (CombinedSource source : sources) {
            input.checkCancelled();
            if (!input.canStillReachTarget(source.original)) {
                continue;
            }
            int radius = source.emission() - 1;
            int sourceX = BlockPos.getX(source.pos()), sourceZ = BlockPos.getZ(source.pos());
            boolean trim = sourceX - radius < minX || sourceX + radius > maxX
                    || sourceZ - radius < minZ || sourceZ + radius > maxZ;
            // 高位标记当前光源，低位保存亮度；避免为封闭光源也清空整块数组。
            generation += levelMask + 1;
            if (generation == 0) Arrays.fill(bestLevels, 0);
            queues[source.emission()].enqueue(source.pos());
            bestLevels[localIndex(BlockPos.getX(source.pos()), BlockPos.getY(source.pos()),
                    BlockPos.getZ(source.pos()), coordinateMask, coordinateBits)] = generation | source.emission();
            for (int lightLevel = source.emission(); lightLevel >= 1; lightLevel--) {
                LongArrayFIFOQueue queue = queues[lightLevel];
                float factor = (float) lightLevel / source.emission();
                double solidRed = source.red * factor;
                double solidGreen = source.green * factor;
                double solidBlue = source.blue * factor;
                int visited = 0;
                while (!queue.isEmpty()) {
                    if ((visited++ & 1023) == 0) input.checkCancelled();
                    long pos = queue.dequeueLong();
                    int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
                    if ((generation | lightLevel) != bestLevels[localIndex(x, y, z, coordinateMask, coordinateBits)]) continue;
                    // 每步至少衰减一级；连目标包围范围都无法到达的节点不再扩展。
                    if (trim) {
                        int distanceX = Math.max(0, Math.max(minX - x, x - maxX));
                        int distanceZ = Math.max(0, Math.max(minZ - z, z - maxZ));
                        if (distanceX + distanceZ >= lightLevel) continue;
                    }
                    long sectionKey = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
                    AccumulatedSection section = accumulated.get(sectionKey);
                    if (section == null) {
                        boolean target = targets.contains(ChunkPos.asLong(x >> 4, z >> 4)) && input.targetsSection(sectionKey);
                        section = new AccumulatedSection(target);
                        accumulated.put(sectionKey, section);
                    }
                    int index = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
                    if (section.colors != null) {
                        int offset = index * 3;
                        section.colors[offset] += solidRed;
                        section.colors[offset + 1] += solidGreen;
                        section.colors[offset + 2] += solidBlue;
                        section.occupied.set(index);
                    }
                    if (lightLevel <= 1) continue;
                    BlockState currentState = null;
                    int encodedEdges = section.edges[index];
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
                    section.edges[index] = encodedEdges;
                }
            }
        }
        Long2ObjectOpenHashMap<Long2LongOpenHashMap> output = new Long2ObjectOpenHashMap<>();
        for (var entry : accumulated.long2ObjectEntrySet()) {
            input.checkCancelled();
            long key = entry.getLongKey();
            AccumulatedSection section = entry.getValue();
            if (section.colors == null) continue;
            Long2LongOpenHashMap values = new Long2LongOpenHashMap(section.occupied.cardinality());
            int sx = SectionPos.x(key) << 4, sy = SectionPos.y(key) << 4, sz = SectionPos.z(key) << 4;
            for (int i = section.occupied.nextSetBit(0); i >= 0; i = section.occupied.nextSetBit(i + 1)) {
                int offset = i * 3;
                values.put(BlockPos.asLong(sx + (i & 15), sy + (i >> 8), sz + (i >> 4 & 15)),
                    LightColorData.pack(section.colors[offset], section.colors[offset + 1],
                        section.colors[offset + 2]));
            }
            output.put(key, values);
        }
        var updates = input.changes(output);
        return new Result(updates, snapshotRead && input.unsupported());
    }

    private static final class AccumulatedSection {
        // 遮挡边与颜色共用分段索引，避免每个光源反复哈希体素坐标。
        final int[] edges = new int[4096];
        // 用双精度减少强弱光混合时的小贡献丢失。
        final double[] colors;
        final BitSet occupied = new BitSet(4096);

        AccumulatedSection(boolean target) {
            colors = target ? new double[4096 * 3] : null;
        }
    }

    private static final class PropagationScratch {
        int[] bestLevels = new int[0];
        LongArrayFIFOQueue[] queues = new LongArrayFIFOQueue[0];

        void prepare(int emission, int cells) {
            if (bestLevels.length < cells) bestLevels = new int[cells];
            else Arrays.fill(bestLevels, 0, cells, 0);
            if (queues.length <= emission) {
                int previous = queues.length;
                queues = Arrays.copyOf(queues, emission + 1);
                for (int i = Math.max(1, previous); i < queues.length; i++) queues[i] = new LongArrayFIFOQueue();
            }
            // 取消任务也可能留下队列内容；只复用容器，不跨快照复用遮挡结果。
            for (int i = 1; i < queues.length; i++) queues[i].clear();
        }
    }

    private static int localIndex(int x, int y, int z, int mask, int bits) {
        return (x & mask) | (z & mask) << bits | (y & mask) << (bits * 2);
    }

    static OpalColor cycleColor(LightDataLoader.CyclePattern pattern, long gameTime) {
        double position = (double) Math.floorMod(gameTime, pattern.periodTicks())
            / pattern.periodTicks() * pattern.colors().size();
        int first = (int) position;
        float fraction = (float) (position - first);
        OpalColor a = pattern.colors().get(first);
        OpalColor b = pattern.colors().get((first + 1) % pattern.colors().size());
        return OpalColor.of(a.r() + (b.r() - a.r()) * fraction,
            a.g() + (b.g() - a.g()) * fraction,
            a.b() + (b.b() - a.b()) * fraction);
    }
}
