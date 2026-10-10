package org.mesdag.opallight.light.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

final class LightBrightness {
    static final float TINT_INTENSITY = 0.35F;
    private static final float ENTITY_LIGHT_LEVEL = 12.0F;

    private LightBrightness() {
    }

    static float skyBrightness(@Nullable ClientLevel level) {
        if (level == null) return 0;
        return level.getSkyFlashTime() > 0 ? 1.0F : level.getSkyDarken(1.0F) * 0.95F + 0.05F;
    }

    static float ambientLight(@Nullable ClientLevel level) {
        return level == null ? 0 : level.dimensionType().ambientLight();
    }

    // 实体原版着色器没有独立加法通道，在 CPU 上保留天空光抑制。
    static float skyVisibility(int skyUv, float skyBrightness, float ambientLight) {
        float sky = Mth.clamp(skyUv / 240.0F, 0.0F, 1.0F);
        float brightness = Mth.lerp(ambientLight, sky / (4.0F - 3.0F * sky), 1.0F) * skyBrightness;
        return 1.0F - Mth.clamp(brightness, 0.0F, 1.0F);
    }

    static int entityLight(float strength, int skyUv, float skyBrightness, float ambientLight) {
        return Math.round(Mth.clamp(strength, 0.0F, 1.0F) * ENTITY_LIGHT_LEVEL
            * LightFalloff.edgeOpacity(strength) * skyVisibility(skyUv, skyBrightness, ambientLight)) << 4;
    }
}
