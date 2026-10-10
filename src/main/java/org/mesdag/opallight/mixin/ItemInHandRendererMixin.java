package org.mesdag.opallight.mixin;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.mesdag.opallight.light.render.FirstPersonLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @ModifyVariable(method = "renderArmWithItem", at = @At("HEAD"), argsOnly = true)
    private MultiBufferSource colorFirstPersonHand(MultiBufferSource source) {
        return FirstPersonLight.color(source);
    }
}
