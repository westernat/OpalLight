package org.mesdag.opallight.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.mesdag.opallight.light.ColoredLightBufferSource;
import org.mesdag.opallight.light.LightColorCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @ModifyArg(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"), index = 4)
    private MultiBufferSource colorEntity(MultiBufferSource source, @Local(argsOnly = true) Entity entity) {
        if (Minecraft.getInstance().level == null || LightColorCache.INSTANCE.isEmpty()) return source;
        if (entity instanceof ItemEntity item) {
            /// 掉落物的烘焙模型会在实体局部继续变换，直接采用其所在位置的彩光。
            long color = LightColorCache.INSTANCE.sample(item.getX(), item.getY() + 0.5, item.getZ());
            if (color != 0) return new ColoredLightBufferSource(source, color);
        }
        AABB bounds = entity.getBoundingBox();
        if (!LightColorCache.INSTANCE.hasColorNear(BlockPos.containing(bounds.getCenter()))
                && !LightColorCache.INSTANCE.hasColorNear(BlockPos.containing(entity.position()))) return source;
        return new ColoredLightBufferSource(source, Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
    }
}
