package org.mesdag.opallight.light;

/// 中心白色沿受遮挡的传播路径淡出，外缘透明度独立于光源的增亮上限。
final class LightFalloff {
    static final float CORE_WHITE_SHARE = 0.2F;
    static final float CORE_RADIUS = 3.0F;
    static final float EDGE_FADE_STRENGTH = 3.0F / 15.0F;

    private LightFalloff() {}

    static float coreWhite(float distance) {
        return CORE_WHITE_SHARE * (1.0F - smoothstep(distance / CORE_RADIUS));
    }

    static float edgeOpacity(float strength) {
        return smoothstep(strength / EDGE_FADE_STRENGTH);
    }

    private static float smoothstep(float value) {
        float t = Math.clamp(value, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }
}
