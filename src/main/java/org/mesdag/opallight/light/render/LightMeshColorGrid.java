package org.mesdag.opallight.light.render;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import org.mesdag.opallight.light.color.LightColorSampler;
import org.mesdag.opallight.light.engine.LightMeshLayout;

import java.util.BitSet;

// 网格及两格采样边界，各构建线程独占。
final class LightMeshColorGrid {
    private static final int WIDTH = (1 << LightMeshLayout.GROUP_XZ_BLOCK_SHIFT) + 4;
    private static final int HEIGHT = (1 << LightMeshLayout.GROUP_Y_BLOCK_SHIFT) + 4;
    private int minX, minY, minZ;
    private final long[] colors = new long[WIDTH * HEIGHT * WIDTH];
    private final BitSet occupied = new BitSet(colors.length);
    private final LightColorSampler sampler = new LightColorSampler(this::get, true);
    private final Long2IntOpenHashMap sampleOffsets = new Long2IntOpenHashMap();
    private final FloatArrayList sampleValues = new FloatArrayList();
    private BlockGetter sampleView;

    LightMeshColorGrid(long key) {
        reset(key);
    }

    void reset(long key) {
        // 只清理上次写入的位置。
        for (int index = occupied.nextSetBit(0); index >= 0; index = occupied.nextSetBit(index + 1)) {
            colors[index] = 0;
        }
        occupied.clear();
        sampler.clear();
        finishSampling();
        minX = (SectionPos.x(key) << LightMeshLayout.GROUP_XZ_BLOCK_SHIFT) - 2;
        minY = (SectionPos.y(key) << LightMeshLayout.GROUP_Y_BLOCK_SHIFT) - 2;
        minZ = (SectionPos.z(key) << LightMeshLayout.GROUP_XZ_BLOCK_SHIFT) - 2;
    }

    void put(long pos, long color) {
        int index = index(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos));
        if (index < 0) return;
        if (!sampleOffsets.isEmpty()) clearSamples();
        colors[index] = color;
        occupied.set(index);
    }

    long get(int x, int y, int z) {
        int index = index(x, y, z);
        return index < 0 ? 0 : colors[index];
    }

    void sample(BlockGetter view, double x, double y, double z,
                int anchorX, int anchorY, int anchorZ, float[] result) {
        if (sampleView != view) {
            clearSamples();
            sampleView = view;
        }
        double hx = (x - minX) * 2, hy = (y - minY) * 2, hz = (z - minZ) * 2;
        int ix = (int) hx, iy = (int) hy, iz = (int) hz;
        // 普通方块共享半格采样点；任意偏移模型沿用完整采样。
        if (ix == hx && iy == hy && iz == hz && ix >= 0 && ix < WIDTH * 2
            && iy >= 0 && iy < HEIGHT * 2 && iz >= 0 && iz < WIDTH * 2) {
            int seed = net.minecraft.util.Mth.clamp(anchorX - (int) Math.floor(x - .5), 0, 1)
                | net.minecraft.util.Mth.clamp(anchorY - (int) Math.floor(y - .5), 0, 1) << 1
                | net.minecraft.util.Mth.clamp(anchorZ - (int) Math.floor(z - .5), 0, 1) << 2;
            long key = ix | (long) iy << 16 | (long) iz << 32 | (long) seed << 48;
            int offset = sampleOffsets.get(key) - 1;
            if (offset >= 0) {
                result[0] = sampleValues.getFloat(offset);
                result[1] = sampleValues.getFloat(offset + 1);
                result[2] = sampleValues.getFloat(offset + 2);
                return;
            }
            int equivalentAnchors = sampler.sample(view, x, y, z, anchorX, anchorY, anchorZ, result);
            long coordinate = key & 0xFFFFFFFFFFFFL;
            for (int anchor = 0; anchor < 8; anchor++) {
                if ((equivalentAnchors & 1 << anchor) != 0)
                    sampleOffsets.put(coordinate | (long) anchor << 48, sampleValues.size() + 1);
            }
            sampleValues.add(result[0]);
            sampleValues.add(result[1]);
            sampleValues.add(result[2]);
        } else {
            sampler.sample(view, x, y, z, anchorX, anchorY, anchorZ, result);
        }
    }

    private void clearSamples() {
        sampleOffsets.clear();
        sampleValues.clear();
    }

    void finishSampling() {
        clearSamples();
        sampleView = null;
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
