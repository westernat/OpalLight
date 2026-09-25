package org.mesdag.opallight.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.mesdag.opallight.light.LightManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/// 只负责在世界渲染开始时抓取矩阵快照，实际绘制放到 {@code RenderLevelStageEvent.AFTER_LEVEL}，
/// 这样光影包在自己的合成阶段改写全局矩阵也不会影响遮罩。
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    /// 1.20.1 的 {@code renderLevel} 只接收投影矩阵，视锥矩阵需要在渲染位姿栈上自行捕获。
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void captureMatrices(CallbackInfo ci, @Local(argsOnly = true) PoseStack poseStack) {
        LightManager.captureMatrices(new Matrix4f(poseStack.last().pose()),
                new Matrix4f(RenderSystem.getProjectionMatrix()));
    }
}
