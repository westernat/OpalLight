package org.mesdag.opallight.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.mesdag.opallight.light.LightManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    /// 1.20.1 的 {@code renderLevel} 只接收投影矩阵，视锥矩阵需要在渲染位姿栈上自行捕获。
    @Unique
    private static Matrix4f opallight$frustumMatrix;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void captureFrustumMatrix(CallbackInfo ci, @Local(argsOnly = true) PoseStack poseStack) {
        opallight$frustumMatrix = new Matrix4f(poseStack.last().pose());
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FogRenderer;setupNoFog()V"))
    private void renderColoredLight(
            CallbackInfo ci,
            @Local(argsOnly = true) Camera camera
    ) {
        /// 在原版关闭地形雾前绘制遮罩，使用与世界方块相同的雾参数。
        Matrix4f frustumMatrix = opallight$frustumMatrix;
        if (frustumMatrix != null) LightManager.render(frustumMatrix, camera);
    }
}
