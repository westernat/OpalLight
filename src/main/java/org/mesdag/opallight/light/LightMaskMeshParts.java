package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/// 缓存方块模型，使彩光变化时只重新生成顶点颜色。
final class LightMaskMeshParts {
    private final Long2ObjectOpenHashMap<LongOpenHashSet> rebuildBlocks = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh>> geometryCache = new Long2ObjectOpenHashMap<>();

    @Nullable Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> previousGeometry(long key) {
        return geometryCache.get(key);
    }

    LongSet changedBlocks(long key) {
        LongOpenHashSet rebuild = rebuildBlocks.get(key);
        return rebuild == null ? LongSets.emptySet() : new LongOpenHashSet(rebuild);
    }

    void markBlockChanged(long key, int x, int y, int z) {
        LongOpenHashSet blocks = rebuildBlocks.computeIfAbsent(key, unused -> new LongOpenHashSet());
        addNeighbors(blocks, key, x, y, z);
    }

    void markChunkLoaded(long key, ChunkPos chunkPos) {
        Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> geometry = geometryCache.get(key);
        if (geometry == null) return;
        int minX = (chunkPos.x << 4) - 1, maxX = (chunkPos.x << 4) + 16;
        int minZ = (chunkPos.z << 4) - 1, maxZ = (chunkPos.z << 4) + 16;
        LongOpenHashSet rebuild = null;
        for (long pos : geometry.keySet()) {
            int x = BlockPos.getX(pos), z = BlockPos.getZ(pos);
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
            if (rebuild == null) rebuild = rebuildBlocks.computeIfAbsent(key, unused -> new LongOpenHashSet());
            rebuild.add(pos);
        }
    }

    private static void addNeighbors(LongOpenHashSet blocks, long key, int x, int y, int z) {
        for (int bx = -1; bx <= 1; bx++) {
            for (int by = -1; by <= 1; by++) {
                for (int bz = -1; bz <= 1; bz++) {
                    int px = x + bx, py = y + by, pz = z + bz;
                    if (SectionPos.asLong(px >> LightMeshLayout.GROUP_XZ_BLOCK_SHIFT,
                            py >> LightMeshLayout.GROUP_Y_BLOCK_SHIFT,
                            pz >> LightMeshLayout.GROUP_XZ_BLOCK_SHIFT) == key) {
                        blocks.add(BlockPos.asLong(px, py, pz));
                    }
                }
            }
        }
    }

    void replace(long key, Long2ObjectOpenHashMap<LightMaskMeshBuilder.BlockMesh> geometry) {
        remove(key);
        geometryCache.put(key, geometry);
    }

    void remove(long key) {
        geometryCache.remove(key);
        rebuildBlocks.remove(key);
    }

    void clear() {
        rebuildBlocks.clear();
        geometryCache.clear();
    }
}
