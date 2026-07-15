package org.mesdag.opallight.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.mesdag.opallight.light.LightManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/// 捕获所有客户端区块槽位淘汰，包括不会发送 NeoForge 卸载事件的槽位替换。
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "unload", at = @At("HEAD"))
    private void captureChunkUnload(LevelChunk chunk, CallbackInfo ci) {
        LightManager.chunkUnload((ClientLevel) (Object) this, chunk);
    }
}
