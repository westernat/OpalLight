package org.mesdag.opallight.light;

// 体素范围包含两端。
record LightUpdateBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    LightUpdateBounds union(LightUpdateBounds other) {
        return new LightUpdateBounds(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
            Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    boolean intersects(LightUpdateBounds other) {
        return minX <= other.maxX && maxX >= other.minX && minY <= other.maxY && maxY >= other.minY && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
