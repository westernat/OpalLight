package org.mesdag.opallight.light;

final class LightColorData {
    private LightColorData() {}

    static long pack(double red, double green, double blue) {
        // 沿用原始传播规则：各通道累加后限制为 1，再参与空间插值。
        return Math.round(Math.min(1.0, red) * 65535) << 32
            | Math.round(Math.min(1.0, green) * 65535) << 16
            | Math.round(Math.min(1.0, blue) * 65535);
    }

    static float channel(long packed, int shift) {
        return (packed >>> shift & 65535L) / 65535F;
    }

    static void addWeighted(long packed, float weight, float[] result) {
        float scale = weight / 65535F;
        result[0] += (packed >>> 32 & 65535L) * scale;
        result[1] += (packed >>> 16 & 65535L) * scale;
        result[2] += (packed & 65535L) * scale;
    }

    static long maximum(long a, long b) {
        return pack(Math.max(channel(a, 32), channel(b, 32)), Math.max(channel(a, 16), channel(b, 16)),
                Math.max(channel(a, 0), channel(b, 0)));
    }
}
