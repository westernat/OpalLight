package org.mesdag.opallight.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.mesdag.opallight.light.LightManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在实际世界渲染完成后追加彩光遮罩。
 *
 * <p>保留这一注入点是为了兼容会替换或重排 NeoForge 渲染阶段的渲染器；从局部变量取得的
 * 相机与视图矩阵和当前世界帧属于同一套状态，避免 VulkanMod/Iris 下使用过期矩阵。</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/client/ClientHooks;dispatchRenderStage(Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent$Stage;Lnet/minecraft/client/renderer/LevelRenderer;Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;ILnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;)V",
                    remap = false,
                    shift = At.Shift.AFTER
            )
    )
    private void renderColoredLight(
            CallbackInfo callback,
            @Local(name = "camera") Camera camera,
            @Local(name = "matrix4f") Matrix4f projectionMatrix,
            @Local(name = "matrix4f1") Matrix4f viewMatrix
    ) {
        LightManager.render(viewMatrix, projectionMatrix, camera);
    }
}
