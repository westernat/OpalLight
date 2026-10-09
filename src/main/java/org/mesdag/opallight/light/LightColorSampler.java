package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;

// 只在八点插值范围内连通的体素之间混色，防止斜对角颜色穿过封闭方块面。
final class LightColorSampler {
    @FunctionalInterface
    interface Colors {
        long get(int x, int y, int z);
    }

    private static final Direction[] POSITIVE = {Direction.EAST, Direction.UP, Direction.SOUTH};
    private static final Direction[] NEGATIVE = {Direction.WEST, Direction.DOWN, Direction.NORTH};
    private final Colors colors;
    private final @Nullable Long2IntOpenHashMap edges;
    private final long[] samples = new long[8];
    private final float[] weights = new float[8];
    private final BlockPos.MutableBlockPos from = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos to = new BlockPos.MutableBlockPos();

    LightColorSampler(Colors colors, boolean cacheEdges) {
        this.colors = colors;
        edges = cacheEdges ? new Long2IntOpenHashMap() : null;
    }

    void clear() {
        if (edges != null) edges.clear();
    }

    int sample(@Nullable BlockGetter view, double x, double y, double z,
                int anchorX, int anchorY, int anchorZ, float[] result) {
        double gx = x - 0.5, gy = y - 0.5, gz = z - 0.5;
        int bx = (int) Math.floor(gx), by = (int) Math.floor(gy), bz = (int) Math.floor(gz);
        float fx = (float) (gx - bx), fy = (float) (gy - by), fz = (float) (gz - bz);
        result[0] = result[1] = result[2] = 0;
        int colored = 0;
        for (int i = 0; i < 8; i++) {
            int dx = i & 1, dy = i >> 1 & 1, dz = i >> 2;
            weights[i] = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy) * (dz == 0 ? 1 - fz : fz);
            samples[i] = weights[i] == 0 ? 0 : colors.get(bx + dx, by + dy, bz + dz);
            if (samples[i] != 0) colored |= 1 << i;
        }
        if (colored == 0) return 255;
        int reachable = 255;
        int equivalentAnchors = 255;
        if (view != null) {
            int seed = Math.clamp(anchorX - bx, 0, 1) | Math.clamp(anchorY - by, 0, 1) << 1
                    | Math.clamp(anchorZ - bz, 0, 1) << 2;
            reachable = 1 << seed;
            int frontier = reachable;
            while (frontier != 0) {
                int i = Integer.numberOfTrailingZeros(frontier);
                frontier &= ~(1 << i);
                for (int axis = 0; axis < 3; axis++) {
                    int neighbor = i ^ 1 << axis;
                    if ((reachable & 1 << neighbor) != 0 || weights[neighbor] == 0) continue;
                    Direction dir = (i & 1 << axis) == 0 ? POSITIVE[axis] : NEGATIVE[axis];
                    if (!canCross(view, bx + (i & 1), by + (i >> 1 & 1), bz + (i >> 2), dir)) continue;
                    reachable |= 1 << neighbor;
                    frontier |= 1 << neighbor;
                }
            }
            // 只有非零权重节点所属的同一连通分量可以共享结果。
            equivalentAnchors = weights[seed] != 0 ? reachable : 1 << seed;
        }
        for (int i = 0; i < 8; i++) {
            if ((reachable & colored & 1 << i) == 0) continue;
            LightColorData.addWeighted(samples[i], weights[i], result);
        }
        return equivalentAnchors;
    }

    private boolean canCross(BlockGetter view, int x, int y, int z, Direction dir) {
        long key = BlockPos.asLong(x, y, z);
        int shift = dir.ordinal() * 2;
        int cached = edges == null ? 0 : edges.get(key);
        int edge = cached >>> shift & 3;
        if (edge != 0) return edge == 2;
        from.set(x, y, z);
        to.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
        boolean open = false;
        if (from.getY() >= view.getMinBuildHeight() && from.getY() < view.getMaxBuildHeight()
                && to.getY() >= view.getMinBuildHeight() && to.getY() < view.getMaxBuildHeight()) {
            BlockState a = view.getBlockState(from), b = view.getBlockState(to);
            open = a.getLightBlock(view, from) < 15 && b.getLightBlock(view, to) < 15
                    && !Shapes.faceShapeOccludes(LightEngine.getOcclusionShape(view, from, a, dir),
                    LightEngine.getOcclusionShape(view, to, b, dir.getOpposite()));
        }
        if (edges != null) {
            int value = open ? 2 : 1;
            edges.put(key, cached | value << shift);
            long reverse = to.asLong();
            edges.put(reverse, edges.get(reverse) | value << (dir.getOpposite().ordinal() * 2));
        }
        return open;
    }
}
