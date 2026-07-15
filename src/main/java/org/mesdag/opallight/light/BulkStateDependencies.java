package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Arrays;

/**
 * 提取批量光照快照的最小安全依赖集。
 *
 * <p>快照身份不能直接包含客户端所有已加载区块：玩家横跨一个区块边界时，
 * 视距远端会一边卸载、另一边加载，即使附近建筑和光源完全没变，“全区块集合”也会
 * 导致缓存恒定失效。反过来，若只记录有光数据的 section，又会遗漏边界外能够
 * 遮挡或接收光照的方块。</p>
 *
 * <p>RGB 光级与原版一样最高为 15，每跨过一个方块至少衰减 1，因此任一已知光场
 * section 或目标光源只可能依赖其所在区块及周围 8 个区块。必须使用完整 3×3，
 * 不能只取四个正交邻居，因为区块角落处的光传播与面可见性同样会依赖对角区块。</p>
 */
final class BulkStateDependencies {
    private BulkStateDependencies() {}

    static long[] relevantLoadedChunks(LongSet loadedChunks, LongSet lightSections, long[] sourceChunkKeys) {
        LongOpenHashSet dependencyChunks = new LongOpenHashSet(sourceChunkKeys);
        // 同一区块可能包含多个垂直光照 section，先按区块合并，再统一展开一次 3×3 邻域。
        for (long sectionKey : lightSections) {
            dependencyChunks.add(chunkKey(
                    PackedPosition.sectionX(sectionKey),
                    PackedPosition.sectionZ(sectionKey)
            ));
        }

        int relevantCapacity = (int) Math.min(
                1_048_576L,
                (long) dependencyChunks.size() * 9L
        );
        // 这里只限制预分配规模，不限制集合最终容量；极端世界仍会按需正常扩容。
        LongOpenHashSet relevantChunks = new LongOpenHashSet(relevantCapacity);
        for (long dependencyChunk : dependencyChunks) {
            addChunkAndNeighbors(
                    relevantChunks,
                    (int) dependencyChunk,
                    (int) (dependencyChunk >>> 32)
            );
        }
        relevantChunks.retainAll(loadedChunks);
        long[] relevantLoadedChunks = relevantChunks.toLongArray();
        Arrays.sort(relevantLoadedChunks);
        return relevantLoadedChunks;
    }

    static boolean removedChunksAffectMeshState(LongSet removedChunks, LongSet meshSections) {
        // 每个 VBO 只拥有一个 section 的几何；判断卸载影响时只需比较它所属区块。
        for (long sectionKey : meshSections) {
            if (removedChunks.contains(chunkKey(
                    PackedPosition.sectionX(sectionKey), PackedPosition.sectionZ(sectionKey)
            ))) {
                return true;
            }
        }
        return false;
    }

    static long[] relevantChunkFingerprints(long[] relevantChunks, Long2LongMap fingerprints) {
        // relevantChunks 已排序，并行数组可让 equals 精确比较“位置 + 内容”，不依赖 Map 遍历顺序。
        long[] relevantFingerprints = new long[relevantChunks.length];
        for (int index = 0; index < relevantChunks.length; index++) {
            relevantFingerprints[index] = fingerprints.get(relevantChunks[index]);
        }
        return relevantFingerprints;
    }

    private static void addChunkAndNeighbors(LongOpenHashSet chunks, int chunkX, int chunkZ) {
        for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                chunks.add(chunkKey(chunkX + offsetX, chunkZ + offsetZ));
            }
        }
    }

    private static long chunkKey(int x, int z) {
        // 与 ChunkPos.asLong 完全相同：低 32 位为 x，高 32 位为 z，保留负坐标的位模式。
        return Integer.toUnsignedLong(x) | Integer.toUnsignedLong(z) << 32;
    }
}
