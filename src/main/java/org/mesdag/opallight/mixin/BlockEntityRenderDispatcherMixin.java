package org.mesdag.opallight.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.mesdag.opallight.light.ColoredLightBufferSource;
import org.mesdag.opallight.light.LightColorCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @ModifyArg(method = "setupAndRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"), index = 3)
    private static MultiBufferSource colorBlockEntity(MultiBufferSource source, @Local(argsOnly = true) BlockEntity entity) {
        if (entity.getLevel() == null) return source;
        long color = LightColorCache.INSTANCE.colorAtBlockEntity(entity.getBlockPos());
        return color == 0 ? source : new ColoredLightBufferSource(source, Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
    }
}
