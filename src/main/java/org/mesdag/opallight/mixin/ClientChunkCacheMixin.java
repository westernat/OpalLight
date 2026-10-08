package org.mesdag.opallight.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.mesdag.opallight.light.LightMaskMeshCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Inject(method = "onLightUpdate", at = @At("TAIL"))
    private void refreshSkyLight(LightLayer layer, SectionPos section, CallbackInfo ci) {
        if (layer == LightLayer.SKY) LightMaskMeshCache.markSkyLightChanged(section);
    }
}
