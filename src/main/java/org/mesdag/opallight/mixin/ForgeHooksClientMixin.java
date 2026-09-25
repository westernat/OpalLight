package org.mesdag.opallight.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.client.ForgeHooksClient;
import org.mesdag.opallight.light.FirstPersonLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/// 1.20.1 使用 {@code ForgeHooksClient} 代替 NeoForge 的 {@code ClientHooks}。
@Mixin(value = ForgeHooksClient.class, remap = false)
public abstract class ForgeHooksClientMixin {
    @ModifyArg(method = "renderSpecificFirstPersonHand", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/client/event/RenderHandEvent;<init>(Lnet/minecraft/world/InteractionHand;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFLnet/minecraft/world/item/ItemStack;)V"), index = 2)
    private static MultiBufferSource colorCustomFirstPersonHand(MultiBufferSource source) {
        return FirstPersonLight.color(source);
    }
}
