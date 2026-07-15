package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongToIntFunction;

/**
 * RGB worker 使用的不可变世界视图。
 *
 * <p>快照只暴露传播算法需要的三项数据：位置是否属于捕获域、变化位置的直接发光值，
 * 以及相邻方块间的衰减。对象内没有 {@code ClientLevel} 或 {@code Minecraft} 引用；原版
 * 方块状态适配被封装在任务私有的 {@link SnapshotBlockAndTintGetter} 中。</p>
 */
final class WorldLightSnapshot implements RgbLightEngine.Access {
    @FunctionalInterface
    interface AttenuationAccess {
        int attenuation(long from, long to, int direction);
    }

    private final int minBuildHeight;
    private final int maxBuildHeight;
    private final LongOpenHashSet capturedSections;
    private final Long2IntOpenHashMap emissions;
    private final Long2IntOpenHashMap boundarySeeds;
    private final AttenuationAccess attenuationAccess;
    private final long captureNanos;

    WorldLightSnapshot(
            int minBuildHeight,
            int height,
            LongOpenHashSet capturedSections,
            Long2IntOpenHashMap emissions,
            Long2IntOpenHashMap boundarySeeds,
            AttenuationAccess attenuationAccess,
            long captureNanos
    ) {
        if (height <= 0) {
            throw new IllegalArgumentException("height must be greater than zero");
        }
        this.minBuildHeight = minBuildHeight;
        this.maxBuildHeight = Math.addExact(minBuildHeight, height);
        // 三个容器均由 capture 新建并在此转移所有权，构造后只读，无需再次深复制哈希表。
        this.capturedSections = Objects.requireNonNull(capturedSections, "capturedSections");
        this.emissions = Objects.requireNonNull(emissions, "emissions");
        this.emissions.defaultReturnValue(0);
        this.boundarySeeds = Objects.requireNonNull(boundarySeeds, "boundarySeeds");
        this.boundarySeeds.defaultReturnValue(0);
        this.attenuationAccess = Objects.requireNonNull(attenuationAccess, "attenuationAccess");
        this.captureNanos = captureNanos;
    }

    /**
     * 把任意数量的方块变化先合并为 changed section，再增加一圈 section halo。
     * 一圈至少覆盖 15 格 RGB 传播闭包，并为边界面遮挡计算保留额外方块状态。
     */
    static Optional<LongOpenHashSet> sectionsToCaptureWithinBudget(
            long[] changedPositions,
            LongSet loadedChunks,
            int minSectionY,
            int maxSectionYExclusive,
            int maxSections
    ) {
        if (maxSections <= 0) {
            throw new IllegalArgumentException("maxSections must be greater than zero");
        }
        LongOpenHashSet changedSections = new LongOpenHashSet(
                Math.min(changedPositions.length, maxSections)
        );
        for (long pos : changedPositions) {
            changedSections.add(PackedPosition.sectionKey(pos));
            if (changedSections.size() > maxSections) {
                return Optional.empty();
            }
        }

        int resultCapacity = (int) Math.min(
                maxSections,
                (long) changedSections.size() * 27L
        );
        LongOpenHashSet result = new LongOpenHashSet(resultCapacity);
        for (long changedSection : changedSections) {
            int centerX = PackedPosition.sectionX(changedSection);
            int centerY = PackedPosition.sectionY(changedSection);
            int centerZ = PackedPosition.sectionZ(changedSection);
            for (int sectionX = centerX - 1; sectionX <= centerX + 1; sectionX++) {
                for (int sectionY = Math.max(minSectionY, centerY - 1);
                     sectionY <= Math.min(maxSectionYExclusive - 1, centerY + 1);
                     sectionY++) {
                    for (int sectionZ = centerZ - 1; sectionZ <= centerZ + 1; sectionZ++) {
                        if (loadedChunks.contains(chunkKey(sectionX, sectionZ))) {
                            result.add(PackedPosition.sectionKey(sectionX, sectionY, sectionZ));
                            if (result.size() > maxSections) {
                                return Optional.empty();
                            }
                        }
                    }
                }
            }
        }
        return Optional.of(result);
    }

