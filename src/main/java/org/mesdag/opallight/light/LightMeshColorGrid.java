package org.mesdag.opallight.light;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import java.util.BitSet;

/// 网格及两格采样边界的紧凑颜色数组，各构建线程独占并复用。
final class LightMeshColorGrid {
    private static final int WIDTH = (1 << LightMaskMeshCache.GROUP_XZ_BLOCK_SHIFT) + 4;
    private static final int HEIGHT = (1 << LightMaskMeshCache.GROUP_Y_BLOCK_SHIFT) + 4;
    private int minX, minY, minZ;
    private final long[] colors = new long[WIDTH * HEIGHT * WIDTH];
    private final BitSet occupied = new BitSet(colors.length);

    LightMeshColorGrid(long key) {
        reset(key);
    }

    void reset(long key) {
        /// 只清理上次写入的位置，稀疏彩光也不需要清空整个数组。
        for (int index = occupied.nextSetBit(0); index >= 0; index = occupied.nextSetBit(index + 1)) {
            colors[index] = 0;
        }
        occupied.clear();
        minX = (SectionPos.x(key) << LightMaskMeshCache.GROUP_XZ_BLOCK_SHIFT) - 2;
        minY = (SectionPos.y(key) << LightMaskMeshCache.GROUP_Y_BLOCK_SHIFT) - 2;
        minZ = (SectionPos.z(key) << LightMaskMeshCache.GROUP_XZ_BLOCK_SHIFT) - 2;
    }

    void put(long pos, long color) {
        int index = index(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos));
        if (index < 0) return;
        colors[index] = color;
        occupied.set(index);
    }

    long get(int x, int y, int z) {
        int index = index(x, y, z);
        return index < 0 ? 0 : colors[index];
    }

    private int index(int x, int y, int z) {
        x -= minX;
        y -= minY;
        z -= minZ;
        if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT || z < 0 || z >= WIDTH) return -1;
        return (y * WIDTH + z) * WIDTH + x;
    }

    int next(int from) {
        return occupied.nextSetBit(from);
    }

    long position(int index) {
        return BlockPos.asLong(minX + index % WIDTH, minY + index / (WIDTH * WIDTH),
                minZ + index / WIDTH % WIDTH);
    }
}
