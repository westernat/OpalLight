package org.mesdag.opallight.light;

import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/// 注册/重载时一次验证 uniform，绘制只更新帧数据与通道状态。
final class LightMaskShader {
    final ShaderInstance shader;
    final Uniform groupOffset, transitionWeight, tintPass;
    private final Uniform skyBrightness, ambientLight;

    LightMaskShader(ShaderInstance shader) {
        this.shader = shader;
        groupOffset = required("GroupOffset");
        transitionWeight = required("TransitionWeight");
        tintPass = required("TintPass");
        skyBrightness = required("SkyBrightness");
        ambientLight = required("AmbientLight");
        required("LightSettings").set(LightBrightness.MASK_INTENSITY, LightBrightness.TINT_INTENSITY,
                LightBrightness.MIN_DAY_TINT_VISIBILITY, LightFalloff.EDGE_FADE_STRENGTH);
    }

    void apply(Matrix4f view, Matrix4f projection, @Nullable ClientLevel level) {
        shader.MODEL_VIEW_MATRIX.set(view);
        // 使用渲染事件中的投影快照，避免光影包的合成阶段改写全局矩阵。
        shader.PROJECTION_MATRIX.set(projection);
        skyBrightness.set(LightBrightness.skyBrightness(level));
        ambientLight.set(LightBrightness.ambientLight(level));
        shader.apply();
    }

    private Uniform required(String name) {
        Uniform uniform = shader.getUniform(name);
        if (uniform == null) throw new IllegalStateException("Missing colored light uniform: " + name);
        return uniform;
    }
}
