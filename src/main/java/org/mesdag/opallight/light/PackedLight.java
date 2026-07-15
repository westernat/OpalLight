package org.mesdag.opallight.light;

/// 将三个无符号四位光照通道压入 int 的低十二位。
final class PackedLight {
    private static final int CHANNEL_MASK = 15;

    private PackedLight() {
    }

    static int pack(int red, int green, int blue) {
        return clamp(red) << 8 | clamp(green) << 4 | clamp(blue);
    }

    static int red(int light) {
        return light >>> 8 & CHANNEL_MASK;
    }

    static int green(int light) {
        return light >>> 4 & CHANNEL_MASK;
    }

    static int blue(int light) {
        return light & CHANNEL_MASK;
    }

    static int max(int left, int right) {
        return pack(
                Math.max(red(left), red(right)),
                Math.max(green(left), green(right)),
                Math.max(blue(left), blue(right))
        );
    }

    static int attenuate(int light, int amount) {
        if (amount >= 16) {
            return 0;
        }
        int loss = Math.max(0, amount);
        return pack(red(light) - loss, green(light) - loss, blue(light) - loss);
    }

    static int fromColor(float red, float green, float blue, int emission) {
        int level = clamp(emission);
        float maximum = Math.max(red, Math.max(green, blue));
        if (level == 0 || !Float.isFinite(maximum) || maximum <= 0.0F) {
            return 0;
        }
        return pack(
                Math.round(Math.max(0.0F, red) / maximum * level),
                Math.round(Math.max(0.0F, green) / maximum * level),
                Math.round(Math.max(0.0F, blue) / maximum * level)
        );
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(CHANNEL_MASK, value));
    }
}
