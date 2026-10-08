package org.mesdag.opallight.light;

/// 三个 16 位 RGB 系数共用二进制指数，缓存保留叠加后的 HDR 数据而不提前限亮。
final class LightColorData {
    private static final double CHANNEL_MAX = 65535.0;

    private LightColorData() {}

    static long pack(double red, double green, double blue) {
        double peak = Math.max(red, Math.max(green, blue));
        if (peak <= 0) return 0;
        int exponent = Math.max(0, Math.getExponent(peak));
        double scale = Math.scalb(1.0, exponent);
        if (scale < peak) scale = Math.scalb(1.0, ++exponent);
        return (long) exponent << 48 | Math.round(red / scale * CHANNEL_MAX) << 32
                | Math.round(green / scale * CHANNEL_MAX) << 16 | Math.round(blue / scale * CHANNEL_MAX);
    }

    static float channel(long packed, int shift) {
        return Math.scalb((packed >>> shift & 65535L) / 65535.0F, (int) (packed >>> 48));
    }

    static void addWeighted(long packed, float weight, float[] result) {
        float scale = Math.scalb(weight / 65535.0F, (int) (packed >>> 48));
        result[0] += (packed >>> 32 & 65535L) * scale;
        result[1] += (packed >>> 16 & 65535L) * scale;
        result[2] += (packed & 65535L) * scale;
    }

    /// 原版实体着色器使用有限范围的顶点色，只有这条兼容路径需要 CPU 显示转换。
    static long display(long packed) {
        return LightColorCache.toneMapped(channel(packed, 32), channel(packed, 16), channel(packed, 0));
    }

    static long maximum(long a, long b) {
        return pack(Math.max(channel(a, 32), channel(b, 32)), Math.max(channel(a, 16), channel(b, 16)),
                Math.max(channel(a, 0), channel(b, 0)));
    }
}
