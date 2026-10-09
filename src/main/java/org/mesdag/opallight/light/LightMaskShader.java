package org.mesdag.opallight.light;

import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

final class LightMaskShader {
    final ShaderInstance shader;
    final Uniform groupOffset, transitionWeight, blendVertexColors;

    LightMaskShader(ShaderInstance shader) {
        this.shader = shader;
        groupOffset = required("GroupOffset");
        transitionWeight = required("TransitionWeight");
        blendVertexColors = required("BlendVertexColors");
    }

    void apply(Matrix4f view, Matrix4f projection) {
        shader.MODEL_VIEW_MATRIX.set(view);
        // 使用渲染事件中的投影快照，避免光影包的合成阶段改写全局矩阵。
        shader.PROJECTION_MATRIX.set(projection);
        shader.apply();
    }

    private Uniform required(String name) {
        Uniform uniform = shader.getUniform(name);
        if (uniform == null) throw new IllegalStateException("Missing colored light uniform: " + name);
        return uniform;
    }
}
