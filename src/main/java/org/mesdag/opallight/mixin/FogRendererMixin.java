package org.mesdag.opallight.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.mesdag.opallight.light.LightManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void captureTerrainFog(Camera camera, FogRenderer.FogMode fogMode, float farPlaneDistance, boolean shouldCreateFog, float partialTick, CallbackInfo ci) {
        if (fogMode == FogRenderer.FogMode.FOG_TERRAIN) LightManager.captureTerrainFog();
    }
}
