package org.mesdag.opallight.light;

/** 与 Minecraft 的 BlockPos、SectionPos 编码一致的无分配坐标工具。 */
final class PackedPosition {
    static final int DIRECTION_COUNT = 6;
    static final int[] DX = {0, 0, 0, 0, -1, 1};
    static final int[] DY = {-1, 1, 0, 0, 0, 0};
    static final int[] DZ = {0, 0, -1, 1, 0, 0};

    private static final int X_BITS = 26;
    private static final int Y_BITS = 12;
    private static final int Z_BITS = 26;
    private static final int Z_OFFSET = Y_BITS;
    private static final int X_OFFSET = Y_BITS + Z_BITS;
    private static final long X_MASK = (1L << X_BITS) - 1L;
    private static final long Y_MASK = (1L << Y_BITS) - 1L;
    private static final long Z_MASK = (1L << Z_BITS) - 1L;

    private PackedPosition() {
    }

    static long pack(int x, int y, int z) {
        return ((long) x & X_MASK) << X_OFFSET
                | ((long) z & Z_MASK) << Z_OFFSET
                | (long) y & Y_MASK;
    }

    static int x(long pos) {
        return (int) (pos >> X_OFFSET);
    }

    static int y(long pos) {
        return (int) (pos << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    static int z(long pos) {
        return (int) (pos << X_BITS >> (X_BITS + Y_BITS));
    }

    static long offset(long pos, int direction) {
        return offset(pos, DX[direction], DY[direction], DZ[direction]);
    }

    static long offset(long pos, int dx, int dy, int dz) {
        return pack(x(pos) + dx, y(pos) + dy, z(pos) + dz);
    }

    static long sectionKey(long pos) {
        return sectionKey(x(pos) >> 4, y(pos) >> 4, z(pos) >> 4);
    }

    static long sectionKey(int sectionX, int sectionY, int sectionZ) {
        return ((long) sectionX & 0x3FFFFFL) << 42
                | ((long) sectionZ & 0x3FFFFFL) << 20
                | (long) sectionY & 0xFFFFFL;
    }

    static int sectionX(long sectionKey) {
        return (int) (sectionKey >> 42);
    }

    static int sectionY(long sectionKey) {
        return (int) (sectionKey << 44 >> 44);
    }

    static int sectionZ(long sectionKey) {
        return (int) (sectionKey << 22 >> 42);
    }

    static int index(long pos) {
        return (y(pos) & 15) << 8 | (z(pos) & 15) << 4 | x(pos) & 15;
    }

    static long fromSectionIndex(long sectionKey, int index) {
        int x = sectionX(sectionKey) << 4 | index & 15;
        int y = sectionY(sectionKey) << 4 | index >>> 8 & 15;
        int z = sectionZ(sectionKey) << 4 | index >>> 4 & 15;
        return pack(x, y, z);
    }
}
