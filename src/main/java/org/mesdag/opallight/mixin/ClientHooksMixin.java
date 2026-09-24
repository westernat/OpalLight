package org.mesdag.opallight.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.neoforge.client.ClientHooks;
import org.mesdag.opallight.light.FirstPersonLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = ClientHooks.class, remap = false)
public abstract class ClientHooksMixin {
    @ModifyArg(method = "renderSpecificFirstPersonHand", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/client/event/RenderHandEvent;<init>(Lnet/minecraft/world/InteractionHand;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFLnet/minecraft/world/item/ItemStack;)V"),
            index = 2)
    private static MultiBufferSource colorCustomFirstPersonHand(MultiBufferSource source) {
        return FirstPersonLight.color(source);
    }
}
