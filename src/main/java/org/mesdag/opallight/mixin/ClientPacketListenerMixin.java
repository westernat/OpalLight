package org.mesdag.opallight.mixin;

import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.chunk.LightChunk;
import org.mesdag.opallight.light.LightColorCache;
import org.mesdag.opallight.light.LightManager;
import org.mesdag.opallight.light.OpalColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Shadow
    private ClientLevel level;

    @Inject(method = "applyLightData", at = @At("TAIL"))
    private void captureLightData(int x, int z, ClientboundLightUpdatePacketData data, CallbackInfo ci) {
        int minSY = SectionPos.blockToSectionCoord(level.getMinBuildHeight());
        int maxSY = SectionPos.blockToSectionCoord(level.getMaxBuildHeight() - 1);
        for (int sy = minSY; sy <= maxSY; sy++) {
            LightColorCache.INSTANCE.clearSection(SectionPos.of(x, sy, z));
        }

        LightChunk chunk = level.getChunkSource().getChunkForLighting(x, z);
        if (chunk == null) return;
        chunk.findBlockLightSources((pos, state) -> {
            ObjectIntPair<OpalColor> colorWithEmissive = LightManager.colorWithEmissive(level, pos, state);
            if (colorWithEmissive == null) return;
            LightManager.pendingToAdd(pos, colorWithEmissive);
        });
    }
}