    /**
     * 提取捕获域外表面的旧 RGB。halo 让这些位置离本批变化至少 16 格，可安全作为
     * decrease 完成后的边界重新灌入种子。
     */
    static Long2IntOpenHashMap captureBoundarySeeds(
            LongSet capturedSections,
            LongToIntFunction baseLight
    ) {
        Long2IntOpenHashMap seeds = new Long2IntOpenHashMap();
        seeds.defaultReturnValue(0);
        for (long sectionKey : capturedSections) {
            int sectionX = PackedPosition.sectionX(sectionKey);
            int sectionY = PackedPosition.sectionY(sectionKey);
            int sectionZ = PackedPosition.sectionZ(sectionKey);
            int minX = sectionX << 4;
            int minY = sectionY << 4;
            int minZ = sectionZ << 4;
            if (!capturedSections.contains(PackedPosition.sectionKey(sectionX - 1, sectionY, sectionZ))) {
                captureXFace(seeds, baseLight, minX, minY, minZ);
            }
            if (!capturedSections.contains(PackedPosition.sectionKey(sectionX + 1, sectionY, sectionZ))) {
                captureXFace(seeds, baseLight, minX + 15, minY, minZ);
            }
            if (!capturedSections.contains(PackedPosition.sectionKey(sectionX, sectionY - 1, sectionZ))) {
                captureYFace(seeds, baseLight, minX, minY, minZ);
            }
            if (!capturedSections.contains(PackedPosition.sectionKey(sectionX, sectionY + 1, sectionZ))) {
                captureYFace(seeds, baseLight, minX, minY + 15, minZ);
            }
            if (!capturedSections.contains(PackedPosition.sectionKey(sectionX, sectionY, sectionZ - 1))) {
                captureZFace(seeds, baseLight, minX, minY, minZ);
            }
            if (!capturedSections.contains(PackedPosition.sectionKey(sectionX, sectionY, sectionZ + 1))) {
                captureZFace(seeds, baseLight, minX, minY, minZ + 15);
            }
        }
        return seeds;
    }

    @Override
    public boolean isLoaded(long pos) {
        int y = PackedPosition.y(pos);
        return y >= minBuildHeight
                && y < maxBuildHeight
                && capturedSections.contains(PackedPosition.sectionKey(pos));
    }

    @Override
    public int emission(long pos) {
        return emissions.get(pos);
    }

    @Override
    public int attenuation(long from, long to, int direction) {
        return attenuationAccess.attenuation(from, to, direction);
    }

    @Override
    public void forEachBoundarySeed(RgbLightEngine.Access.BoundarySeedConsumer consumer) {
        boundarySeeds.long2IntEntrySet().forEach(entry ->
                consumer.accept(entry.getLongKey(), entry.getIntValue()));
    }

    int capturedSectionCount() {
        return capturedSections.size();
    }

    long captureNanos() {
        return captureNanos;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkZ << 32 | chunkX & 0xFFFF_FFFFL;
    }

    private static void captureXFace(
            Long2IntOpenHashMap seeds, LongToIntFunction baseLight, int x, int minY, int minZ
    ) {
        for (int y = minY; y < minY + 16; y++) {
            for (int z = minZ; z < minZ + 16; z++) {
                captureSeed(seeds, baseLight, PackedPosition.pack(x, y, z));
            }
        }
    }

    private static void captureYFace(
            Long2IntOpenHashMap seeds, LongToIntFunction baseLight, int minX, int y, int minZ
    ) {
        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                captureSeed(seeds, baseLight, PackedPosition.pack(x, y, z));
            }
        }
    }

    private static void captureZFace(
            Long2IntOpenHashMap seeds, LongToIntFunction baseLight, int minX, int minY, int z
    ) {
        for (int x = minX; x < minX + 16; x++) {
            for (int y = minY; y < minY + 16; y++) {
                captureSeed(seeds, baseLight, PackedPosition.pack(x, y, z));
            }
        }
    }

    private static void captureSeed(
            Long2IntOpenHashMap seeds, LongToIntFunction baseLight, long pos
    ) {
        int light = baseLight.applyAsInt(pos);
        if (light != 0) {
            seeds.put(pos, light);
        }
    }
}
