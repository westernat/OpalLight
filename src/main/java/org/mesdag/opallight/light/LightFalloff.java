package org.mesdag.opallight.light;

final class LightFalloff {
    static final float EDGE_FADE_STRENGTH = 3.0F / 15.0F;

    private LightFalloff() {
    }

    static float edgeOpacity(float strength) {
        return smoothstep(strength / EDGE_FADE_STRENGTH);
    }

    private static float smoothstep(float value) {
        float t = net.minecraft.util.Mth.clamp(value, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }
}
