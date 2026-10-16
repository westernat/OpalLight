package org.mesdag.opallight.light.render;

import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

final class LightMaskShader {
    final ShaderInstance shader;
    final Uniform groupOffset, transitionWeight, blendVertexColors;
    private final @Nullable Uniform fogStart, fogEnd, fogColor, fogShape;

    LightMaskShader(ShaderInstance shader) {
        this.shader = shader;
        groupOffset = required("GroupOffset");
        transitionWeight = required("TransitionWeight");
        blendVertexColors = required("BlendVertexColors");
        fogStart = shader.getUniform("FogStart");
        fogEnd = shader.getUniform("FogEnd");
        fogColor = shader.getUniform("FogColor");
        fogShape = shader.getUniform("FogShape");
    }

    void apply(Matrix4f view, Matrix4f projection) {
        shader.MODEL_VIEW_MATRIX.set(view);
        // 使用渲染事件中的投影快照，避免光影包的合成阶段改写全局矩阵。
        shader.PROJECTION_MATRIX.set(projection);
        shader.apply();
    }

    void applyFog(float start, float end, float red, float green, float blue, float alpha, int shape) {
        // 光影或资源包可能让部分雾参数被优化掉，只上传仍然存在的参数。
        if (fogStart != null) {
            fogStart.set(start);
            fogStart.upload();
        }
        if (fogEnd != null) {
            fogEnd.set(end);
            fogEnd.upload();
        }
        if (fogColor != null) {
            fogColor.set(red, green, blue, alpha);
            fogColor.upload();
        }
        if (fogShape != null) {
            fogShape.set(shape);
            fogShape.upload();
        }
    }

    private Uniform required(String name) {
        Uniform uniform = shader.getUniform(name);
        if (uniform == null) throw new IllegalStateException("Missing colored light uniform: " + name);
        return uniform;
    }
}
