package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * 维护当前已加载范围内的彩色光源，并为批量状态缓存生成精确身份。
 *
 * <p>可变路径只使用 primitive 集合，不设置条目上限，也不会静默丢弃光源。
 * 该类按客户端主线程单写设计；冻结结果可安全交给只读缓存或后台任务。</p>
 */
final class SourceRegistry {
    private static final int PACKED_LIGHT_MASK = 0xFFF;

    private final Long2IntOpenHashMap emissionsByPosition = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<LongOpenHashSet> positionsByChunk = new Long2ObjectOpenHashMap<>();
    private @Nullable FrozenIdentity frozenIdentity;

    SourceRegistry() {
        emissionsByPosition.defaultReturnValue(0);
    }

    /**
     * 新增或替换一个光源。
     * 高位与 {@link PackedLight} 的读取语义一致，会被规范化丢弃；规范化为零等同删除。
     */
    void put(long position, int packedEmission) {
        int normalized = packedEmission & PACKED_LIGHT_MASK;
        int previous = emissionsByPosition.get(position);
        if (previous == normalized) {
            return;
        }
        if (normalized == 0) {
            remove(position);
            return;
        }
        frozenIdentity = null;

        if (previous == 0) {
            emissionsByPosition.put(position, normalized);
            long chunkKey = chunkKey(position);
            @Nullable LongOpenHashSet chunkPositions = positionsByChunk.get(chunkKey);
            if (chunkPositions == null) {
                chunkPositions = new LongOpenHashSet();
                positionsByChunk.put(chunkKey, chunkPositions);
            }
            chunkPositions.add(position);
        } else {
            emissionsByPosition.put(position, normalized);
        }
    }

    /** 删除单个位置。 */
    private void remove(long position) {
        int previous = emissionsByPosition.remove(position);
        if (previous == 0) {
            return;
        }
        frozenIdentity = null;

        long chunkKey = chunkKey(position);
        @Nullable LongOpenHashSet chunkPositions = positionsByChunk.get(chunkKey);
        if (chunkPositions != null) {
            chunkPositions.remove(position);
            if (chunkPositions.isEmpty()) {
                positionsByChunk.remove(chunkKey);
            }
        }
    }

    /** 删除由 Minecraft 区块长整型编码指定的全部光源。 */
    void removeChunk(long chunkKey) {
        LongOpenHashSet positions = positionsByChunk.remove(chunkKey);
        if (positions == null) {
            return;
        }
        frozenIdentity = null;

        LongIterator iterator = positions.iterator();
        while (iterator.hasNext()) {
            long position = iterator.nextLong();
            emissionsByPosition.remove(position);
        }
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
        frozenIdentity = null;
    }

    /**
     * 按有符号 packed-position 排序并复制所有元组。
     * 冻结成本只在缓存身份建立时支付，不进入方块逐次变更的热路径。
     */
    FrozenIdentity freezeIdentity() {
        @Nullable FrozenIdentity cached = frozenIdentity;
        if (cached != null) {
            return cached;
        }
        long[] positions = emissionsByPosition.keySet().toLongArray();
        Arrays.sort(positions);
        short[] emissions = new short[positions.length];
        for (int index = 0; index < positions.length; index++) {
            emissions[index] = (short) emissionsByPosition.get(positions[index]);
        }
        FrozenIdentity frozen = new FrozenIdentity(
                positions,
                emissions,
                positionsByChunk.keySet().toLongArray()
        );
        frozenIdentity = frozen;
        return frozen;
    }

    private static long chunkKey(long position) {
        return packChunk(PackedPosition.x(position) >> 4, PackedPosition.z(position) >> 4);
    }

    /** 与 Minecraft 的 ChunkPos.asLong 编码保持一致，并保留负区块坐标。 */
    private static long packChunk(int chunkX, int chunkZ) {
        return (long) chunkX & 0xFFFFFFFFL | ((long) chunkZ & 0xFFFFFFFFL) << 32;
    }

    /**
     * 不可变、规范排序的精确光源身份。
     * 两个数组均由冻结过程新建并转交给最终缓存键，注册表之后不会再修改它们。
     */
    static final class FrozenIdentity {
        private final long[] positions;
        private final short[] emissions;
        private final long[] chunkKeys;

        FrozenIdentity(long[] positions, short[] emissions, long[] chunkKeys) {
            if (positions.length != emissions.length) {
                throw new IllegalArgumentException("Source positions and light values must have the same length");
            }
            for (int index = 0; index < positions.length; index++) {
                if (index > 0 && positions[index - 1] >= positions[index]) {
                    throw new IllegalArgumentException("Source positions must be strictly increasing and unique");
                }
                int emission = Short.toUnsignedInt(emissions[index]);
                if (emission == 0 || (emission & ~PACKED_LIGHT_MASK) != 0) {
                    throw new IllegalArgumentException("Frozen light values must be nonzero 12-bit PackedLight values");
                }
            }
            this.positions = positions;
            this.emissions = emissions;
            this.chunkKeys = chunkKeys;
        }

        /** 返回所有权已冻结的数组，只允许最终缓存键接管，调用方不得修改。 */
        long[] positionsView() {
            return positions;
        }

        /** 返回与位置平行的 12 位光值数组，只允许最终缓存键接管，调用方不得修改。 */
        short[] emissionsView() {
            return emissions;
        }

        /** 返回去重后的光源区块键，只允许依赖域构建过程只读使用。 */
        long[] chunkKeysView() {
            return chunkKeys;
        }
    }
}
