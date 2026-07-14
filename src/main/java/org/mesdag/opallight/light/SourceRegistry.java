package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;

/**
 * 维护当前已加载范围内的彩色光源，并为批量状态缓存生成精确身份。
 *
 * <p>可变路径只使用 primitive 集合，不设置条目上限，也不会静默丢弃光源。
 * 该类按客户端主线程单写设计；冻结结果可安全交给只读缓存或后台任务。</p>
 */
final class SourceRegistry {
    private static final int PACKED_LIGHT_MASK = 0xFFF;
    private static final long SIZE_MIX = 0x9E3779B97F4A7C15L;

    private final Long2IntOpenHashMap emissionsByPosition = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<LongOpenHashSet> positionsByChunk = new Long2ObjectOpenHashMap<>();
    private long tupleDigest;

    SourceRegistry() {
        emissionsByPosition.defaultReturnValue(0);
    }

    /**
     * 新增或替换一个光源，返回替换前的低十二位光值。
     * 高位与 {@link PackedLight} 的读取语义一致，会被规范化丢弃；规范化为零等同删除。
     */
    int put(long position, int packedEmission) {
        int normalized = packedEmission & PACKED_LIGHT_MASK;
        int previous = emissionsByPosition.get(position);
        if (previous == normalized) {
            return previous;
        }
        if (normalized == 0) {
            remove(position);
            return previous;
        }

        if (previous == 0) {
            emissionsByPosition.put(position, normalized);
            long chunkKey = chunkKey(position);
            LongOpenHashSet chunkPositions = positionsByChunk.get(chunkKey);
            if (chunkPositions == null) {
                chunkPositions = new LongOpenHashSet();
                positionsByChunk.put(chunkKey, chunkPositions);
            }
            chunkPositions.add(position);
        } else {
            emissionsByPosition.put(position, normalized);
            tupleDigest ^= tupleDigest(position, previous);
        }
        tupleDigest ^= tupleDigest(position, normalized);
        return previous;
    }

    /** 删除单个位置并返回原值；不存在时返回零。 */
    int remove(long position) {
        int previous = emissionsByPosition.remove(position);
        if (previous == 0) {
            return 0;
        }

        tupleDigest ^= tupleDigest(position, previous);
        long chunkKey = chunkKey(position);
        LongOpenHashSet chunkPositions = positionsByChunk.get(chunkKey);
        if (chunkPositions != null) {
            chunkPositions.remove(position);
            if (chunkPositions.isEmpty()) {
                positionsByChunk.remove(chunkKey);
            }
        }
        return previous;
    }

    /** 删除指定区块内的全部光源，并返回实际删除数量。 */
    int removeChunk(int chunkX, int chunkZ) {
        return removeChunk(packChunk(chunkX, chunkZ));
    }

    /** 删除由 Minecraft 区块长整型编码指定的全部光源。 */
    int removeChunk(long chunkKey) {
        LongOpenHashSet positions = positionsByChunk.remove(chunkKey);
        if (positions == null) {
            return 0;
        }

        int removed = positions.size();
        LongIterator iterator = positions.iterator();
        while (iterator.hasNext()) {
            long position = iterator.nextLong();
            int emission = emissionsByPosition.remove(position);
            if (emission != 0) {
                tupleDigest ^= tupleDigest(position, emission);
            }
        }
        return removed;
    }

    int get(long position) {
        return emissionsByPosition.get(position);
    }

    boolean contains(long position) {
        return emissionsByPosition.containsKey(position);
    }

    int size() {
        return emissionsByPosition.size();
    }

    boolean isEmpty() {
        return emissionsByPosition.isEmpty();
    }

    void clear() {
        emissionsByPosition.clear();
        positionsByChunk.clear();
        tupleDigest = 0L;
    }

    /** 返回用于候选定位的 O(1) 摘要；最终命中仍必须比较冻结元组。 */
    long fastDigest() {
        return mix64(tupleDigest ^ (long) size() * SIZE_MIX);
    }

    /**
     * 按有符号 packed-position 排序并复制所有元组。
     * 冻结成本只在缓存身份建立时支付，不进入方块逐次变更的热路径。
     */
    FrozenIdentity freezeIdentity() {
        long[] positions = emissionsByPosition.keySet().toLongArray();
        Arrays.sort(positions);
        int[] emissions = new int[positions.length];
        for (int index = 0; index < positions.length; index++) {
            emissions[index] = emissionsByPosition.get(positions[index]);
        }
        return new FrozenIdentity(fastDigest(), positions, emissions);
    }

    private static long chunkKey(long position) {
        return packChunk(PackedPosition.x(position) >> 4, PackedPosition.z(position) >> 4);
    }

    /** 与 Minecraft 的 ChunkPos.asLong 编码保持一致，并保留负区块坐标。 */
    private static long packChunk(int chunkX, int chunkZ) {
        return (long) chunkX & 0xFFFFFFFFL | ((long) chunkZ & 0xFFFFFFFFL) << 32;
    }

    private static long tupleDigest(long position, int emission) {
        return mix64(position ^ Integer.toUnsignedLong(emission) * SIZE_MIX);
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    /**
     * 不可变、规范排序的精确光源身份。
     * 摘要仅用于快速排除；摘要相同后仍逐项比较位置和光值，因而不会把哈希碰撞当作命中。
     */
    static final class FrozenIdentity {
        private final long fastDigest;
        private final long[] positions;
        private final int[] emissions;

        FrozenIdentity(long fastDigest, long[] positions, int[] emissions) {
            if (positions.length != emissions.length) {
                throw new IllegalArgumentException("光源位置与光值数量不一致");
            }
            for (int index = 0; index < positions.length; index++) {
                if (index > 0 && positions[index - 1] >= positions[index]) {
                    throw new IllegalArgumentException("光源位置必须严格递增且不能重复");
                }
                if (emissions[index] == 0 || (emissions[index] & ~PACKED_LIGHT_MASK) != 0) {
                    throw new IllegalArgumentException("冻结光值必须是非零的低十二位 PackedLight");
                }
            }
            this.fastDigest = fastDigest;
            this.positions = positions.clone();
            this.emissions = emissions.clone();
        }

        long fastDigest() {
            return fastDigest;
        }

        int size() {
            return positions.length;
        }

        long positionAt(int index) {
            return positions[index];
        }

        int emissionAt(int index) {
            return emissions[index];
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FrozenIdentity identity)) {
                return false;
            }
            return fastDigest == identity.fastDigest
                    && Arrays.equals(positions, identity.positions)
                    && Arrays.equals(emissions, identity.emissions);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(fastDigest);
        }

        @Override
        public String toString() {
            return "FrozenIdentity{size=" + positions.length + ", fastDigest=" + fastDigest + '}';
        }
    }
}
