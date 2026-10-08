package org.mesdag.opallight.light;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/// 彩光的显示强度独立于传播范围；天空光按原版光照曲线抑制额外增亮。
final class LightBrightness {
    static final float MASK_INTENSITY = 0.3F;
    static final float TINT_INTENSITY = 1.0F;
    static final float MIN_DAY_TINT_VISIBILITY = 0.25F;
    private static final float ENTITY_LIGHT_LEVEL = 12.0F;

    private LightBrightness() {}

    /// 原版实体的 CPU 兼容公式；地形使用 include/light.glsl 中的 GPU 版本。
    static float mappedStrength(float peak) {
        return peak <= 0.9F ? peak : 0.9F + 0.1F * (peak - 0.9F) / (peak - 0.8F);
    }

    static float skyBrightness(@Nullable ClientLevel level) {
        if (level == null) return 0;
        return level.getSkyFlashTime() > 0 ? 1.0F : level.getSkyDarken(1.0F) * 0.95F + 0.05F;
    }

    static float ambientLight(@Nullable ClientLevel level) {
        return level == null ? 0 : level.dimensionType().ambientLight();
    }

    /// 原版实体兼容路径使用的天空光曲线，与 include/light.glsl 一致。
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
